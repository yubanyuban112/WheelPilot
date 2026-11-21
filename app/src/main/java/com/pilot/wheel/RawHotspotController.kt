package com.pilot.wheel

import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.ViewConfiguration
import android.view.WindowManager
import com.pilot.wheel.core.ConfigRules
import com.pilot.wheel.core.DisplayGate
import com.pilot.wheel.core.HotspotHitRules
import com.pilot.wheel.core.HotspotRouter
import com.pilot.wheel.core.HotspotScope
import com.pilot.wheel.core.WindowRect
import com.pilot.wheel.core.navBarBottomPx
import com.pilot.wheel.core.systemGestureInsets
import com.pilot.wheel.core.toRect
import com.pilot.wheel.core.toWindowRect
import com.pilot.wheel.diag.TouchStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 旁路热区控制器：把「热区感知」从窗口触摸接收里彻底解耦出来。
 *
 * 职责：
 *  1. 通过 Shizuku shell 旁路跑输入通道（monitorInput / evdev / 无障碍 onMotionEvent），
 *     只监听不消费全局触摸（系统照常把触摸发给下层）；
 *  2. 在**专用 routing 线程**上完成分流判定（[HotspotRouter]），
 *     只有「热区命中 / 小窗外侧点击」才投递回主线程；
 *  3. 命中热区 → 呼出轮盘；普通点击/边缘手势 → 什么都不做，交给下层。
 *
 * 53 号改动（维度一）：
 *  - **主线程零 MOVE**：原实现对每个 onMotionEvent / onRawTouch 都 `main.post`，
 *    120–240Hz 下每秒上百条主线程消息与 UI/无障碍回调抢同一条队列；现改为
 *    `hotspot-router` 线程内判定，只有 TRIGGER / OUTSIDE_CLICK 回主线程；
 *  - **通道优先级可配**（[Prefs.rawChannelPriority]），默认与旧硬编码顺序完全一致；
 *  - **evdev 设备择优**：不再取 `first()`，按长宽比接近程度排序；全部超容差即记
 *    `AMBIGUOUS_TOUCH_DEVICE` 并保守放弃 evdev；
 *  - **消费闭环健康度**：monitor 通道读 [InputEventReceiverBridge.health]，
 *    p95 超阈或无事件过久即记 `CHANNEL_STATE_CHANGE ... DEGRADED`。
 *
 * 线程归属：
 *  - `hotspot-router`：全部分流判定与计数器写入（唯一写者，无需锁）；
 *  - 主线程：窗口/UI 动作（onHotSwipe / onOutsideClick）与状态上报；
 *  - `Dispatchers.IO`：Shizuku 绑定与通道启停（协程）。
 */
class RawHotspotController(
    private val service: PilotService,
    private val onHotSwipe: (fromLeft: Boolean) -> Unit,
    private val onOutsideClick: () -> Unit,
    /** 全部通道（Shizuku 两层 + 无障碍 onMotionEvent）都不可用时回调；PilotService 据此关闭开关并提示 */
    private val onBlocked: () -> Unit = {},
) {

    companion object {
        private const val TAG = "WheelPilot"

        /** 呼出最小位移 dp（对齐 SwipeDetector 的 22dp 门槛） */
        private const val THRESHOLD_DP = 22f
        /** 呼出后冷却，避免连续多次触发 */
        private const val COOLDOWN_MS = 500L
        /** 启动失败后重试间隔 */
        private const val RETRY_MS = 5_000L
        /** 55.1：退避上限。Shizuku 绑定可能晚于服务 init（重装后更久），重试不设次数上限 */
        private const val MAX_RETRY_MS = 30_000L
        /**
         * 连续失败多少次后**降级**（不再视为「可用」）。
         * 55.1 起该值只影响日志与降级语义，不再终止重试——否则冷启时 Shizuku 晚到
         * 会让「开关开着但通道永远不恢复」，实测 14:16 装机后 4 分钟内 evdev/coord 一直为空。
         */
        private const val MAX_FAILURES = 3

        /** monitor 通道健康判据：finish p95 上限（ms）——保守经验值，真机可标定 */
        private const val HEALTH_P95_MS = 50
        /** monitor 通道健康判据：多久没事件算「通道在但收不到」（ms） */
        private const val HEALTH_IDLE_MS = 8_000L
        /** 健康巡检周期（ms） */
        private const val HEALTH_TICK_MS = 5_000L

        /**
         * 62 号：静默看门狗的宽限期。
         *
         * 旁路通道「自认可用但一条事件都收不到」是本项目最贵的失效形态：
         * getevent 的 fd 在息屏/设备重枚举后会失效（read 永久阻塞）、pilot-shell 进程被
         * 回收时 binder 直接断开，两者都不会有任何回调 → `available` 永远为 true、
         * `TouchLayerState` 停在 GETEVENT、无障碍兜底被互斥挡掉。用户在真机上看到的就是
         * 「开着旁路触摸，正常用一会儿手机后轮盘再也滑不出来，也不会退回无障碍」。
         */
        /** 建链后的宽限期：刚建链就判静默会把尚在启动的采集线程反复杀掉 */
        private const val WARMUP_MS = 15_000L

        /**
         * 62.1：判「静默」的探测窗口。低于它的事件间隔属于正常停顿（看完一屏再滑一下）。
         * 超过它且无障碍同期收到了触摸 → 旁路确定已死（[isSilent] + [TouchStats.a11yMotionAt]）。
         */
        private const val SILENCE_PROBE_MS = 8_000L

        /**
         * 62.1：**盲判**兜底阈值（10 分钟）。
         *
         * 旧实现把「30s 零事件」直接当死亡，等于用户只要停下来看 30 秒就重启一次采集：
         * 实测 18:07 那轮就是 `reason=degraded:silent=30014ms` 空转重启（没有任何真实故障）。
         * 现在主判据换成「无障碍看得到触摸、旁路看不到」，只有连无障碍都没有触摸可言时
         * 才用这个长阈值兜底（真死且用户一直没碰屏幕 → 最迟 10 分钟内自愈）。
         */
        private const val SILENCE_BLIND_MS = 600_000L

        /** evdev 设备长宽比容差：超过即认为不是主屏 */
        private const val DEVICE_ASPECT_TOLERANCE = 0.12f

        private const val ACTION_DOWN = 0
        private const val ACTION_UP = 1
        private const val ACTION_MOVE = 2
    }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 分流线程：判定不落主线程（53 号核心改动）。懒启动——控制器会随设置变更重建，不能每次构造都起线程 */
    private val routerThread by lazy { HandlerThread("hotspot-router").apply { start() } }
    private val routerHandler by lazy { Handler(routerThread.looper) }

    /** 当前采集的触摸屏设备（evdev 降级路径用；monitorInput 路径下为 null） */
    private var device: PilotShellClient.RawTouchDevice? = null

    /** 当前旁路模式：monitorInput（true）或 evdev（false） */
    @Volatile
    private var usingMonitor = false

    @Volatile
    private var running = false

    @Volatile
    private var available = false

    /** 通道降级原因（快照/设置页展示用） */
    @Volatile
    private var degradedReason = ""

    @Volatile
    private var lastTriggerAt = 0L

    /** 62 号：最近一次收到触摸事件（任一带宽通道）——静默看门狗的唯一判据 */
    @Volatile
    private var lastEventAt = 0L

    /** 62 号：本次建链时刻。刚建链时 lastEventAt 为 0，用建链时间做静默基线 */
    @Volatile
    private var channelUpAt = 0L

    /**
     * 70 号：本次建链后旁路是否真正收到过触摸事件。
     * 建链成功 ≠ 能收到事件——markChannelUp() 把静默基线重置为「现在」，仅靠 isSilent()
     * 会让「亮屏重建窗口内的第一滑」既不被旁路消费、也不被无障碍接管（掉进缝里）。
     * 只有 onRawTouch/onMotionEvent 真实收到事件才置 true；重建/建链时置 false。
     */
    @Volatile
    private var bypassConfirmed = false

    /** 62 号：重启互斥，避免健康巡检 / 线程退出 / 重试三路叠加重复停采集 */
    @Volatile
    private var restarting = false

    /** 连续失败计数：达到 [MAX_FAILURES] 后降级（55.1 起不再终止重试） */
    private var consecutiveFailures = 0

    /** 55.1：退避重试的当前间隔（成功建链后复位为 [RETRY_MS]） */
    private var retryDelayMs = RETRY_MS

    /** 分流路由器（routing 线程独占；几何变化时重建） */
    @Volatile
    private var router: HotspotRouter? = null

    /** 上次坐标域快照，用于旋转/尺寸变化时重算 */
    private var lastRotation = -1
    private var lastBounds: Rect = Rect()

    private val touchSlop: Float =
        ViewConfiguration.get(service).scaledTouchSlop.toFloat()

    private val callback = object : IRawTouchCallback.Stub() {
        override fun onRawTouch(type: Int, rawX: Float, rawY: Float, slot: Int) {
            // 62 号：先盖时间戳再看通道开销——静默看门狗要的是「有没有事件」而不是「事件是否被消费」
            lastEventAt = SystemClock.elapsedRealtime()
            bypassConfirmed = true // 70 号：旁路确实收到事件，确认存活，交回旁路通道
            // 采集回调（binder 线程）→ 判定线程；不经过主线程
            routerHandler.post { handleRawTouch(type, rawX, rawY) }
        }

        override fun onCollectorState(state: Int, detail: String?) {
            if (state == PilotShellService.STATE_THREAD_EXIT) {
                // 采集线程异常退出：降级到下一通道，但 running 保持 true
                // （线程退出 ≠ 用户关闭旁路，仍要按 RETRY_MS 重试拉起）。
                main.post {
                    if (!running) return@post
                    available = false
                    degradedReason = "collector_exit:$detail"
                    PilotLog.d(service, PilotService.TAG, "touch collector exited: $detail")
                    settleOnAccessibility("采集线程退出")
                }
            }
        }

        override fun onMotionEvent(action: Int, x: Float, y: Float, eventTime: Long) {
            // 62 号：同上，monitorInput 通道的事件也计入存活证据
            lastEventAt = SystemClock.elapsedRealtime()
            bypassConfirmed = true // 70 号：monitorInput 通道收到事件，同样确认旁路存活
            // monitorInput 路径：屏幕逻辑坐标 → 判定线程
            routerHandler.post { handleMotionEvent(action, x, y, eventTime) }
        }
    }

    fun start() {
        if (running) return
        running = true
        scope.launch { setup() }
    }

    fun stop() {
        running = false
        available = false
        restarting = false
        channelUpAt = 0L
        lastEventAt = 0L
        router = null
        TouchLayerState.set(TouchLayerState.Layer.NONE, "已关闭")
        routerHandler.removeCallbacksAndMessages(null)
        main.removeCallbacks(healthTick)
        main.removeCallbacks(retryTick)
        retryDelayMs = RETRY_MS
        scope.launch {
            runCatching { PilotShellClient.attachRawTouchCallback(null) }
            runCatching { PilotShellClient.stopMonitorTouch() }
            runCatching { PilotShellClient.stopRawTouch() }
        }
    }

    /** 旁路是否可用（通道已建立且线程活着）。层切换由 [TouchLayerState] 统一上报 */
    fun isAvailable(): Boolean = available

    /** 最近一次降级原因，供快照判定 */
    fun degradedDetail(): String = degradedReason

    /** 快照用：只读访问路由器计数器（线程安全，router 内部字段为 volatile） */
    fun routerForStats(): HotspotRouter? = router

    private suspend fun setup() {
        if (!running) return
        if (!PilotShellClient.possible()) {
            settleOnAccessibility("Shizuku 未连接（未授权或未运行）")
            return
        }
        // 1) 注册回调（各通道共用）
        val attached = PilotShellClient.attachRawTouchCallback(callback)
        if (!attached) {
            settleOnAccessibility("触摸回调注册失败")
            return
        }
        // 2) 按配置优先级依次尝试；默认值 = 旧硬编码顺序，语义不变
        for (channel in Prefs.rawChannelPriority(service)) {
            val ok = when (channel) {
                "monitorInput" -> tryMonitorInput()
                "evdev", "getevent" -> tryGetEvent()
                // 无障碍兜底由 settleOnAccessibility 统一处理，不作为主动启动项
                "a11y" -> false
                else -> {
                    Log.w(TAG, "raw-hotspot: unknown channel '$channel' ignored")
                    false
                }
            }
            if (ok) return
        }
        settleOnAccessibility("配置的通道均不可用：${Prefs.rawChannelPriority(service)}")
    }

    /**
     * 通道都不可用 → 落到无障碍 onMotionEvent 兜底（窗口仍 NOT_TOUCHABLE，
     * 由 [PilotService.onMotionEvent] 接管，无需重建窗口）。
     * 系统 < 34（无 setMotionEventSources）时彻底不可用，通知 [onBlocked] 关闭开关。
     */
    private fun settleOnAccessibility(reason: String) {
        available = false
        consecutiveFailures++
        degradedReason = reason
        if (TouchLayerState.accessibilitySupported()) {
            TouchLayerState.set(TouchLayerState.Layer.ACCESSIBILITY, "等待触摸事件", reason)
            Log.w(TAG, "raw-hotspot: $reason -> 无障碍 onMotionEvent 兜底 (fail $consecutiveFailures/$MAX_FAILURES)")
        } else {
            TouchLayerState.set(TouchLayerState.Layer.NONE, "", "$reason；系统 <34 无无障碍兜底")
            Log.e(TAG, "raw-hotspot: 全部通道不可用，通知关闭旁路触摸 (fail $consecutiveFailures/$MAX_FAILURES)")
            main.post { onBlocked() }
        }
        // 55.1：不论失败几次都继续按退避重试。旧实现在第 3 次（约 15s）后永久放弃，
        // 而重装/冷启时 Shizuku 绑定常常晚于服务 init —— 那一刻放弃就等于「开关开着却不工作」。
        scheduleRetry()
    }

    /** 尝试 monitorInput 通道（首选，不读 /dev/input） */
    private suspend fun tryMonitorInput(): Boolean {
        InputEventReceiverBridge.resetHealth()
        val started = PilotShellClient.startMonitorTouch()
        if (!started) {
            Log.w(TAG, "raw-hotspot: monitorInput unavailable, trying next channel")
            return false
        }
        usingMonitor = true
        device = null
        TouchStats.evdevDescriptor = "none"
        consecutiveFailures = 0
        retryDelayMs = RETRY_MS
        available = true
        degradedReason = ""
        markChannelUp()
        TouchLayerState.set(TouchLayerState.Layer.MONITOR_INPUT)
        buildRouter()
        startHealthWatch()
        Log.w(TAG, "raw-hotspot: active via monitorInput (no /dev/input)")
        return true
    }

    /** 尝试 evdev 通道（降级兜底，仍只读不消费） */
    private suspend fun tryGetEvent(): Boolean {
        val devices = PilotShellClient.probeTouchDevices()
        if (devices.isEmpty()) {
            Log.w(TAG, "raw-hotspot: no touch device for evdev")
            return false
        }
        val picked = pickTouchDevice(devices) ?: return false
        val started = PilotShellClient.startRawTouch(picked.path)
        if (!started) return false
        usingMonitor = false
        device = picked
        TouchStats.evdevDescriptor = "${picked.path}|${picked.xMax}|${picked.yMax}"
        consecutiveFailures = 0
        retryDelayMs = RETRY_MS
        available = true
        degradedReason = ""
        markChannelUp()
        TouchLayerState.set(TouchLayerState.Layer.GETEVENT, picked.path)
        buildRouter()
        // 62 号：evdev 也要开健康巡检。旧实现只在 monitorInput 里启动，
        // 而本机（Android 17）实际跑的就是 evdev —— 等于全程没有任何存活检查。
        startHealthWatch()
        Log.w(TAG, "raw-hotspot: active via evdev ${picked.path} (${picked.xMax}x${picked.yMax})")
        return true
    }

    /**
     * evdev 设备择优（53 号 · 第 11 项）。
     *
     * 旧实现取 `devices.first()`：厂商 IDC 缺失时 `getevent -lp` 可能把副屏/手环列在前面，
     * 顺序一变就误判主触摸屏，坐标全错、热区永不命中，且日志一片安静。
     *
     * 现在按「长宽比与当前屏幕的接近程度」排序；最佳候选仍超容差 → 记
     * `AMBIGUOUS_TOUCH_DEVICE` 并放弃 evdev，宁可降级到无障碍，也不拿错设备假装在工作。
     */
    private fun pickTouchDevice(devices: List<PilotShellClient.RawTouchDevice>): PilotShellClient.RawTouchDevice? {
        val dm = service.resources.displayMetrics
        val screenAspect = dm.widthPixels.toFloat() / dm.heightPixels
        val ranked = devices.sortedBy { dev ->
            if (dev.xMax <= 0 || dev.yMax <= 0) Float.MAX_VALUE
            else abs(dev.xMax.toFloat() / dev.yMax - screenAspect)
        }
        val best = ranked.firstOrNull() ?: return null
        if (best.xMax <= 0 || best.yMax <= 0) {
            reportAmbiguous(devices, "invalid-range")
            return null
        }
        val devAspect = best.xMax.toFloat() / best.yMax
        val deviation = abs(devAspect - screenAspect) / screenAspect
        if (deviation > DEVICE_ASPECT_TOLERANCE) {
            reportAmbiguous(devices, "aspect")
            return null
        }
        return best
    }

    private fun reportAmbiguous(devices: List<PilotShellClient.RawTouchDevice>, cause: String) {
        val cands = devices.joinToString(",") { "${it.path}:${it.xMax}x${it.yMax}" }
        Log.w(TAG, "AMBIGUOUS_TOUCH_DEVICE cause=$cause cands=$cands picked=none")
        PilotShellClient.trace("AMBIGUOUS_TOUCH_DEVICE", "cause=$cause cands=$cands")
    }

    /**
     * 构建/刷新分流路由器。
     *
     * 坐标域（55 号修正）：本应用热区是**显示级覆盖窗**
     * （`TYPE_ACCESSIBILITY_OVERLAY + FLAG_LAYOUT_NO_LIMITS + Gravity.BOTTOM`），贴的是
     * **物理底边**，因此判定域必须取物理显示边界（`maximum ∪ current`，见
     * [DisplayGate.unionBounds]），不能只看 `currentWindowMetrics`——后者在带导航条的竖屏上
     * 可能少一段（参考机实测 65px），会让判定区整体上浮、与看得见的把手错位。
     *
     * 同时把「热区离底距离」统一成视觉窗口那一套 [ConfigRules.hotspotBottomPx]
     * （用户值 / 导航栏+8dp / 内缩三者取 max）：旧实现判定用用户值、窗口用护栏值，
     * 用户把离底调到很小的值时两者会静默错位。
     *
     * 每次重建落一行 `GATE`，`dTop` 非 0 即说明仍有几何错位（判定区 vs 视觉窗口）。
     */
    private fun buildRouter() {
        val wm = service.getSystemService(WindowManager::class.java)
        val dm = service.resources.displayMetrics
        val density = dm.density
        val current = runCatching { wm.currentWindowMetrics.bounds.toWindowRect() }.getOrNull()
        val maximum = runCatching { wm.maximumWindowMetrics.bounds.toWindowRect() }.getOrNull()
        val physical = DisplayGate.unionBounds(
            current, maximum, WindowRect(0, 0, dm.widthPixels, dm.heightPixels),
        )
        val rotation = runCatching {
            service.getSystemService(android.hardware.display.DisplayManager::class.java)
                ?.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.rotation
        }.getOrNull() ?: lastRotation.coerceAtLeast(0)
        val gesture = wm.systemGestureInsets()
        val navBottom = wm.navBarBottomPx()
        val insetPx = (Prefs.hotInset(service) * density).toInt()
        val widthPx = (Prefs.hotWidth(service) * density).toInt()
        val heightPx = (Prefs.hotHeight(service) * density).toInt()
        // 与 addHotspotViews 的视觉窗口同一套基准
        val bottomPx = ConfigRules.hotspotBottomPx(
            bottomDp = Prefs.hotBottom(service),
            density = density,
            navBarPx = navBottom,
            edgeInsetPx = insetPx,
        )
        val cfg = HotspotRouter.Config(
            bounds = physical.toRect(),
            rotation = rotation,
            hotWidthDp = Prefs.hotWidth(service),
            hotHeightDp = Prefs.hotHeight(service),
            hotInsetDp = Prefs.hotInset(service),
            // 传「等效 dp」：HotspotRouter 内部 dp×density 还原出与视觉窗口一致的 px
            hotBottomDp = bottomPx / density,
            density = density,
            triggerPx = THRESHOLD_DP * density,
            tapTolerancePx = touchSlop,
            edgeBand = intArrayOf(gesture[0], gesture[1], gesture[2], gesture[3]),
        )
        val existing = router
        val isNew = existing == null
        if (!isNew && !existing.updateConfig(cfg)) return
        if (isNew) router = HotspotRouter(cfg)
        lastRotation = rotation
        lastBounds = physical.toRect()
        TouchStats.coordDomain = "phys=${DisplayGate.shortString(physical)}" +
            " rot=$rotation edge=${gesture[0]},${gesture[1]},${gesture[2]},${gesture[3]}"
        val mismatch = physical.width != dm.widthPixels || physical.height != dm.heightPixels
        val line = "${TouchStats.coordDomain}" +
            " cur=${current?.let { DisplayGate.shortString(it) } ?: "-"}" +
            " max=${maximum?.let { DisplayGate.shortString(it) } ?: "-"}" +
            " dm=${dm.widthPixels}x${dm.heightPixels} nav=$navBottom mismatch=$mismatch"
        // trace() 会自带事件码作为 tag，这里消息体不再重复事件码
        Log.w(TAG, "COORD_DOMAIN_RECOMPUTE $line")
        PilotShellClient.trace("COORD_DOMAIN_RECOMPUTE", line)
        traceGate(
            physical = physical,
            current = current,
            maximum = maximum,
            rotation = rotation,
            navBottomPx = navBottom,
            widthPx = widthPx,
            heightPx = heightPx,
            insetPx = insetPx,
            bottomPx = bottomPx,
            density = density,
            dmWidth = dm.widthPixels,
            dmHeight = dm.heightPixels,
        )
    }

    /**
     * 55 号：几何自检落盘。`dTop` = 判定矩形顶端 − 视觉窗口顶端（px）。
     * 0 = 判定与视觉同源；非 0 就是「按在把手上判不中」的量化证据。
     */
    private fun traceGate(
        physical: WindowRect,
        current: WindowRect?,
        maximum: WindowRect?,
        rotation: Int,
        navBottomPx: Int,
        widthPx: Int,
        heightPx: Int,
        insetPx: Int,
        bottomPx: Int,
        density: Float,
        dmWidth: Int,
        dmHeight: Int,
        retry: Boolean = true,
    ) {
        // 显式 Unit 返回类型：retry 分支递归引用本函数，表达式体（Result<Unit>）会触发
        // Kotlin 的递归类型推断错误 TYPECHECKER_HAS_RUN_INTO_RECURSIVE_PROBLEM
        runCatching {
        val judge = DisplayGate.sectorBoundsRect(physical, insetPx, bottomPx, widthPx, heightPx, left = true)
        val visual = DisplayGate.visualRect(physical, widthPx, heightPx, insetPx, bottomPx, left = true)
        val g = DisplayGate.Geometry(
            physical = physical,
            current = current ?: physical,
            maximum = maximum ?: physical,
            dmWidth = dmWidth,
            dmHeight = dmHeight,
            navBottomPx = navBottomPx,
            rotation = rotation,
        )
        // 旧实现按 currentWindowMetrics 推导判定区；dOld 记录「若仍按旧口径会错多少」，供回归比对
        val dOld = current?.let {
            DisplayGate.verticalDriftPx(
                DisplayGate.judgeRect(it, widthPx, heightPx, insetPx, bottomPx, left = true), visual,
            )
        } ?: 0
        val text = DisplayGate.gateLine(g, judge, visual, density, extra = "shape=ellipse src=bypass dOld=$dOld")
        Log.w(TAG, "GATE $text")
        if (PilotShellClient.isTraceReady()) {
            PilotShellClient.trace("GATE", text)
        } else if (retry) {
            // 55.1：首帧常早于 Shizuku 绑定，trace() 会静默丢弃 → 3s 后补发一次
            main.postDelayed({ traceGate(physical, current, maximum, rotation, navBottomPx, widthPx, heightPx, insetPx, bottomPx, density, dmWidth, dmHeight, retry = false) }, 3_000L)
        }
        }.onFailure { Log.w(TAG, "GATE trace failed", it) }
    }

    /** 旋转/尺寸变化后必须重算坐标域，否则热区整体错位（典型症状：横屏跑到对侧） */
    private fun ensureDomainFresh() {
        val wm = service.getSystemService(WindowManager::class.java) ?: return
        val rotation = runCatching {
            service.getSystemService(android.hardware.display.DisplayManager::class.java)
                ?.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.rotation
        }.getOrNull() ?: lastRotation
        // 55 号：判据也用物理边界，否则 current 在导航条处变一次、物理边界没变时会被漏掉/误触重算
        val dm = service.resources.displayMetrics
        val bounds = runCatching { wm.currentWindowMetrics.bounds.toWindowRect() }.getOrNull()
        val maximum = runCatching { wm.maximumWindowMetrics.bounds.toWindowRect() }.getOrNull()
        val physical = DisplayGate.unionBounds(
            bounds, maximum, WindowRect(0, 0, dm.widthPixels, dm.heightPixels),
        )
        if (rotation != lastRotation || physical != lastBounds.toWindowRect()) buildRouter()
    }

    // -----------------------------------------------------------------------
    // 健康巡检（维度一 · 第 2 项）
    // -----------------------------------------------------------------------

    /** 62 号：建链成功时刷新静默基线（lastEventAt 可能还停在上一轮通道的值上） */
    private fun markChannelUp() {
        val now = SystemClock.elapsedRealtime()
        channelUpAt = now
        lastEventAt = now
        // 70 号：重建只是「建好管道」，还没证明能收到事件。清空确认标志，
        // 在第一个真实事件到来前由无障碍兜底接管，覆盖亮屏后的第一滑。
        bypassConfirmed = false
    }

    /** 62.1：浏览器是否已静默到「可疑」——供无障碍侧判断要不要接管本次手势 */
    fun isSilent(): Boolean {
        if (!running || !available) return false
        val base = maxOf(lastEventAt, channelUpAt)
        if (base <= 0L) return false
        return SystemClock.elapsedRealtime() - base > SILENCE_PROBE_MS
    }

    /**
     * 70 号：无障碍是否应接管本次手势（替代裸 [isSilent] 判据）。
     * = 旁路尚未确认收到过事件（重建窗口） OR 已静默到可疑。
     * 这样亮屏重建后、第一个真实旁路事件到来前，用户第一滑也由 a11y 走完，不再落空。
     */
    fun shouldA11yTakeover(): Boolean = !bypassConfirmed || isSilent()

    /**
     * 62.1：无障碍侧看到触摸、而旁路静默 → 旁路收不到事件的实证，立即重启。
     *
     * 由 `PilotService.onMotionEvent` 在主线程调用；不等 5s 巡检，避免用户白滑一下。
     */
    fun onA11yEvidence() {
        if (!running || !available) return
        val now = SystemClock.elapsedRealtime()
        val silence = now - maxOf(lastEventAt, channelUpAt)
        if (silence < SILENCE_PROBE_MS) return
        restartQuietly("dead_silent", "a11y_seen silent=${silence}ms")
    }

    /**
     * 72 号：亮屏**不再主动掐断重建**，只把静默基线刷到「现在」。
     *
     * 68 号曾在此无条件 restartQuietly（stop 采集 + 切无障碍层 + ~100ms 后重建），
     * 真机 trace 证明这是「息屏几秒第一滑唤不出」的真正根因：
     *  - 短息屏（几秒）时 native 线程和 fd 根本没死（0 次 device_gone / STATE_THREAD_EXIT / EOF），
     *    却被无条件 stop→restart，制造 ~100ms 无采集窗口；
     *  - 该窗口要靠 a11y onMotionEvent 兜底，但本机 a11y 收不到触摸事件
     *    （25 次降级全 silent 盲判、0 次 a11y_seen、BYPASS_TAKEOVER 从不出现），
     *    于是第一滑既无人消费也无人接管 → 落空。
     *
     * 真正的死亡由既有机制兜底，无需亮屏重建：
     *  ①69 号 native poll 的 POLLERR/HUP/NVAL → device_gone 即时回传重建；
     *  ②68 号 available() pingBinder → link_down 巡检重建；
     *  ③10 分钟静默盲判 → 兜底最后一道。
     * 亮屏只需重置 lastEventAt/channelUpAt，避免息屏期间的「无事件」被误判为静默死亡。
     */
    fun onScreenOn() {
        if (!running) return
        val now = SystemClock.elapsedRealtime()
        channelUpAt = now
        lastEventAt = now
        PilotShellClient.trace("SCREEN_ON_BASELINE", "亮屏仅刷新静默基线，不掐断采集")
    }

    /**
     * 62.1：诊断落盘保底。
     *
     * `PilotShellClient.trace` 在 shell 未绑定时**静默丢弃整行**，而「通道刚死」正是
     * 最需要留证据的时刻——实测 `BYPASS_RESTART` 就这样丢过一次。这里发现通道不可用就
     * 3s 后补发一行（_LATE），与 55.1 的 GATE 补发同一套做法。
     */
    private fun traceResilient(tag: String, message: String) {
        PilotShellClient.trace(tag, message)
        if (!PilotShellClient.isTraceReady()) {
            main.postDelayed({ PilotShellClient.trace("${tag}_LATE", message) }, 3_000L)
        }
    }

    private val healthTick = object : Runnable {
        override fun run() {
            if (!running || !available) return
            // 1) 链路存活：pilot-shell 被系统回收 / Shizuku 退出 / 绑定过期，都表现为 binder 不可用。
            //    这是真机「用一会儿就失效」最常见的一种，且**不会有任何回调**——只能巡检发现。
            if (!PilotShellClient.available()) {
                restartQuietly("link_down", "client=${PilotShellClient.describe()}")
                return
            }
            // 2) monitorInput 指标：p95 超阈说明处理链路已经跟不上手指，直接重建
            var restartReason: String? = null
            if (usingMonitor) {
                val text = InputEventReceiverBridge.health()
                val p95 = Regex("p95=(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val idle = Regex("idle=(-?\\d+)").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: -1L
                if (p95 > HEALTH_P95_MS) {
                    restartReason = "monitor_p95=${p95}ms"
                } else if (idle > HEALTH_IDLE_MS) {
                    // 仅告警不重建：8s 没触摸在正常使用里太常见，重建会变成周期性抖动
                    degradedReason = "monitor idle=${idle}ms"
                    Log.w(TAG, "CHANNEL_STATE_CHANGE monitor=DEGRADED reason=$degradedReason")
                    PilotShellClient.trace("CHANNEL_STATE_CHANGE", "from=MONITOR_INPUT to=DEGRADED reason=$degradedReason")
                }
            }
            // 3) 静默看门狗（两条通道共用），62.1 改为**实证优先**：
            //    旁路收不到事件时，它自己拿不出证据；而无障碍通道同期收到了触摸，
            //    就是「旁路已死」的直接反证。盲判（只看多久没事件）只留 10 分钟兜底，
            //    避免用户站着看 30 秒就被重启一次采集。
            val now = SystemClock.elapsedRealtime()
            val base = maxOf(lastEventAt, channelUpAt)
            val silence = now - base
            if (restartReason == null && channelUpAt > 0L &&
                now - channelUpAt > WARMUP_MS && base > 0L
            ) {
                val a11y = TouchStats.a11yMotionAt
                restartReason = when {
                    a11y > base && silence > SILENCE_PROBE_MS ->
                        "dead_silent(a11y_seen=${now - a11y}ms ago silent=${silence}ms)"
                    silence > SILENCE_BLIND_MS -> "silent=${silence}ms"
                    else -> null
                }
            }
            if (restartReason != null) {
                restartQuietly("degraded", restartReason)
                return
            }
            main.postDelayed(this, HEALTH_TICK_MS)
        }
    }

    /**
     * 62 号：通道判死 → 停采集 → 重建。
     *
     * 三个动作缺一不可：
     *  1. `available = false`：让 [retryTick] 重新接管，同时立刻停止把事件送进路由；
     *  2. **先**把层切到无障碍：通道已判死时，用户下一秒滑不出轮盘就是 bug，
     *     不能等 setup() 走完 Shizuku 绑定（冷启/重装时可能几秒）；
     *  3. 停掉旧采集：否则 setup() 里 `startRawTouch / startMonitorTouch` 会因
     *     「已在跑」直接返回 false，重连永远失败——这是旧实现把「重试」写成空转的坑。
     */
    private fun restartQuietly(reason: String, detail: String) {
        if (!running || restarting) return
        restarting = true
        available = false
        degradedReason = "$reason:$detail"
        Log.w(TAG, "BYPASS_RESTART reason=$reason detail=$detail layer=${TouchLayerState.current}")
        // 62.1：链路断的那一刻 shell 往往已不可用，直接用 trace 会整行丢掉 → 走保底补发
        traceResilient("BYPASS_RESTART", "reason=$reason detail=$detail layer=${TouchLayerState.current}")
        if (TouchLayerState.accessibilitySupported()) {
            TouchLayerState.set(TouchLayerState.Layer.ACCESSIBILITY, "重启旁路通道中", "$reason:$detail")
        }
        scope.launch {
            runCatching { PilotShellClient.stopMonitorTouch() }
            runCatching { PilotShellClient.stopRawTouch() }
            channelUpAt = 0L
            lastEventAt = 0L
            // setup() 成功 → 层切回 MONITOR_INPUT/GETEVENT 并重开巡检；
            // 失败 → 内部 settleOnAccessibility 保底并排重试，功能不中断
            runCatching { setup() }
            restarting = false
        }
    }

    private fun startHealthWatch() {
        main.removeCallbacks(healthTick)
        main.postDelayed(healthTick, HEALTH_TICK_MS)
    }

    private fun scheduleRetry() {
        if (!running) return
        main.removeCallbacks(retryTick)
        val delay = retryDelayMs.coerceAtMost(MAX_RETRY_MS)
        main.postDelayed(retryTick, delay)
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_MS)
    }

    /** 55.1：退避重试任务。可 removeCallbacks 去重，避免多路（健康巡检/线程退出/初始化）叠加排队 */
    private val retryTick = object : Runnable {
        override fun run() {
            // 62 号：restarting 期间的重启由 restartQuietly 自己负责，别叠加第二路 setup
            if (!running || available || restarting) return
            scope.launch { setup() }
        }
    }

    // -----------------------------------------------------------------------
    // 统一分流入口（routing 线程）
    // -----------------------------------------------------------------------

    private fun handleMotionEvent(action: Int, x: Float, y: Float, eventTime: Long) {
        if (!available || !usingMonitor) return
        route(action, x, y, eventTime)
    }

    /** 判定在这里完成；只有命中/外侧点击才回主线程 */
    private fun route(action: Int, x: Float, y: Float, eventTime: Long) {
        val r = router ?: return
        val masked = action and 0xff
        if (masked == ACTION_DOWN) {
            ensureDomainFresh()
            // 坐标域自检：DOWN 落在当前显示区域之外，说明归一化/旋转/域快照已经错位
            // （典型症状：分屏或横屏时热区整体偏移）。这是唯一能在"无触发"状态下
            // 证明映射错误的信号，必须计数并落盘。
            val b = r.snapshotOfConfig().bounds
            if (x < b.left || x > b.right || y < b.top || y > b.bottom) {
                TouchStats.downOutOfBounds++
                val detail = "x=$x y=$y bounds=${b.left},${b.top}-${b.right},${b.bottom}"
                Log.w(TAG, "DOWN_OUT_OF_BOUNDS $detail")
                PilotShellClient.trace("DOWN_OUT_OF_BOUNDS", detail)
            }
        }
        TouchStats.bumpRoute()
        when (val result = r.feed(action, x, y, eventTime)) {
            HotspotRouter.Route.EDGE_EXCLUDE -> {
                TouchStats.edgeExclude++
                if (masked == ACTION_DOWN) {
                    // 与「下层已被系统 CANCEL」区分：这是我们在系统手势带内主动不触发
                    Log.w(TAG, "EDGE_EXCLUDE_PRE_PILFER x=$x y=$y rot=${r.snapshotOfConfig().rotation}")
                    PilotShellClient.trace("EDGE_EXCLUDE_PRE_PILFER", "x=$x y=$y")
                }
            }
            HotspotRouter.Route.CANCEL -> {
                TouchStats.edgeCancelObserved++
                Log.w(TAG, "EDGE_CANCEL_OBSERVED x=$x y=$y afterExclude=${r.cancelAfterExclude > 0}")
                PilotShellClient.trace("EDGE_CANCEL_OBSERVED", "x=$x y=$y")
            }
            HotspotRouter.Route.TRIGGER_LEFT -> trigger(true, x, y)
            HotspotRouter.Route.TRIGGER_RIGHT -> trigger(false, x, y)
            HotspotRouter.Route.NONE -> Unit
        }
    }

    private fun trigger(fromLeft: Boolean, x: Float, y: Float) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastTriggerAt < COOLDOWN_MS) return
        lastTriggerAt = now
        // 59 号：方向生效范围。放在冷却之后、回调之前——方向不符时既不呼出也不留统计噪声
        if (!HotspotScope.allows(
                Prefs.hotOrientation(service),
                service.resources.configuration.orientation,
            )
        ) {
            TouchStats.orientationBlocked++
            PilotShellClient.trace(
                "HOT_ORIENT_BLOCKED",
                "mode=${Prefs.hotOrientation(service)} orient=${service.resources.configuration.orientation}",
            )
            return
        }
        TouchStats.bumpDispatchToMain()
        Log.w(TAG, "edge-swipe: TRIGGER fromLeft=$fromLeft cur=($x,$y)")
        main.post { onHotSwipe(fromLeft) }
    }

    // -----------------------------------------------------------------------
    // evdev 降级路径：原始坐标 → 归一化 → 同一路由器
    // -----------------------------------------------------------------------

    private fun handleRawTouch(type: Int, rawX: Float, rawY: Float) {
        if (!available || usingMonitor) return
        val dev = device ?: return
        val cfg = router?.snapshotOfConfig() ?: return
        val p = HotspotHitRules.normalize(
            HotspotHitRules.RawTouchPoint(rawX, rawY),
            dev.xMax.toFloat(), dev.yMax.toFloat(),
            cfg.bounds.width(), cfg.bounds.height(), cfg.rotation,
        )
        val action = when (type) {
            PilotShellService.TYPE_DOWN -> ACTION_DOWN
            PilotShellService.TYPE_MOVE -> ACTION_MOVE
            PilotShellService.TYPE_UP -> ACTION_UP
            else -> return
        }
        // 小窗外侧点击：保持旧语义（先判外侧，再判热区），动作回主线程
        if (action == ACTION_DOWN && service.isWheelShown() &&
            service.wheelOutsidePoint(HotspotHitRules.ScreenPoint(p.x, p.y))
        ) {
            main.post { onOutsideClick() }
            return
        }
        route(action, p.x, p.y, SystemClock.uptimeMillis())
    }
}
