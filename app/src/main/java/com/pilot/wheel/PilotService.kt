package com.pilot.wheel

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Region
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.Display
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.core.graphics.drawable.toBitmap
import com.pilot.wheel.core.ConfigRules
import com.pilot.wheel.core.DisplayGate
import com.pilot.wheel.core.GestureArbiter
import com.pilot.wheel.core.HotspotHitRules
import com.pilot.wheel.core.ReplaySemantics
import com.pilot.wheel.core.TaskBinderBridge
import com.pilot.wheel.core.WindowStateParser
import com.pilot.wheel.core.WindowRect
import com.pilot.wheel.core.navBarBottomPx
import com.pilot.wheel.core.physicalBounds
import com.pilot.wheel.core.sectorRegion
import com.pilot.wheel.core.toRect
import com.pilot.wheel.core.toWindowRect
import com.pilot.wheel.diag.ChannelSnapshot
import com.pilot.wheel.diag.TouchStats
import com.pilot.wheel.notify.DanmakuManager
import com.pilot.wheel.smartlink.SmartLinkCoordinator
import com.pilot.wheel.smartlink.SmartLinkFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 承载所有悬浮界面的无障碍服务。
 *
 * 用无障碍服务而不是普通悬浮窗有两个原因：
 * 1. TYPE_ACCESSIBILITY_OVERLAY 不需要 SYSTEM_ALERT_WINDOW，少一个授权步骤；
 * 2. 无障碍服务被系统杀的优先级远低于普通后台进程。
 *
 * 热区穿透逻辑：热区窗口平时正常消费触摸；
 * 需要穿透的点击/手势先把窗口临时改成 FLAG_NOT_TOUCHABLE，
 * 再用 dispatchGesture 注入到下层，随后恢复标志。
 */
class PilotService : AccessibilityService() {

    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var leftHotspot: View? = null
    private var rightHotspot: View? = null

    /** 55.1：旁路开关为关闭时只落一次 `RAW_OFF`，避免每次重建都写一行 */
    private var rawOffTraced = false
    private var wheel: View? = null
    private var currentFromLeft = true

    /** 旁路热区控制器（Shizuku 读 getevent，只读不消费；默认关，探测失败降级） */
    private var rawHotspot: RawHotspotController? = null

    /**
     * 53 号：注入调度器（专属 inject-dispatch 线程 + 有界门控 + 1200ms 超时兜底）。
     * 懒建：`serviceInfo` 在服务连接后才有效，构造期取能力位会拿到空值。
     */
    private var injectorRef: InjectDispatcher? = null

    private fun injector(): InjectDispatcher =
        injectorRef ?: InjectDispatcher(this).also { injectorRef = it }

    /**
     * 53 号：同 UID 遮挡探针。
     *
     * 注意：热区窗在旁路模式与注入期间都是 NOT_TOUCHABLE，本身不参与命中链，
     * 所以它自己的 alpha 与遮挡无关；真正会吃掉注入事件的是「仍然可触摸且覆盖注入点」
     * 的自家浮层（最典型的是已展开的轮盘）。注册的就是这几个。
     */
    private val occlusion: OverlayOcclusionGuard by lazy {
        OverlayOcclusionGuard(this).apply {
            register("hotspot-left") { leftHotspot }
            register("hotspot-right") { rightHotspot }
            register("wheel") { wheel }
        }
    }

    /**
     * 53 号：周期性 CHANNEL_SNAPSHOT。
     *
     * 一帧同时给出「当前通道 / capabilities / 回放计数」，使两条验收结论可一眼判定：
     *  - 旁路layer 在跑且 replay 不变 → 回放零调用成立；
     *  - caps 不含 0x8 且 incap > 0 → 注入不可用的唯一解释。
     */
    private val snapshotTick = object : Runnable {
        override fun run() {
            val period = Prefs.snapshotPeriodS(this@PilotService)
            if (period <= 0) return
            runCatching {
                val info = serviceInfo
                // 触摸探索状态在 AccessibilityManager 上（不在 AccessibilityServiceInfo 上）；
                // 11-19 事故的判据就是这一位，快照必须带上
                val touchExploration = runCatching {
                    getSystemService(android.view.accessibility.AccessibilityManager::class.java)
                        ?.isTouchExplorationEnabled == true
                }.getOrDefault(false)
                ChannelSnapshot.emit(
                    context = this@PilotService,
                    caps = info?.capabilities ?: 0,
                    touchExploration = touchExploration,
                    guard = occlusion,
                    router = rawHotspot?.routerForStats(),
                )
            }
            main.postDelayed(this, period * 1000L)
        }
    }

    /** 无障碍 onMotionEvent 兜底的手势仲裁器（仅当 Shizuku 两层失败、层=ACCESSIBILITY 时接管） */
    private var a11yArbiter: GestureArbiter? = null
    private var lastA11yTriggerAt = 0L

    /** 场景暂停中：横屏 / 游戏 / 输入框获得焦点时撤掉热区 */
    private var scenePaused = false

    /**
     * 键盘让位中：输入法窗口在屏时把热区整体设成 FLAG_NOT_TOUCHABLE。
     *
     * 这是「打字时点删除/大写键失效」的根因修复：
     * TYPE_ACCESSIBILITY_OVERLAY 的层级高于输入法窗口，热区（默认 48×96dp、离底 56dp）
     * 正好压在键盘中段的大写键与删除键上。DOWN 被热区吃掉后，即使把窗口改成
     * NOT_TOUCHABLE 再 dispatchGesture 注入，注入的触摸也不会送达输入法（IME 不接受
     * 合成事件），于是表现为「点了没反应」。
     *
     * 只能从源头不拦截：键盘在屏期间热区不接收任何触摸，点击直接落到键盘。
     */
    private var imeYielded = false

    /** 热区待太久自动收起，避免全屏 overlay 卡死用户的触摸 */
    private val autoDismiss = Runnable { hideWheel() }

    /** 设置页改了开关就即时重建热区，不用重启服务 */
    private val prefListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            main.post {
                if (scenePaused) return@post
                removeHotspots()
                addHotspots()
                // 开关本身可能把「键盘让位」关掉，重建后强制重算一次
                updateImeYield(force = true)
            }
        }

    /**
     * 锁屏 / 息屏时无障碍热区会盖在锁屏界面上（TYPE_ACCESSIBILITY_OVERLAY 不受 keyguard 限制），
     * 所以必须自己监听屏幕状态，把热区撤掉，避免在锁屏界面上滑出轮盘。
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            main.post {
                // 72 号：亮屏只刷新旁路静默基线，**不再无条件掐断重建**。
                // 68 号的主动重建在本机是「息屏几秒第一滑落空」的根因——短息屏采集没死，
                // 重建反而制造 ~100ms 空窗，而 a11y 兜底本机收不到事件。
                // 真死亡由 device_gone 回传 / pingBinder / 静默盲判兜底。
                if (intent?.action == Intent.ACTION_SCREEN_ON ||
                    intent?.action == Intent.ACTION_USER_PRESENT
                ) {
                    rawHotspot?.onScreenOn()
                }
                updateScenePause(null)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WindowManager::class.java)
        instance = this
        Log.e(TAG, "probe-a11y onServiceConnected entered (SDK ${Build.VERSION.SDK_INT})")
        Prefs.register(this, prefListener)
        runCatching {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(screenReceiver, filter)
            }
        }
        // 无障碍 onMotionEvent 兜底通道注册：放在 addHotspots() 之前，避免 addHotspots 冷启动时
        // BadTokenException 中断后续执行。只开 flagSendMotionEvents（配置 XML 已声明），
        // 不开 flagRequestTouchExplorationMode。
        // 关键：官方文档明确「若任一服务开启 FLAG_REQUEST_TOUCH_EXPLORATION_MODE，onMotionEvent 收不到触摸屏事件」，
        // 且探索模式会锁死用户单击（11-19 事故）。正确路径是 setMotionEventSources 声明要收的来源。
        // setMotionEventSources(int) 是 AccessibilityServiceInfo 的公开方法（API 34+），无需反射。
        // 无条件执行：保证 Shizuku 两层失效时 onMotionEvent 能立即接管（层由 TouchLayerState 仲裁）。
        if (Build.VERSION.SDK_INT >= 34) {
            runCatching {
                serviceInfo.setMotionEventSources(InputDevice.SOURCE_TOUCHSCREEN) // 4098
                Log.e(TAG, "probe-a11y setMotionEventSources(SOURCE_TOUCHSCREEN) ok")
            }.onFailure { Log.e(TAG, "probe-a11y setMotionEventSources failed: ${it.message}") }
        } else {
            Log.e(TAG, "probe-a11y skip setMotionEventSources (SDK ${Build.VERSION.SDK_INT} < 34)")
        }
        updateScenePause(null)
        addHotspots()
        // 服务重连时键盘可能已经在屏（切换无障碍服务、重启服务后返回输入框）
        updateImeYield(force = true)
        // 轻量打开的事件基线（前台包名、桌面包名）在这里建立
        LightOpenWatcher.attach(this)
        // 弹幕（B4）需要窗口管理器，在这里挂；点击弹幕以小窗打开对应应用
        DanmakuManager.attach(this) { pkg ->
            scope.launch {
                AppLauncher.launch(this@PilotService, pkg, Prefs.launchMode(this@PilotService))
            }
        }
        // 侧边栏第一次拉出要一屏图标：提前在 IO 线程把应用列表与图标读进缓存，
        // 否则首次滑出会在主线程卡半秒
        scope.launch(Dispatchers.IO) { runCatching { launchableApps(this@PilotService) } }
        // SmartLink 剪贴板通道：注册剪贴板监听（开关关闭时回调内直接 return，零副作用）
        SmartLinkCoordinator.attach(this)
        // 58 号自检：一次 binder 往返，只落「shell 通道是否可达」，**不落剪贴板内容**。
        // 目的：SmartLink 默认关闭时，也能从 trace.log 判断 shell 读剪贴板这条路通不通。
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val r = runCatching { PilotShellClient.readClipboardText() }.getOrNull()
            val state = when {
                r == null -> "unavailable"
                r.startsWith("ERR:") -> r
                r.isEmpty() -> "ok_empty"
                else -> "ok_len=${r.length}"
            }
            PilotShellClient.trace("CLIP_SELFTEST", state)
        }
        // 53 号：周期快照首帧 3s 后出（等通道探测完），之后按 Prefs.snapshotPeriodS 滚动
        main.removeCallbacks(snapshotTick)
        main.postDelayed(snapshotTick, 3_000L)
        // 53 号 · 第 9 项：Spy（monitorGestureInput）可行性探测。
        // 只探测、不进业务路径：成功仅代表该隐藏接口在本机可创建，不代表事件语义可用。
        // 由 verboseLog 门控，避免默认配置下多发一次 binder 与一个临时 monitor。
        if (Prefs.verboseLog(this)) {
            scope.launch(Dispatchers.IO) {
                val result = runCatching { PilotShellClient.startInputMonitorProbe() }
                    .getOrElse { "threw ${it.javaClass.simpleName}" }
                runCatching { PilotShellClient.stopInputMonitorProbe() }
                TouchStats.spyStatus = if (result.contains("OK")) "ok" else "unavailable"
                PilotShellClient.trace("SPY_PROBE", result.replace('\n', ' ').take(200))
            }
        } else {
            TouchStats.spyStatus = "skipped(verboseLog off)"
        }
    }

    /** 锁屏或息屏：此时不该有任何热区/轮盘 */
    private fun isKeyguardOrScreenOff(): Boolean = runCatching {
        val km = getSystemService(KeyguardManager::class.java)
        val pm = getSystemService(PowerManager::class.java)
        (km?.isKeyguardLocked == true) || (pm?.isInteractive == false)
    }.getOrDefault(false)

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        // SmartLink：仅在开关开启时把事件交给编排（默认关，零副作用）
        if (SmartLinkFeature.isEnabled(this)) {
            SmartLinkCoordinator.onAccessibilityEvent(this, e)
        }
        when (e.eventType) {
            // 触摸事件没有目标包名，喂给场景暂停会把 pkg 判成 null，必须单独处理
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> {
                LightOpenWatcher.onTouch()
                // 兜底：个别 ROM 键盘收起不发窗口变化事件，这时热区会一直停在让位状态。
                // 每次触摸开始时若正在让位就复查一次（只遍历窗口列表，代价很小）。
                if (imeYielded) scheduleImeYieldCheck()
                return
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                LightOpenWatcher.onWindow(
                    e.packageName?.toString(),
                    e.className?.toString(),
                    imeWindowOnScreen = isImeWindowOnScreen(),
                )
                scheduleImeYieldCheck()
            }
            // 键盘弹出/收起最可靠的信号：收起键盘常常不产生 STATE_CHANGED
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                scheduleImeYieldCheck()
                return
            }
            else -> Unit
        }
        updateScenePause(e)
    }

    /**
     * 无障碍 onMotionEvent 兜底通道（Shizuku 两层旁路都失败时接管）。
     *
     * 前提：配置 XML 声明 flagSendMotionEvents，且在 onServiceConnected 里用
     * serviceInfo.setMotionEventSources(SOURCE_TOUCHSCREEN) 声明要收的触摸来源（API 34+）。
     *
     * 关键（官方文档 + 11-19 事故教训）：绝不开 FLAG_REQUEST_TOUCH_EXPLORATION_MODE ——
     * 探索模式会锁死用户单击（单击被系统吞成 hover），且「任一服务开启探索模式」时
     * onMotionEvent 反而收不到触摸屏事件。正确路径是 setMotionEventSources（本服务已用）。
     *
     * 互斥：仅当 [TouchLayerState.current] == ACCESSIBILITY（Shizuku 两层确认失败）时接管；
     * monitorInput / getevent 在跑时这里静默，避免同一边缘内滑被两条通道双重触发。
     * 普通点击在本通道默认透传（窗口恒 NOT_TOUCHABLE），不注入、不消费。
     */
    override fun onMotionEvent(event: MotionEvent) {
        // 62.1：**无条件**先盖时间戳，再走任何 return。
        // 旁路静默时它自己拿不出证据，只有「无障碍看得见触摸」能反证旁路已死；
        // 这一行写在所有前置判断之前，正是为了不被层互斥或开关提前挡掉。
        val nowA11y = SystemClock.elapsedRealtime()
        TouchStats.a11yMotionAt = nowA11y
        if (!Prefs.rawHotspotEnabled(this)) return
        // 维度5：降级开关——关闭后本兜底层完全静默，触摸感知退回纯 Shizuku 旁路，
        // 无障碍服务只保留窗口类型持有与必要节点读取，不再介入任何触摸事件流。
        if (!Prefs.a11yFallbackEnabled(this)) return
        // 71 号：把「本次手势是否由 a11y 接管」的判定统一到 ACTION_DOWN，一旦接管就锁住整段
        // 手势（MOVE/UP 不再重判）。修复 70 号的致命嵌套：
        //   70 号把 a11yTakeoverUntil 的窗口判定嵌在 shouldA11yTakeover() 内部——
        //   DOWN 时旁路未确认(bypassConfirmed=false) → 进、设窗口；但亮屏重建通常 <100ms，
        //   MOVE 到来时旁路已恢复并收到事件把 bypassConfirmed 置 true →
        //   shouldA11yTakeover() 翻 false → MOVE 被挡在外层 → 仲裁器只收到 DOWN 没有 MOVE，
        //   手势被割裂（DOWN 给 a11y、MOVE 给旁路），两路都不触发 → 第一滑落空。
        //   真机铁证：亮屏后每滑只见 `GATE ... src=a11y`，无任何 EDGE/触发日志。
        // 新规则：DOWN 时判定一次，layer 是 ACCESSIBILITY 或旁路未确认/静默即接管；
        // 之后整段手势只看 a11yTakeoverUntil，不再被 bypassConfirmed 翻转打断。
        val layerA11y = TouchLayerState.current == TouchLayerState.Layer.ACCESSIBILITY
        if (event.actionMasked == MotionEvent.ACTION_DOWN &&
            (layerA11y || rawHotspot?.shouldA11yTakeover() == true)
        ) {
            a11yTakeoverUntil = nowA11y + A11Y_TAKEOVER_MS
            // 旁路静默时把触摸作为「旁路已死」实证，触发即时重建（内部 silence<8s 保护，
            // 亮屏重建刚 markChannelUp 时会 return，不会重复重启）。
            rawHotspot?.onA11yEvidence()
            PilotShellClient.trace(
                "BYPASS_TAKEOVER",
                "action=DOWN layer=${TouchLayerState.current} locked=${A11Y_TAKEOVER_MS}ms",
            )
        }
        // 窗口内（含整段 MOVE/UP）无条件交给 a11y 走完；窗口判定在 shouldA11yTakeover 之外。
        // 另：layer 稳定处于 ACCESSIBILITY（旁路长期降级）时也始终交给 a11y，不被窗口过期切断。
        if (nowA11y < a11yTakeoverUntil || layerA11y) {
            handleA11yMotion(event)
        }
    }

    /**
     * 62.1：a11y 接管窗口的截止时刻。
     *
     * 旁路重启只要几十毫秒就会把层切回 getevent，如果逐事件重判，手势后半段会被重新
     * 判给「已恢复的旁路」而丢掉 —— 表现为「滑了但没呼出」。接管必须覆盖整段手势。
     */
    private var a11yTakeoverUntil = 0L

    /** 无障碍兜底：MotionEvent → GestureArbiter → 仅 EDGE_SWIPE_TRIGGER 呼出小窗 */
    private fun handleA11yMotion(event: MotionEvent) {
        val arb = a11yArbiter ?: buildA11yArbiter()
        val x = event.rawX
        val y = event.rawY
        // 维度4：量化核心判定耗时；仅 verbose 日志开启且 >5ms 时打印，不刷屏。
        // 真机验证「onMotionEvent 不成为输入延迟瓶颈」直接看这条日志是否出现。
        val t0 = if (Prefs.verboseLog(this)) System.nanoTime() else 0L
        val result = arb.feed(event.actionMasked, x, y, event.eventTime)
        if (t0 != 0L) {
            val costMs = (System.nanoTime() - t0) / 1_000_000f
            if (costMs > 5f) {
                Log.w(TAG, "a11y-motion slow: %.2fms action=${event.actionMasked}".format(costMs))
            }
        }
        when (result) {
            GestureArbiter.Arbitration.EDGE_SWIPE_TRIGGER -> {
                val down = arb.lastDownPoint
                if (down == null) {
                    Log.w(TAG, "a11y-swipe: trigger but no down point, ignore")
                    return
                }
                if (SystemClock.elapsedRealtime() - lastA11yTriggerAt < A11Y_COOLDOWN_MS) {
                    Log.w(TAG, "a11y-swipe: trigger ignored (cooldown)")
                    return
                }
                val density = resources.displayMetrics.density
                // 55 号：判定域改物理边界；56 号：角区由矩形改四分之一椭圆（详见 DisplayGate）
                val bounds = displayBounds()
                val screenW = bounds.width
                val fromLeft = down.first <= screenW / 2f
                val inZone = DisplayGate.sectorContains(
                    x = down.first,
                    y = down.second,
                    bounds = bounds,
                    insetPx = (Prefs.hotInset(this) * density).toInt(),
                    bottomPx = effectiveBottomPx(),
                    bottomExtentPx = (Prefs.hotWidth(this) * density).toInt(),
                    edgeExtentPx = (Prefs.hotHeight(this) * density).toInt(),
                    left = fromLeft,
                )
                if (!inZone) {
                    Log.w(TAG, "a11y-swipe: down=(${down.first},${down.second}) out of zone, ignore")
                    return
                }
                // 59 号：方向生效范围（与旁路路径同一判据）
                if (!com.pilot.wheel.core.HotspotScope.allows(
                        Prefs.hotOrientation(this), resources.configuration.orientation,
                    )
                ) {
                    Log.w(TAG, "a11y-swipe: orientation blocked")
                    return
                }
                lastA11yTriggerAt = SystemClock.elapsedRealtime()
                Log.w(TAG, "a11y-swipe: EDGE_SWIPE_TRIGGER fromLeft=$fromLeft down=(${down.first},${down.second})")
                showWheel(fromLeft)
            }
            GestureArbiter.Arbitration.CLICK_PASS -> {
                // 普通点击：窗口恒 NOT_TOUCHABLE，本次点击已自然透传给下层，不注入
                Log.w(TAG, "a11y-swipe: CLICK_PASS x=$x y=$y -> 透传（无动作）")
            }
            GestureArbiter.Arbitration.NONE -> Unit
        }
    }

    /** 无障碍兜底的仲裁器几何：与旁路一致（底边带 + 触发后二次角区校验） */
    private fun buildA11yArbiter(): GestureArbiter {
        val density = resources.displayMetrics.density
        // 55 号：与旁路/视觉窗口同一基准（物理边界 + 护栏内的离底距离）
        val bounds = displayBounds()
        val hPx = Prefs.hotHeight(this) * density
        val bottomPx = effectiveBottomPx().toFloat()
        val top = bounds.bottom - bottomPx - hPx
        return GestureArbiter(
            GestureArbiter.Config(
                zoneLeft = bounds.left.toFloat(),
                zoneTop = top,
                zoneRight = bounds.right.toFloat(),
                zoneBottom = bounds.bottom - bottomPx,
                triggerDistancePx = THRESHOLD_DP * density,
                clickTolerancePx = ViewConfiguration.get(this).scaledTouchSlop.toFloat(),
            ),
        ).also { a11yArbiter = it }
    }


    /**
     * 屏幕上是否有活动的输入法窗口。
     *
     * 无障碍能直接读到窗口类型（`TYPE_INPUT_METHOD`），比靠包名猜更权威：
     * 键盘弹出时的窗口事件与「应用内跳转」在事件流里同形，只有窗口类型能区分。
     */
    private fun isImeWindowOnScreen(): Boolean = runCatching {
        // AccessibilityWindowInfo 只有 getBoundsInScreen(Rect)，没有 isVisible()。
        // 键盘收起后这一层通常直接从窗口列表消失，个别 ROM 会留一个 0 高度的空壳，
        // 用 bounds 面积排除掉，否则热区会永远停在让位状态。
        val rect = android.graphics.Rect()
        windows?.any { w ->
            w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD && run {
                w.getBoundsInScreen(rect)
                rect.width() > 0 && rect.height() > 0
            }
        } == true
    }.getOrDefault(false)

    /**
     * TYPE_WINDOWS_CHANGED 在滑通知栏、切分屏时会连发很多条，
     * 120ms 合并一次即可，避免每条事件都去遍历窗口列表 + updateViewLayout。
     */
    private val imeYieldCheck = Runnable { updateImeYield() }

    private fun scheduleImeYieldCheck() {
        main.removeCallbacks(imeYieldCheck)
        main.postDelayed(imeYieldCheck, IME_YIELD_DEBOUNCE_MS)
    }

    /**
     * 按「键盘是否在屏」同步热区的触摸能力。
     *
     * 用窗口类型（TYPE_INPUT_METHOD）判定，不用输入框焦点：微信这类自绘输入框
     * 的 FOCUS_INPUT 节点经常取不到，而键盘窗口类型是系统给的，ROM 改不掉。
     */
    private fun updateImeYield(force: Boolean = false) {
        val shouldYield = Prefs.pauseKeyboard(this) && isImeWindowOnScreen()
        if (!force && shouldYield == imeYielded) return
        imeYielded = shouldYield
        // 正在穿透注入时先收尾，否则恢复标志会把让位状态覆盖掉
        if (throughInFlight) endPassThrough()
        listOfNotNull(leftHotspot, rightHotspot).forEach { applyHotspotYield(it, shouldYield) }
        PilotLog.d(this, TAG, "hotspot ime yield=$shouldYield scenePaused=$scenePaused")
    }

    private fun applyHotspotYield(view: View, yielded: Boolean) {
        (view as? CornerHotspot)?.setImeYielded(yielded)
        val lp = view.layoutParams as? WindowManager.LayoutParams ?: return
        lp.flags = if (yielded) {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        runCatching { wm.updateViewLayout(view, lp) }
            .onFailure { Log.w(TAG, "ime yield flag update failed", it) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (wheel == null) applyScenePause(computeScenePause(null))
        updateImeYield(force = true)
    }

    override fun onInterrupt() = Unit

    // ---------------------------------------------------------------------------
    // 场景暂停：横屏 / 游戏时撤掉热区（已经打开的窗口不受影响，离开场景自动恢复）。
    // 键盘走另一条更轻的路：见 [updateImeYield]，只把热区设成不可触摸，不重建窗口。
    // ---------------------------------------------------------------------------

    private fun computeScenePause(event: AccessibilityEvent?): Boolean {
        val pkg = event?.packageName?.toString()
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val game = Prefs.pauseGame(this) && pkg != null && isGamePackage(pkg)
        // 键盘不再走「整块撤掉热区」：撤掉要 remove + add 两个窗口，键盘频繁弹收时会闪，
        // 而且 FOCUS_INPUT 节点在自绘输入框上经常取不到，判据本身不可靠。
        // 现在由 [updateImeYield] 按窗口类型把热区设成不可触摸，键盘收起即恢复。
        // 锁屏 / 息屏优先级最高：这两个场景下热区必须不存在
        val locked = isKeyguardOrScreenOff()
        val paused = locked || (Prefs.pauseLandscape(this) && landscape) || game
        if (paused && !scenePaused) {
            PilotLog.d(
                this, TAG,
                "scene pause: landscape=$landscape game=$game pkg=$pkg",
            )
        }
        return paused
    }

    private fun updateScenePause(event: AccessibilityEvent?) {
        // 锁屏/息屏时连已经打开的轮盘一起收掉；其它场景不打断已打开的窗口
        if (isKeyguardOrScreenOff() && wheel != null) hideWheel()
        if (wheel != null) return
        applyScenePause(computeScenePause(event))
    }

    private fun applyScenePause(paused: Boolean) {
        if (paused == scenePaused) return
        scenePaused = paused
        if (paused) {
            removeHotspots()
        } else {
            PilotLog.d(this, TAG, "scene resume: rebuild hotspots")
            addHotspots()
        }
    }

    /** 游戏包名启发式判定；可按机型在源码里增补标记 */
    private fun isGamePackage(pkg: String): Boolean {
        val markers = arrayOf(
            "tmgp", "mihoyo", "hoyoverse", "hypergryph", "kurogames",
            "bilibili.game", "gameloft", "supercell", "riotgames", "netease", ".game",
        )
        return markers.any { pkg.contains(it, ignoreCase = true) }
    }

    override fun onDestroy() {
        instance = null
        runCatching { unregisterReceiver(screenReceiver) }
        Prefs.unregister(this, prefListener)
        DanmakuManager.detach()
        main.removeCallbacks(restoreThrough)
        main.removeCallbacks(guardThrough)
        main.removeCallbacks(pendingReplay)
        main.removeCallbacks(imeYieldCheck)
        // 53 号：停快照定时器与注入线程，避免服务重启后线程堆积
        main.removeCallbacks(snapshotTick)
        injectorRef?.shutdown()
        injectorRef = null
        throughInFlight = false
        pendingTap = null
        removeHotspots()
        rawHotspot?.stop()
        rawHotspot = null
        hideWheel()
        SmartLinkCoordinator.shutdown()
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------
    // 热区：上滑/把手呼出轮盘，普通点击按需穿透
    // ---------------------------------------------------------------------------

    /**
     * 对齐参考实现的 OverlayEngine.navigationBarInsetPx：
     * 触发区离底距离 = max(56dp, 导航栏高度 + 8dp, 边缘内缩)。
     * 本轮按图四把触发区整体上移：基准 40dp → 56dp，使红色预览区
     * 覆盖到参考实现的角落把手（把手在窗口内距顶 80dp 处）。
     */
    private fun navigationBarInsetPx(): Int = runCatching {
        wm.currentWindowMetrics.windowInsets
            .getInsetsIgnoringVisibility(android.view.WindowInsets.Type.navigationBars())
            .bottom
    }.getOrDefault((8 * resources.displayMetrics.density).toInt())

    private fun cornerBottomPx(
        edgeInsetPx: Int,
        bottomDp: Float = Prefs.hotBottom(this),
    ): Int = com.pilot.wheel.core.ConfigRules.hotspotBottomPx(
        bottomDp = bottomDp,
        density = resources.displayMetrics.density,
        navBarPx = navigationBarInsetPx(),
        edgeInsetPx = edgeInsetPx,
    )

    private fun addHotspots() {
        // 两边分别创建、分别兜异常。之前两个 addView 放在同一个 runCatching 里，
        // 右侧失败会连左侧一起回滚，右侧就永远补不上。
        removeHotspots()
        ensureRawHotspot()
        addHotspotViews()
    }

    /**
     * 旁路热区感知：视觉层常驻 FLAG_NOT_TOUCHABLE，绝不拦截下层输入。
     * 触摸感知完全由旁路完成（monitorInput 优先 → getevent 降级 → 无障碍 onMotionEvent 兜底），
     * 窗口从始至终不参与触摸分发。三级全失效时由 [onBypassBlocked] 关掉开关，绝不退回可触摸的旧拦截路径。
     */
    private fun ensureRawHotspot() {
        if (!Prefs.rawHotspotEnabled(this)) {
            rawHotspot?.stop()
            // 55.1：把「开关是关着的」变成可读事实。否则快照里的 layer=NONE/evdev=none
            // 会同时对应「用户关掉了」与「通道挂了」两种完全不同的原因，无法区分。
            if (!rawOffTraced) {
                rawOffTraced = true
                main.postDelayed({
                    PilotShellClient.trace("RAW_OFF", "pref=0 旁路触摸开关为关闭（非通道故障）")
                }, 3_000L)
            }
            return
        }
        rawOffTraced = false
        if (rawHotspot == null) {
            rawHotspot = RawHotspotController(
                service = this,
                onHotSwipe = { fromLeft -> showWheel(fromLeft) },
                onOutsideClick = { hideWheel() },
                // 全部通道不可用 → 关闭开关并提示（窗口不再切回可触摸的旧路径）
                onBlocked = { main.post { onBypassBlocked() } },
            )
        }
        rawHotspot?.start()
    }

    /** 旁路触摸全部通道不可用：关掉开关 + 提示，避免留下「开了却不工作」的开关 */
    private fun onBypassBlocked() {
        if (Prefs.rawHotspotEnabled(this)) {
            Prefs.setRawHotspotEnabled(this, false)
            Toast.makeText(
                this,
                "旁路触摸不可用：Shizuku 与无障碍触摸探测均失败，已自动关闭",
                Toast.LENGTH_LONG,
            ).show()
        }
        // 开关落盘后 prefListener 会重建热区（切回可触摸旧路径），无需在此重复重建
    }

    private fun addHotspotViews() {
        val density = resources.displayMetrics.density
        val w = (Prefs.hotWidth(this) * density).toInt()
        val h = (Prefs.hotHeight(this) * density).toInt()
        val margin = (Prefs.hotInset(this) * density).toInt()
        val bottom = cornerBottomPx(margin)
        val handle = Prefs.cornerHandle(this)
        val pass = Prefs.hotspotPassThrough(this)
        // 热区视觉层的可触摸性只由「旁路触摸采集」开关决定（不再是「旁路是否已就绪」）：
        //  - 开关开启 → 恒 NOT_TOUCHABLE，普通点击完全穿透，触摸感知全由旁路完成
        //    （monitorInput → getevent → 无障碍 onMotionEvent），窗口从不参与触摸分发；
        //  - 开关关闭 → 可触摸，回退到 CornerHotspot 旧无障碍拦截路径。
        // 旧实现用 `rawHotspot?.isAvailable()` 判断：旁路失效时窗口切回可触摸，导致
        // 「旁路失效且未回退无障碍」；现在旁路失效落到 onMotionEvent，窗口保持 NOT_TOUCHABLE，
        // 只有三级全失效时 onBypassBlocked 才会把开关关掉（从而回到可触摸旧路径）。
        val touchThrough = Prefs.rawHotspotEnabled(this)

        fun build(fromLeft: Boolean): CornerHotspot = CornerHotspot(
            context = this,
            left = fromLeft,
            thresholdDp = THRESHOLD_DP,
            showHandle = handle,
            passThrough = pass,
            onSwipe = { showWheel(fromLeft) },
            // 40 号：上滑阈值一到就建轮盘进跟手模式；返回 false（拉黑/无应用/竖列样式）
            // 时热区自动退回旧路径，后续 MOVE/UP 不再喂给轮盘
            onSwipeActivate = { startWheelFollow(fromLeft) },
            onSwipeMove = { progress, x, y -> wheelFollowMove(progress, x, y) },
            onSwipeEnd = { x, y -> wheelFollowEnd(x, y) },
            onSwipeCancel = { wheelFollowCancel() },
            onTapThrough = { x, y ->
                if (Prefs.hotspotPassThrough(this@PilotService)) {
                    performTapThrough(fromLeft, x, y)
                }
            },
            // 划动一律透传：点击是否透传由「点击穿透」开关决定，但划动本来就是应用的手势，
            // 只因为落在热区里就吞掉（微信视频上滑、阅读器滚动）是纯粹的掉手感。
            onGesturePassThrough = { path, dur -> performGesturePassThrough(fromLeft, path, dur) },
        )

        if (Prefs.leftEnabled(this)) {
            runCatching {
                val view = build(fromLeft = true)
                view.setImeYielded(imeYielded)
                wm.addView(
                    view,
                    overlayParams(
                        Gravity.START or Gravity.BOTTOM, margin, bottom, w, h,
                        touchThrough = touchThrough,
                    ),
                )
                leftHotspot = view
            }.onFailure { Log.e(TAG, "left hotspot failed", it) }
        }

        if (Prefs.rightEnabled(this)) {
            runCatching {
                val view = build(fromLeft = false)
                view.setImeYielded(imeYielded)
                wm.addView(
                    view,
                    overlayParams(
                        Gravity.END or Gravity.BOTTOM, margin, bottom, w, h,
                        touchThrough = touchThrough,
                    ),
                )
                rightHotspot = view
            }.onFailure { Log.e(TAG, "right hotspot failed", it) }
        }

        Log.d(TAG, "hotspots: left=${leftHotspot != null} right=${rightHotspot != null} pass=$pass")
        // 55 号：几何自检落盘（dTop 应为 0；为 0 时仍保留 cur/max/dm 三列供比对）
        traceGateA11y()
        // 维度1：热区重建后同步区域 passthrough（默认探索模式关 → 空 Region，零行为变化）
        applyPassthroughRegions()
    }

    private fun removeHotspots() {
        listOfNotNull(leftHotspot, rightHotspot).forEach {
            runCatching { wm.removeView(it) }
        }
        leftHotspot = null
        rightHotspot = null
    }

    // ---------------------------------------------------------------------------
    // 穿透核心：临时 NOT_TOUCHABLE → 注入手势 → 恢复 flags
    // 数值同步自同类小窗应用：注入延迟 26ms、点击恢复 45ms、看门狗 220ms、
    // 重入排队 220ms、过期丢弃 400ms。
    // ---------------------------------------------------------------------------

    private data class TapReq(
        val fromLeft: Boolean,
        val x: Float,
        val y: Float,
        val at: Long,
    )

    private var throughInFlight = false
    private var throughStartedAt = 0L
    private var throughOldFlags = 0
    private var throughView: View? = null
    private var throughParams: WindowManager.LayoutParams? = null
    private var pendingTap: TapReq? = null

    private val restoreThrough = Runnable { endPassThrough() }
    private val guardThrough = Runnable {
        if (throughInFlight) {
            Log.w(TAG, "pass-through guard timeout, restoring touchable")
            endPassThrough()
        }
    }
    private val pendingReplay = Runnable {
        pendingTap?.let { performTapThrough(it.fromLeft, it.x, it.y) }
    }

    private fun beginThrough(view: View, lp: WindowManager.LayoutParams): Boolean {
        throughInFlight = true
        throughStartedAt = SystemClock.elapsedRealtime()
        throughView = view
        throughParams = lp
        throughOldFlags = lp.flags
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return try {
            wm.updateViewLayout(view, lp)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "pass-through flag update failed", t)
            endPassThrough()
            false
        }
    }

    /** 普通点击穿透：注入一次短按，45ms 后恢复热区可触摸 */
    private fun performTapThrough(fromLeft: Boolean, x: Float, y: Float) {
        val now = SystemClock.elapsedRealtime()
        if (throughInFlight) {
            if (now - throughStartedAt >= TAP_GUARD_MAX_MS) {
                endPassThrough()
            } else {
                // 220ms 内重入 → 串行排队，避免两个人同时改同一个 LayoutParams
                pendingTap = TapReq(fromLeft, x, y, now)
                return
            }
        }

        val view = (if (fromLeft) leftHotspot else rightHotspot) ?: return
        val lp = view.layoutParams as? WindowManager.LayoutParams ?: return
        if (!beginThrough(view, lp)) return

        main.removeCallbacks(guardThrough)
        main.postDelayed(guardThrough, TAP_GUARD_MAX_MS)
        main.postDelayed({
            if (!throughInFlight || view.layoutParams !== lp) {
                endPassThrough()
                return@postDelayed
            }
            // 53 号：注入点被自家可触摸浮层覆盖时，回放注定无效 → 记 OVERLAY_ALPHA_RISK 并放行
            val risk = occlusion.riskAt(x, y)
            if (risk.blocked) {
                occlusion.reportIfRisk("tap", risk)
                endPassThrough()
                return@postDelayed
            }
            // 53 号：flags 改动在 WMS 侧异步生效，注入前让出一帧，避免手势打回自己的窗口
            runAfterInputWindowSync(view) {
                if (!throughInFlight || view.layoutParams !== lp) {
                    endPassThrough()
                    return@runAfterInputWindowSync
                }
                if (!dispatchTap(x, y, TAP_RESTORE_MS.toInt())) {
                    endPassThrough()
                    return@runAfterInputWindowSync
                }
                main.removeCallbacks(restoreThrough)
                main.postDelayed(restoreThrough, TAP_RESTORE_MS)
            }
        }, INJECT_DELAY_MS)
    }

    /**
     * 53 号：注入前等一帧。
     *
     * `updateViewLayout` 只保证 Java 侧调用成功，WMS 侧 `InputWindowInfo` 的 flag 更新
     * 要等下一次 traversal 才进 hit-test。固定 26ms 延时在 120Hz 与低端机上都不保证，
     * 注入过早会被自己的窗口接走 —— 表现就是 `completed=true` 但下层毫无响应。
     * 这里拿一帧（`postOnAnimation`）后再注入，并留 80ms 兜底，
     * 保证窗口已 detach / 无动画帧时不会永久挂住。
     */
    private fun runAfterInputWindowSync(view: View, block: () -> Unit) {
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val fire = Runnable { if (done.compareAndSet(false, true)) block() }
        val fallback = Runnable { fire.run() }
        main.postDelayed(fallback, 80L)
        view.postOnAnimation {
            main.removeCallbacks(fallback)
            fire.run()
        }
    }

    /** 斜划/多指等未达呼出阈值的手势：按采样轨迹整段注入给下层，结束后恢复 */
    private fun performGesturePassThrough(
        fromLeft: Boolean,
        path: List<Pair<Float, Float>>,
        durationMs: Long,
    ) {
        if (throughInFlight) {
            Log.d(TAG, "gesture pass-through skipped, another in flight")
            return
        }
        if (path.size < 2) return

        // 53 号 · 甩动语义边界：GestureDescription 是相对时间轴，原始 downTime/eventTime
        // 进不了注入事件。快甩（惯性/甩动）回放必然失真，与其伪装成正常触摸，
        // 不如显式放弃并放行 —— 阈值可配，<=0 表示关闭该规则。
        val velocity = ReplaySemantics.displacementVelocity(
            path.first().first, path.first().second,
            path.last().first, path.last().second,
            durationMs,
        )
        val velocityLimit = Prefs.replayVelocityMaxPxS(this)
        if (ReplaySemantics.shouldAbandon(velocity, velocityLimit)) {
            TouchStats.replaySemanticLost++
            val detail = "v=%.0f thr=%.0f dur=%d samples=%d".format(velocity, velocityLimit, durationMs, path.size)
            Log.w(TAG, "REPLAY_SEMANTIC_LOST $detail")
            PilotShellClient.trace("REPLAY_SEMANTIC_LOST", detail)
            return
        }
        // 53 号：注入点被自家可触摸浮层覆盖时不注入（否则事件被自家窗口接走）
        val risk = occlusion.riskAt(path.first().first, path.first().second)
        if (risk.blocked) {
            occlusion.reportIfRisk("swipe", risk)
            return
        }

        val view = (if (fromLeft) leftHotspot else rightHotspot) ?: return
        val lp = view.layoutParams as? WindowManager.LayoutParams ?: return
        if (!beginThrough(view, lp)) return

        val duration = durationMs.coerceIn(80L, 800L)
        // 注入期间让出系统手势排除区，下层才能正常收到边缘手势
        (view as? CornerHotspot)?.setExclusionSuspended(true)
        main.removeCallbacks(guardThrough)
        main.postDelayed(guardThrough, duration + 350L)
        main.postDelayed({
            if (!throughInFlight || view.layoutParams !== lp) {
                endPassThrough()
                return@postDelayed
            }
            // 53 号：等 input window 同步后再注入（固定延时不足以保证 hit-test 已更新）
            runAfterInputWindowSync(view) {
                if (!throughInFlight || view.layoutParams !== lp) {
                    endPassThrough()
                    return@runAfterInputWindowSync
                }
                val dispatched = dispatchSwipe(path, duration) {
                    endPassThrough()
                }
                if (!dispatched) endPassThrough()
            }
        }, INJECT_DELAY_MS)
    }

    private fun endPassThrough() {
        main.removeCallbacks(restoreThrough)
        main.removeCallbacks(guardThrough)
        val view = throughView
        val lp = throughParams
        throughInFlight = false
        throughView = null
        throughParams = null
        if (view == null || lp == null) return
        // 手势排除区随注入结束恢复
        (view as? CornerHotspot)?.setExclusionSuspended(false)
        // 窗口可能已经被配置变更重建，绝不能拿旧 lp 去改新窗口
        if (view.layoutParams !== lp) return

        lp.flags = if (imeYielded) {
            throughOldFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            throughOldFlags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        var restored = false
        if (view.isAttachedToWindow) {
            restored = try {
                wm.updateViewLayout(view, lp)
                true
            } catch (t: Throwable) {
                Log.e(TAG, "restore hotspot flags failed", t)
                false
            }
        }
        if (!restored) {
            Log.w(TAG, "hotspot rebuild after pass-through")
            addHotspots()
        }

        pendingTap?.let { req ->
            pendingTap = null
            if (SystemClock.elapsedRealtime() - req.at <= TAP_QUEUE_EXPIRE_MS) {
                main.removeCallbacks(pendingReplay)
                main.postDelayed(pendingReplay, 18L)
            }
        }
    }

    /**
     * 注入回传必须显性化：dispatchGesture 在服务缺少 CAPABILITY_CAN_PERFORM_GESTURES
     * 时直接返回 false，旧代码用 getOrDefault(false) 静默吞掉，表现为「热区覆盖区
     * 点不动、滑不动」而日志一片安静，排查成本极高。这里记日志 + 一次性 Toast，
     * 成功一次后重置告警位。
     *
     * 维度5 语义澄清：本返回值「仅」表示 dispatchGesture 请求是否被系统接受
     * （能力/参数层面），绝不能当作「事件真的送达下层」的证据——平台上存在
     * completed:true 但事件被静默丢弃的情况。调用方只用 false 做失败清理
     * （立即 endPassThrough 恢复窗口 flags）；真正的完成信号是 GestureResultCallback
     * 的 onCompleted/onCancelled，不得靠返回值推断注入已生效。
     */
    private var injectWarned = false

    /**
     * 注入健康度：dispatchGesture 返回 true 只代表请求被接受，不代表事件送达下层
     * （平台存在 completed:true 但被静默丢弃的情况；Android 16+ 对非工具类调用还有拦截风险）。
     * 这里记录连续 onCancelled 次数，达到阈值就落盘告警——荣耀 logcat 看不到第三方日志，
     * 只有 /data/local/tmp 落盘能作为真机判定依据。仅做可观测，不改变注入/窗口行为。
     */
    @Volatile
    private var injectCancelledStreak = 0

    private fun noteInject(ok: Boolean): Boolean {
        if (ok) {
            injectWarned = false
            return true
        }
        Log.w(TAG, "dispatchGesture rejected: lacks CAPABILITY_CAN_PERFORM_GESTURES")
        injectCancelledStreak++
        PilotShellClient.trace("INJECT", "rejected streak=$injectCancelledStreak")
        if (!injectWarned) {
            injectWarned = true
            runCatching {
                Toast.makeText(this, "热区穿透注入不可用：请重新开启无障碍服务", Toast.LENGTH_LONG).show()
            }
        }
        return false
    }

    /**
     * 注入完成回调已迁入 [InjectDispatcher]（53 号）：那里持有专属线程与超时定时器，
     * 能同时判定 onCompleted / onCancelled / 超时三种收尾，并统一落盘 `INJECT` 事件。
     * 本类不再各自持回调，避免「多个入口各有一套门控」的旧问题。
     */

    private fun dispatchTap(x: Float, y: Float, durationMs: Int): Boolean {
        val dur = durationMs.coerceIn(20, 300).toLong()
        // 53 号：改走 inject-dispatch 专属线程（原传 main 会把回调串回主线程队列），
        // 并发门控 + 超时兜底统一由 InjectDispatcher 负责。
        val accepted = injector().submit(
            "tap",
            build = {
                val path = Path().apply { moveTo(x, y) }
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0L, dur))
                    .build()
            },
            done = { },
        )
        return noteInject(accepted)
    }

    private fun dispatchSwipe(
        points: List<Pair<Float, Float>>,
        durationMs: Long,
        onDone: Runnable,
    ): Boolean {
        if (points.size < 2) return false
        val dur = durationMs.coerceIn(80L, 1200L)
        // 53 号：专属线程 + 门控；完成回调在 inject-dispatch 线程，故回主线程再跑 onDone
        val accepted = injector().submit(
            "swipe",
            build = {
                val path = Path().apply {
                    moveTo(points[0].first, points[0].second)
                    // 按采样点逐段连线，还原曲线；dip 采样已抑制抖动
                    for (i in 1 until points.size) lineTo(points[i].first, points[i].second)
                }
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0L, dur))
                    .build()
            },
            done = { main.post(onDone) },
        )
        return noteInject(accepted)
    }

    // ---------------------------------------------------------------------------
    // 轮盘
    // ---------------------------------------------------------------------------

    /** 40 号：唤出跟手进行中——轮盘进度由手指驱动，自动收起计时器暂停 */
    private var wheelFollowing = false

    /** 40 号：阈值触发 → 建轮盘进跟手模式；false = 未接管（走旧唤出路径） */
    private fun startWheelFollow(fromLeft: Boolean): Boolean {
        val shown = showWheel(fromLeft, follow = true)
        wheelFollowing = shown && wheel is WheelOverlay
        if (wheelFollowing) {
            (wheel as WheelOverlay).beginFollow(0f)
        }
        return wheelFollowing
    }

    private fun wheelFollowMove(progress: Float, x: Float, y: Float) {
        if (!wheelFollowing) return
        (wheel as? WheelOverlay)?.updateFollow(progress, x, y)
    }

    private fun wheelFollowEnd(x: Float, y: Float) {
        if (!wheelFollowing) return
        wheelFollowing = false
        val view = wheel as? WheelOverlay ?: return
        val handled = view.endFollow(x, y)
        if (!handled) {
            // 轮盘原地展开保持全开，此时才排自动收起；
            // 启动/面板路径由 onPick/onMore 内部 hideWheel，不排
            main.removeCallbacks(autoDismiss)
            main.postDelayed(autoDismiss, AUTO_DISMISS_MS)
        }
    }

    private fun wheelFollowCancel() {
        if (!wheelFollowing) return
        wheelFollowing = false
        (wheel as? WheelOverlay)?.cancelFollow()
        hideWheel()
    }

    private fun showWheel(fromLeft: Boolean, follow: Boolean = false): Boolean {
        if (wheel != null) return false
        // 黑名单：当前前台应用被拉黑时不呼出（需要「使用情况访问」权限，没给就查不到、不拦）
        val fg = if (Prefs.blacklist(this).isEmpty()) null else ForegroundCheck.currentForeground(this, 8000L)
        if (Prefs.isBlacklisted(this, fg)) {
            PilotLog.d(this, TAG, "showWheel blocked by blacklist: $fg")
            return false
        }
        currentFromLeft = fromLeft
        PilotLog.d(this, TAG, "showWheel fromLeft=$fromLeft")

        val apps = loadApps()
        if (apps.isEmpty()) {
            Log.w(TAG, "no apps configured, open the app and pick some")
            return false
        }

        val view: View = if (Prefs.quickMenuStyle(this) == Prefs.MENU_STYLE_VERTICAL) {
            // C2：竖列样式 —— 同一份条目、同一套回调，只换排布与命中判定
            VerticalMenuView(
                context = this,
                items = apps,
                fromLeft = fromLeft,
                iconDp = Prefs.iconDp(this),
                doubleRow = Prefs.doubleRow(this),
                hapticEnabled = Prefs.haptic(this),
                dismissOnOutside = Prefs.dismissOutside(this),
                onPick = ::onPick,
                onDismiss = ::hideWheel,
                onMore = ::openMorePanel,
            )
        } else {
            WheelOverlay(
                context = this,
                items = apps,
                fromLeft = fromLeft,
                radiusDp = Prefs.radiusDp(this),
                iconDp = Prefs.iconDp(this),
                angleDeg = Prefs.angle(this),
                doubleRow = Prefs.doubleRow(this),
                hapticEnabled = Prefs.haptic(this),
                showHint = Prefs.showWheelHint(this),
                dismissOnOutside = Prefs.dismissOutside(this),
                onPick = ::onPick,
                onDismiss = ::hideWheel,
                onMore = ::openMorePanel,
                onRemove = { pkg ->
                    val apps = Prefs.load(this).toMutableList()
                    if (apps.remove(pkg)) {
                        Prefs.save(this, apps)
                        main.post {
                            hideWheel()
                            showWheel(currentFromLeft)
                        }
                    }
                },
            )
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 62 号：删除 FLAG_BLUR_BEHIND + blurBehindRadius(30dp)。
            //
            // 轮盘是 MATCH_PARENT 全屏覆盖窗，`FLAG_BLUR_BEHIND` 模糊的是**整块屏幕背后**，
            // 30dp（本机 density 3.24 → 约 97px）几乎是能设的上限：滑出轮盘后背景被糊成
            // 一片乳白，用户完全看不出下层是什么应用（实测截图即此现象）。
            // 轮盘本体只承担图标绘制，`onDraw` 里 scrim 的 alpha 已经是 0（「整屏保持背景原样」），
            // 两层叠加的唯一效果就是把背景抹掉，故整块移除；如需轻微磨砂，把常量改小重加即可。
            // 通知面板（NotificationPanelOverlay）是独立小窗，模糊语义不同，保持不动。
        }

        wheel = view
        runCatching {
            wm.addView(view, params)
            // 40 号：跟手模式不排自动收起（拖动可能超过 15s），松手后在 wheelFollowEnd 排；
            // 非跟手（含竖列样式 / 跟手被拉黑拦截后的旧路径）照旧
            if (!(follow && view is WheelOverlay)) {
                main.removeCallbacks(autoDismiss)
                main.postDelayed(autoDismiss, AUTO_DISMISS_MS)
            }
        }.onFailure {
            wheel = null
            Log.e(TAG, "show wheel failed", it)
        }
        return wheel != null
    }

    private fun hideWheel() {
        main.removeCallbacks(autoDismiss)
        val view = wheel ?: return
        wheel = null
        runCatching { wm.removeView(view) }
    }

    /**
     * 旁路控制器用：轮盘是否正在显示。
     */
    fun isWheelShown(): Boolean = wheel != null

    /**
     * 旁路控制器用：旁路触点是否落在「轮盘图标区之外」。
     *
     * 当前轮盘是 MATCH_PARENT 全屏透明 overlay，图标只占一角弧形区域，
     * 「小窗矩形」语义尚未拆分 —— 本方法在现阶段恒返回 false，「点击窗外关闭」
     * 仍由轮盘自身的 ACTION_OUTSIDE / 空白点击逻辑处理（不新增拦截）。
     * 保留此接口是为了将来把轮盘图标区拆成独立小窗后，由旁路数据驱动
     * 「关闭但不拦截下层」的 Flyme 语义。
     */
    fun wheelOutsidePoint(p: com.pilot.wheel.core.HotspotHitRules.ScreenPoint): Boolean = false

    /** 旁路控制器用：当前小窗矩形（屏幕坐标）；未拆分独立小窗时返回 null */
    fun currentWheelRect(): com.pilot.wheel.core.WindowRect? = null

    /** 「更多」面板：轮盘与竖列菜单共用同一个入口 */
    private fun openMorePanel() {
        hideWheel()
        runCatching {
            startActivity(
                Intent(this, AppSheetActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** 同一图标的连点去重：启动链本身 0.3–1s，期间无反馈时用户会再点一次 */
    private val lastPickAt = java.util.concurrent.atomic.AtomicLong(0L)

    @Volatile
    private var lastPickPkg: String? = null

    private fun onPick(app: PilotApp) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (app.pkg == lastPickPkg && now - lastPickAt.get() < PICK_DEDUP_MS) {
            Log.d(TAG, "pick ${app.pkg} ignored: duplicate within ${PICK_DEDUP_MS}ms")
            return
        }
        lastPickPkg = app.pkg
        lastPickAt.set(now)
        hideWheel()
        // 快捷工具：直接调系统能力，不经过 AppLauncher
        app.toolId?.let { toolId ->
            scope.launch {
                val message = QuickTools.run(this@PilotService, toolId)
                PilotLog.d(this@PilotService, TAG, "tool ${app.pkg} -> $message")
                if (message.startsWith(QuickTools.NEED_PICK)) {
                    // 自动匹配失败：直接把「更多」面板拉起来并弹出入口选择，
                    // 不用让用户自己去找这张卡片
                    runCatching {
                        startActivity(
                            Intent(this@PilotService, AppSheetActivity::class.java)
                                .putExtra(AppSheetActivity.EXTRA_PICK_TOOL, toolId)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                    Toast.makeText(
                        this@PilotService, "选一次这个工具的入口，下次直达", Toast.LENGTH_SHORT,
                    ).show()
                    return@launch
                }
                if (message.isNotBlank()) {
                    Toast.makeText(this@PilotService, message, Toast.LENGTH_SHORT).show()
                }
            }
            return
        }
        if (app.pkg == PilotNotificationListener.MSG_PKG) {
            if (PilotNotificationListener.open()) {
                // 点进去即从轮盘移除；下次来微信通知会重新置顶出现
                PilotNotificationListener.clearMessage()
                PilotLog.d(this, TAG, "wechat message entry consumed, removed from wheel")
            } else {
                main.post {
                    Toast.makeText(
                        this, "打不开微信聊天，请直接点系统通知", Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            return
        }
        Prefs.noteRecent(this, app.pkg)
        val mode = Prefs.launchMode(this)
        // 41B：双窗共存 —— 目标应用已有 freeform task 就 refocus（不新开）；
        // 旧的「阻止二次打开」去重只保留启动中防抖（lastPickAt，上方）
        if (mode != Prefs.LAUNCH_FULL) {
            val existing = FreeformWindowRegistry.findByPkg(app.pkg)
            if (existing != null) {
                scope.launch {
                    // 68 号修复：registry 里的 task 可能已死（用户关掉小窗 / 应用被杀），
                    // 而 refocusTask 对死 task 会静默「成功」（setFocusedTask 找不到 task
                    // 时静默返回），导致「小窗开过一次之后就再也打不开，只有全屏能打开」。
                    if (!refocusOrReopen(app, existing)) launchPickedApp(app)
                }
                return
            }
        }
        scope.launch { launchPickedApp(app) }
    }

    /**
     * 68 号修复：refocus 一个已登记的 freeform task，失败 / 已死时清掉登记。
     *
     * 返回 true = 已成功 refocus（不必重开）；false = task 已死或 refocus 失败，
     * 已清掉登记，调用方应走 [launchPickedApp] 重新启动。
     */
    private suspend fun refocusOrReopen(
        app: PilotApp,
        existing: FreeformWindowRegistry.Entry,
    ): Boolean {
        if (!taskStillAlive(app.pkg, existing.taskId)) {
            PilotLog.d(
                this, TAG,
                "refocus skip: task=${existing.taskId} pkg=${app.pkg} already gone, reopen",
            )
            FreeformWindowRegistry.remove(existing.taskId)
            return false
        }
        val ok = PilotShellClient.refocus(existing.taskId)
        PilotLog.d(this, TAG, "refocus existing task=${existing.taskId} pkg=${app.pkg} ok=$ok")
        if (!ok) FreeformWindowRegistry.remove(existing.taskId)
        return ok
    }

    /** 该 pkg 的现存 task 里还能查到登记的 taskId 就算还活着；读不到任务表时保守按「还活着」走 refocus */
    private suspend fun taskStillAlive(pkg: String, taskId: Int): Boolean {
        val dump = TaskBinderBridge.dumpTasks(this)
            ?: AppLauncher.shellStdout("dumpsys activity activities", 5_000L)
            ?: return true
        val id = taskId.toString()
        return WindowStateParser.tasks(dump, pkg).any { it.id == id }
    }

    /** 统一的「从轮盘启动应用」：启动 → 登记 registry → 失败提示 */
    private suspend fun launchPickedApp(app: PilotApp) {
        val mode = Prefs.launchMode(this)
        val result = AppLauncher.launch(this, app.pkg, mode)
        Log.d(TAG, "launch ${app.label} mode=$mode -> $result")
        PilotLog.d(this, TAG, "launch ${app.pkg} mode=$mode -> $result")
        val failed = result.startsWith("shell-exit") ||
            result.startsWith("shell-error") ||
            result.startsWith("intent-failed") ||
            result.startsWith("shell-blocked")
        // 41B：小窗启动成功 → 登记 registry + 显示底角把手
        if (!failed && mode != Prefs.LAUNCH_FULL) {
            registerFreeformTask(app)
        }
        if (failed) {
            main.post {
                Toast.makeText(
                    this,
                    "启动失败：${app.label}（特权服务未连接？）",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun loadApps(): List<PilotApp> {
        val pm = packageManager
        PilotLog.d(this, TAG, "wheel order -> ${Prefs.load(this).joinToString()}")
        val apps = Prefs.load(this).mapNotNull { key ->
            // tool:<id> 是快捷工具，不需要查包信息
            if (QuickTools.isToolKey(key)) {
                QuickTools.asPilotApp(this, key)
            } else runCatching {
                val info = pm.getApplicationInfo(key, 0)
                PilotApp(
                    pkg = key,
                    label = pm.getApplicationLabel(info).toString(),
                    icon = loadIcon(key),
                )
            }.getOrNull()
        }.toMutableList()
        // 微信消息入口：收到通知后置顶一项，用微信图标 + 红点与普通项区分。
        // 只在本机内存里保存；点进去后 clearMessage，下次来通知再自动回到首位。
        if (Prefs.wechatNotify(this) && PilotNotificationListener.hasMessage()) {
            apps.add(
                0,
                PilotApp(
                    pkg = PilotNotificationListener.MSG_PKG,
                    label = "微信消息 · ${PilotNotificationListener.summary()}",
                    icon = loadIcon(PilotNotificationListener.WECHAT_PKG),
                    badge = true,
                ),
            )
        }
        return apps
    }

    private fun loadIcon(pkg: String): Bitmap? = runCatching {
        packageManager.getApplicationIcon(pkg).toBitmap(96, 96)
    }.getOrNull()

    // ---------------------------------------------------------------------------
    // 维度1：区域 passthrough（防御性，仅探索模式开启时生效）
    // ---------------------------------------------------------------------------

    /**
     * 用「区域 passthrough」把热区从无障碍手势/探索处理中摘出。
     *
     * 两个 API 的分工与组合（均 API 30+；每个 display 只有一个 region，新值覆盖旧值，
     * 传空 Region 恢复默认）：
     *  - [setGestureDetectionPassthroughRegion]：区域内「开始的触摸」绕过手势检测器
     *    （探索模式下的 swipe 等手势识别），直接进入视图层次结构 —— 负责热区内点击/滑动
     *    不被无障碍手势吞掉；
     *  - [setTouchExplorationPassthroughRegion]：区域内触摸绕过触摸探索（hover 焦点导航），
     *    不产生探索焦点/读屏高亮 —— 负责热区不让下层控件被探索模式误聚焦。
     *  两者互补，热区场景必须「同时设置同一个 Region」：只设前者仍留下探索焦点副作用，
     *  只设后者仍会吞掉滑动手势。
     *
     * 关键前提：这两个 API 仅在 flagRequestTouchExplorationMode 开启时才有意义。
     * 本项目默认「不开」探索模式——见 onServiceConnected 的注释与 11-19 事故记录：
     * 探索模式会锁死单击（单击被吞成 hover），且任一服务开启探索模式时 onMotionEvent
     * 反而收不到触摸屏事件。当前正确路径是 flagSendMotionEvents + setMotionEventSources。
     *
     * 因此这里检测 serviceInfo.flags：
     *  - 未开探索模式（默认）→ 传空 Region，恢复系统默认，零副作用、零行为变化；
     *  - 若产品通过 XML 强制开启探索模式 → 自动把热区 Region 从两种探索处理中摘出，
     *    把探索模式干扰面收敛到热区之外（热区外仍走系统手势检测）。
     * 回退：删除 addHotspots() 末尾的调用即恢复旧行为，不涉及任何窗口 flag 变化。
     */
    private fun applyPassthroughRegions() {
        // 整体 runCatching：无障碍服务的 Context 是非视觉 Context，本方法的任何异常都不得
        // 冒泡到 onServiceConnected —— 曾因 getDisplay() 抛 UnsupportedOperationException
        // 致服务连接崩溃、进程闪退（2026-11-20 实测）。
        runCatching {
            if (Build.VERSION.SDK_INT >= 30) {
                val explorationOn = runCatching {
                    serviceInfo.flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE != 0
                }.getOrDefault(false)
                val region = if (explorationOn) buildHotspotRegion() else Region()
                // getDisplay() 在非视觉 Context 上抛 UnsupportedOperationException（API 30+ 严格
                // 校验）；热区 overlay 通过 WindowManager 加在默认屏，displayId 固定用 DEFAULT_DISPLAY。
                val displayId = Display.DEFAULT_DISPLAY
                setGestureDetectionPassthroughRegion(displayId, region)
                setTouchExplorationPassthroughRegion(displayId, region)
                if (Prefs.verboseLog(this)) {
                    Log.w(TAG, "passthrough-region: exploration=$explorationOn region=$region")
                }
            }
        }.onFailure { Log.w(TAG, "passthrough-region unavailable: ${it.message}") }
    }

    /** 55 号：物理显示边界（`maximum ∪ current`），热区几何的唯一基准 */
    private fun displayBounds(): WindowRect {
        val dm = resources.displayMetrics
        return wm.physicalBounds(dm.widthPixels, dm.heightPixels)
    }

    /**
     * 55 号：热区离底距离（px）。与 [addHotspotViews] 的视觉窗口同一套护栏
     * （用户值 / 导航栏+8dp / 内缩取 max），否则判定区与看得见的把手会静默错位。
     */
    private fun effectiveBottomPx(): Int {
        val d = resources.displayMetrics.density
        return ConfigRules.hotspotBottomPx(
            bottomDp = Prefs.hotBottom(this),
            density = d,
            navBarPx = wm.navBarBottomPx(),
            edgeInsetPx = (Prefs.hotInset(this) * d).toInt(),
        )
    }

    /** 等价 dp（保留给仍以 dp 为入参的调用点；56 号起判定统一走 px 版 sectorContains） */
    @Suppress("unused")
    private fun effectiveBottomDp(): Float = effectiveBottomPx() / resources.displayMetrics.density

    /** 左右热区的屏幕坐标并集（56 号：四分之一椭圆，不再是矩形） */
    private fun buildHotspotRegion(): Region {
        val d = resources.displayMetrics.density
        val w = (Prefs.hotWidth(this) * d).toInt()
        val h = (Prefs.hotHeight(this) * d).toInt()
        val inset = (Prefs.hotInset(this) * d).toInt()
        val bounds = displayBounds()
        val bottomPx = effectiveBottomPx()
        return Region().apply {
            if (Prefs.leftEnabled(this@PilotService)) {
                op(
                    sectorRegion(bounds, inset, bottomPx, w, h, left = true),
                    Region(), Region.Op.UNION,
                )
            }
            if (Prefs.rightEnabled(this@PilotService)) {
                op(
                    sectorRegion(bounds, inset, bottomPx, w, h, left = false),
                    Region(), Region.Op.UNION,
                )
            }
        }
    }

    /**
     * 55 号：无障碍路径的几何自检，与 `RawHotspotController` 的 `GATE` 同格式，
     * 便于两次安装直接 diff。`dTop` = 判定区顶端 − 视觉窗口顶端。
     */
    private fun traceGateA11y(retry: Boolean = true) {
        runCatching {
            val d = resources.displayMetrics.density
            val dm = resources.displayMetrics
            val bounds = displayBounds()
            val w = (Prefs.hotWidth(this) * d).toInt()
            val h = (Prefs.hotHeight(this) * d).toInt()
            val inset = (Prefs.hotInset(this) * d).toInt()
            val bottomPx = effectiveBottomPx()
            val judge = DisplayGate.sectorBoundsRect(bounds, inset, bottomPx, w, h, left = true)
            val visual = DisplayGate.visualRect(bounds, w, h, inset, bottomPx, left = true)
            // 旧实现按 displayMetrics 推导判定区；dOld 记录「若仍按旧口径会错多少」，供回归比对
            val dmBounds = WindowRect(0, 0, dm.widthPixels, dm.heightPixels)
            val dOld = DisplayGate.verticalDriftPx(
                DisplayGate.judgeRect(dmBounds, w, h, inset, bottomPx, left = true), visual,
            )
            val rotation = runCatching {
                getSystemService(android.hardware.display.DisplayManager::class.java)
                    ?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation
            }.getOrNull() ?: 0
            val g = DisplayGate.Geometry(
                physical = bounds,
                current = bounds,
                maximum = bounds,
                dmWidth = dm.widthPixels,
                dmHeight = dm.heightPixels,
                navBottomPx = wm.navBarBottomPx(),
                rotation = rotation,
            )
            val text = DisplayGate.gateLine(g, judge, visual, d, extra = "shape=ellipse src=a11y dOld=$dOld")
            Log.w(TAG, "GATE $text")
            if (PilotShellClient.isTraceReady()) {
                PilotShellClient.trace("GATE", text)
            } else if (retry) {
                // 55.1：首帧常早于 Shizuku 绑定，trace() 会静默丢弃 → 3s 后补发一次
                main.postDelayed({ traceGateA11y(retry = false) }, 3_000L)
            }
        }.onFailure { Log.w(TAG, "GATE(a11y) failed", it) }
    }

    private fun overlayParams(
        gravity: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        touchThrough: Boolean = false,
    ): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                (if (touchThrough) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0),
            PixelFormat.TRANSLUCENT,
        ).apply {
            this.gravity = gravity
            this.x = x
            this.y = y
        }
    }

    /** 应用页改了轮盘顺序：如果轮盘正开着，重建一次，避免还显示旧顺序 */
    fun refreshWheel() {
        if (wheel == null) return
        main.post {
            hideWheel()
            showWheel(currentFromLeft)
        }
    }

    /**
     * 小窗启动成功后登记 task。
     * bounds 用启动时实际传入的 [AppLauncher.lastLaunchBounds]；
     * 拿不到（老路径没记录）就用第 0 窗默认几何。
     */
    private suspend fun registerFreeformTask(app: PilotApp) {
        val entry = AppLauncher.awaitTask(this, app.pkg) ?: return
        val taskId = entry.taskId.toIntOrNull() ?: return
        val bounds = AppLauncher.lastLaunchBounds ?: run {
            val dm = resources.displayMetrics
            com.pilot.wheel.core.FreeformLayout.nextBounds(
                dm.widthPixels,
                dm.heightPixels,
                0,
                com.pilot.wheel.core.WindowGeometry.freeformBounds(
                    dm.widthPixels,
                    dm.heightPixels,
                ),
            )
        }
        val icon = app.icon?.let {
            android.graphics.drawable.BitmapDrawable(resources, it)
        }
        FreeformWindowRegistry.record(taskId, app.pkg, bounds, icon)
        PilotLog.d(this, TAG, "freeform registered task=$taskId pkg=${app.pkg} bounds=$bounds")
    }

    /** 设置页的预览/显示热区按钮通过它回调到运行中的服务 */
    fun previewWheel() {
        showWheel(fromLeft = Prefs.leftEnabled(this))
    }

    /**
     * 识屏：注入「双指长按」，这是荣耀 YOYO 看见的触发方式。
     *
     * GLOBAL_ACTION 里没有识屏动作，YOYO 看见也没有对第三方开放的 intent，
     * 所以走无障碍手势等价于用户自己双指按住屏幕。
     * 延迟 620ms 再注入：等更多面板/轮盘收起、焦点回到原来的页面。
     */
    fun doubleFingerLongPress(): Boolean {
        val dm = resources.displayMetrics
        val cx = dm.widthPixels / 2f
        val cy = dm.heightPixels / 2f
        val gap = 110f
        // 53 号：原实现传 handler=null（回调落到无障碍服务主线程，与主线程消息队列强耦合）；
        // 改为专属线程投递，门控与超时兜底与其他注入入口统一。
        return injector().submit(
            "two-finger",
            build = {
                val stroke = { x: Float ->
                    val path = Path().apply {
                        moveTo(x, cy)
                        lineTo(x, cy)
                    }
                    GestureDescription.StrokeDescription(path, 0L, 900L)
                }
                GestureDescription.Builder()
                    .addStroke(stroke(cx - gap))
                    .addStroke(stroke(cx + gap))
                    .build()
            },
            done = { },
        )
    }

    private val guideViews = mutableListOf<View>()

    /** 把热区位置画成半透明红块。拖动滑块时会连续调用，实现实时预览 */
    fun showHotspotGuide(
        wDp: Float,
        hDp: Float,
        insetDp: Float,
        bottomDp: Float = Prefs.hotBottom(this),
    ) {
        removeGuide()
        val density = resources.displayMetrics.density
        val w = (wDp * density).toInt().coerceAtLeast(1)
        // 红色预览区真实反映触发区高度，允许调到很小（16dp 起）。
        val h = (hDp * density).toInt().coerceAtLeast(1)
        val margin = (insetDp * density).toInt()
        val bottom = cornerBottomPx(margin, bottomDp)

        if (Prefs.leftEnabled(this)) {
            runCatching {
                val v = View(this).apply { setBackgroundColor(0x59FF3B30) }
                wm.addView(v, overlayParams(Gravity.START or Gravity.BOTTOM, margin, bottom, w, h))
                guideViews += v
            }
        }
        if (Prefs.rightEnabled(this)) {
            runCatching {
                val v = View(this).apply { setBackgroundColor(0x59FF3B30) }
                wm.addView(v, overlayParams(Gravity.END or Gravity.BOTTOM, margin, bottom, w, h))
                guideViews += v
            }
        }
    }

    fun hideHotspotGuide() = removeGuide()

    private fun removeGuide() {
        guideViews.forEach { runCatching { wm.removeView(it) } }
        guideViews.clear()
    }

    /** 设置页点「显示热区」：蓝色闪烁 1.6 秒，同时短暂显示红块几何 */
    fun highlightHotspots() {
        showHotspotGuide(Prefs.hotWidth(this), Prefs.hotHeight(this), Prefs.hotInset(this))
        (leftHotspot as? CornerHotspot)?.flashZone()
        (rightHotspot as? CornerHotspot)?.flashZone()
        main.postDelayed({ hideHotspotGuide() }, 1600L)
    }

    companion object {
        const val TAG = "WheelPilot"
        const val THRESHOLD_DP = 22f

        /** 同一图标的连点去重窗口：启动链本身 0.3–1s，期间重复点击会多开窗口 */
        private const val PICK_DEDUP_MS = 500L
        const val AUTO_DISMISS_MS = 15000L

        private const val TAP_RESTORE_MS = 45L
        private const val TAP_GUARD_MAX_MS = 220L
        private const val TAP_QUEUE_EXPIRE_MS = 400L
        private const val INJECT_DELAY_MS = 26L

        /** 注入连续 cancelled 达到此值即标记 DEGRADED（仅落盘告警，不改窗口行为） */
        private const val INJECT_DEGRADED_THRESHOLD = 3
        private const val IME_YIELD_DEBOUNCE_MS = 120L
        private const val A11Y_COOLDOWN_MS = 500L

        /**
         * 62.1：旁路静默时由 a11y 接管的窗口长度（ms）。
         * 旁路重启通常在 100ms 内完成，接管窗口只需覆盖一次完整手势即可，取 1.5s 富余。
         */
        private const val A11Y_TAKEOVER_MS = 1_500L

        @Volatile
        var instance: PilotService? = null
            private set
    }
}
