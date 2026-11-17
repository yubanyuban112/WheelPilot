package com.pilot.wheel

import android.accessibilityservice.AccessibilityService
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
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.core.graphics.drawable.toBitmap
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
    private var wheel: View? = null
    private var currentFromLeft = true

    /** 场景暂停中：横屏 / 游戏 / 输入框获得焦点时撤掉热区 */
    private var scenePaused = false

    /** 热区待太久自动收起，避免全屏 overlay 卡死用户的触摸 */
    private val autoDismiss = Runnable { hideWheel() }

    /** 设置页改了开关就即时重建热区，不用重启服务 */
    private val prefListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            main.post {
                if (scenePaused) return@post
                removeHotspots()
                addHotspots()
            }
        }

    /**
     * 锁屏 / 息屏时无障碍热区会盖在锁屏界面上（TYPE_ACCESSIBILITY_OVERLAY 不受 keyguard 限制），
     * 所以必须自己监听屏幕状态，把热区撤掉，避免在锁屏界面上滑出轮盘。
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            main.post { updateScenePause(null) }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WindowManager::class.java)
        instance = this
        Log.d(TAG, "service connected")
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
        updateScenePause(null)
        addHotspots()
    }

    /** 锁屏或息屏：此时不该有任何热区/轮盘 */
    private fun isKeyguardOrScreenOff(): Boolean = runCatching {
        val km = getSystemService(KeyguardManager::class.java)
        val pm = getSystemService(PowerManager::class.java)
        (km?.isKeyguardLocked == true) || (pm?.isInteractive == false)
    }.getOrDefault(false)

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        updateScenePause(event)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (wheel == null) applyScenePause(computeScenePause(null))
    }

    override fun onInterrupt() = Unit

    // ---------------------------------------------------------------------------
    // 场景暂停（移植自同类小窗应用）：横屏、游戏、输入框获得焦点时只撤热区，
    // 已经打开的窗口不受影响；离开场景自动恢复。
    // ---------------------------------------------------------------------------

    private fun computeScenePause(event: AccessibilityEvent?): Boolean {
        val pkg = event?.packageName?.toString()
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val game = Prefs.pauseGame(this) && pkg != null && isGamePackage(pkg)
        val keyboard = Prefs.pauseKeyboard(this) && hasFocusedInput()
        // 锁屏 / 息屏优先级最高：这两个场景下热区必须不存在
        val locked = isKeyguardOrScreenOff()
        val paused = locked || (Prefs.pauseLandscape(this) && landscape) || game || keyboard
        if (paused && !scenePaused) {
            PilotLog.d(
                this, TAG,
                "scene pause: landscape=$landscape game=$game keyboard=$keyboard pkg=$pkg",
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

    private fun hasFocusedInput(): Boolean = runCatching {
        val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        focused != null && (
            focused.isEditable ||
                focused.className?.contains("EditText", ignoreCase = true) == true
            )
    }.getOrDefault(false)

    override fun onDestroy() {
        instance = null
        runCatching { unregisterReceiver(screenReceiver) }
        Prefs.unregister(this, prefListener)
        main.removeCallbacks(restoreThrough)
        main.removeCallbacks(guardThrough)
        main.removeCallbacks(pendingReplay)
        throughInFlight = false
        pendingTap = null
        removeHotspots()
        hideWheel()
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

    private fun cornerBottomPx(edgeInsetPx: Int): Int {
        val density = resources.displayMetrics.density
        return maxOf(
            (56 * density).toInt(),
            navigationBarInsetPx() + (8 * density).toInt(),
            edgeInsetPx,
        )
    }

    private fun addHotspots() {
        // 两边分别创建、分别兜异常。之前两个 addView 放在同一个 runCatching 里，
        // 右侧失败会连左侧一起回滚，右侧就永远补不上。
        removeHotspots()

        val density = resources.displayMetrics.density
        val w = (Prefs.hotWidth(this) * density).toInt()
        val h = (Prefs.hotHeight(this) * density).toInt()
        val margin = (Prefs.hotInset(this) * density).toInt()
        val bottom = cornerBottomPx(margin)
        val handle = Prefs.cornerHandle(this)
        val pass = Prefs.hotspotPassThrough(this)

        fun build(fromLeft: Boolean): CornerHotspot = CornerHotspot(
            context = this,
            left = fromLeft,
            thresholdDp = THRESHOLD_DP,
            showHandle = handle,
            passThrough = pass,
            onSwipe = { showWheel(fromLeft) },
            onTapThrough = { x, y ->
                if (Prefs.hotspotPassThrough(this@PilotService)) {
                    performTapThrough(fromLeft, x, y)
                }
            },
            onGesturePassThrough = { path, dur ->
                if (Prefs.hotspotPassThrough(this@PilotService)) {
                    performGesturePassThrough(fromLeft, path, dur)
                }
            },
        )

        if (Prefs.leftEnabled(this)) {
            runCatching {
                val view = build(fromLeft = true)
                wm.addView(
                    view,
                    overlayParams(Gravity.START or Gravity.BOTTOM, margin, bottom, w, h),
                )
                leftHotspot = view
            }.onFailure { Log.e(TAG, "left hotspot failed", it) }
        }

        if (Prefs.rightEnabled(this)) {
            runCatching {
                val view = build(fromLeft = false)
                wm.addView(
                    view,
                    overlayParams(Gravity.END or Gravity.BOTTOM, margin, bottom, w, h),
                )
                rightHotspot = view
            }.onFailure { Log.e(TAG, "right hotspot failed", it) }
        }

        Log.d(TAG, "hotspots: left=${leftHotspot != null} right=${rightHotspot != null} pass=$pass")
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
            if (!dispatchTap(x, y, TAP_RESTORE_MS.toInt())) {
                endPassThrough()
                return@postDelayed
            }
            main.removeCallbacks(restoreThrough)
            main.postDelayed(restoreThrough, TAP_RESTORE_MS)
        }, INJECT_DELAY_MS)
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
            val dispatched = dispatchSwipe(path, duration) {
                endPassThrough()
            }
            if (!dispatched) endPassThrough()
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

        lp.flags = throughOldFlags
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

    private fun dispatchTap(x: Float, y: Float, durationMs: Int): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0L,
                    durationMs.coerceIn(20, 300).toLong(),
                ),
            )
            .build()
        return runCatching { dispatchGesture(gesture, null, main) }.getOrDefault(false)
    }

    private fun dispatchSwipe(
        points: List<Pair<Float, Float>>,
        durationMs: Long,
        onDone: Runnable,
    ): Boolean {
        val path = Path().apply {
            moveTo(points[0].first, points[0].second)
            // 按采样点逐段连线，还原曲线； dip 采样已抑制抖动
            for (i in 1 until points.size) lineTo(points[i].first, points[i].second)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0L,
                    durationMs.coerceIn(80L, 1200L),
                ),
            )
            .build()
        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                main.post(onDone)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                main.post(onDone)
            }
        }
        return runCatching { dispatchGesture(gesture, callback, main) }.getOrDefault(false)
    }

    // ---------------------------------------------------------------------------
    // 轮盘
    // ---------------------------------------------------------------------------

    private fun showWheel(fromLeft: Boolean) {
        if (wheel != null) return
        currentFromLeft = fromLeft
        PilotLog.d(this, TAG, "showWheel fromLeft=$fromLeft")

        val apps = loadApps()
        if (apps.isEmpty()) {
            Log.w(TAG, "no apps configured, open the app and pick some")
            return
        }

        val view = WheelOverlay(
            context = this,
            items = apps,
            fromLeft = fromLeft,
            radiusDp = Prefs.radiusDp(this),
            iconDp = Prefs.iconDp(this),
            angleDeg = Prefs.angle(this),
            hapticEnabled = Prefs.haptic(this),
            showHint = Prefs.showWheelHint(this),
            dismissOnOutside = Prefs.dismissOutside(this),
            closeMode = Prefs.closeMode(this),
            closeX = Prefs.closeX(this),
            closeDistance = Prefs.closeDistance(this),
            closeDuration = Prefs.closeDuration(this),
            closeFromBottom = Prefs.closeFromBottom(this),
            onPick = ::onPick,
            onDismiss = ::hideWheel,
            onMore = {
                hideWheel()
                runCatching {
                    startActivity(
                        Intent(this, AppSheetActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
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
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                blurBehindRadius = (30 * resources.displayMetrics.density).toInt()
            }
        }

        wheel = view
        runCatching {
            wm.addView(view, params)
            main.removeCallbacks(autoDismiss)
            main.postDelayed(autoDismiss, AUTO_DISMISS_MS)
        }.onFailure {
            wheel = null
            Log.e(TAG, "show wheel failed", it)
        }
    }

    private fun hideWheel() {
        main.removeCallbacks(autoDismiss)
        val view = wheel ?: return
        wheel = null
        runCatching { wm.removeView(view) }
    }

    private fun onPick(app: PilotApp) {
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
            if (!PilotNotificationListener.open()) {
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
        scope.launch {
            val result = AppLauncher.launch(this@PilotService, app.pkg, mode)
            Log.d(TAG, "launch ${app.label} mode=$mode -> $result")
            PilotLog.d(this@PilotService, TAG, "launch ${app.pkg} mode=$mode -> $result")
            val failed = result.startsWith("shell-exit") ||
                result.startsWith("shell-error") ||
                result.startsWith("intent-failed") ||
                result.startsWith("shell-blocked")
            if (failed) {
                main.post {
                    Toast.makeText(
                        this@PilotService,
                        "启动失败：${app.label}（特权服务未连接？）",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
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
        // 微信消息入口：收到通知后轮盘里临时多一项，只在本机内存里保存
        if (Prefs.wechatNotify(this) && PilotNotificationListener.hasMessage()) {
            apps += PilotApp(
                pkg = PilotNotificationListener.MSG_PKG,
                label = "微信消息 · ${PilotNotificationListener.summary()}",
                icon = null,
            )
        }
        return apps
    }

    private fun loadIcon(pkg: String): Bitmap? = runCatching {
        packageManager.getApplicationIcon(pkg).toBitmap(96, 96)
    }.getOrNull()

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
        val stroke = { x: Float ->
            val path = Path().apply {
                moveTo(x, cy)
                lineTo(x, cy)
            }
            GestureDescription.StrokeDescription(path, 0L, 900L)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(stroke(cx - gap))
            .addStroke(stroke(cx + gap))
            .build()
        return runCatching { dispatchGesture(gesture, null, null) }
            .onFailure { Log.w(TAG, "识屏手势注入失败", it) }
            .getOrDefault(false)
    }

    private val guideViews = mutableListOf<View>()

    /** 把热区位置画成半透明红块。拖动滑块时会连续调用，实现实时预览 */
    fun showHotspotGuide(wDp: Float, hDp: Float, insetDp: Float) {
        removeGuide()
        val density = resources.displayMetrics.density
        val w = (wDp * density).toInt().coerceAtLeast(1)
        // 红色预览区真实反映触发区高度，允许调到很小（16dp 起）。
        val h = (hDp * density).toInt().coerceAtLeast(1)
        val margin = (insetDp * density).toInt()
        val bottom = cornerBottomPx(margin)

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
        const val AUTO_DISMISS_MS = 15000L

        private const val TAP_RESTORE_MS = 45L
        private const val TAP_GUARD_MAX_MS = 220L
        private const val TAP_QUEUE_EXPIRE_MS = 400L
        private const val INJECT_DELAY_MS = 26L

        @Volatile
        var instance: PilotService? = null
            private set
    }
}
