package com.pilot.wheel

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

import com.pilot.wheel.core.SwipeDetector
import com.pilot.wheel.core.physicalBounds

/**
 * 角落感应区。手势判定规则：
 *
 *  - 上滑：呼出轮盘（主交互）；
 *  - 小把手轻点 / 长按：呼出轮盘（备选交互）；
 *  - 普通点击：开启穿透时，临时把窗口设成 NOT_TOUCHABLE，把这一下点击注入给下层应用；
 *  - 其它手势（斜划、短距离划动等）：同样穿透给下层，而不是被热区吞掉。
 *
 * 40 号改动：上滑达到阈值后进入「跟手」模式——触摸事件流绑死在收到 DOWN 的窗口，
 * 轮盘 overlay 中途 addView 抢不到这段手势，所以唤出全程由本 View 驱动：
 * 每次 MOVE 把「手指位移进度 + 屏幕坐标」喂给轮盘（onSwipeMove），
 * UP/CANCEL 时交给 onSwipeEnd / onSwipeCancel 收尾。
 *
 * 这样"热区穿透"和"上滑呼出"可以同时成立，不再需要靠 API 34 的
 * onMotionEvent 全局观察，也不需要单独的小把手窗口。
 */
@SuppressLint("ViewConstructor")
class CornerHotspot(
    context: Context,
    private val left: Boolean,
    private val thresholdDp: Float,
    private val showHandle: Boolean,
    private val passThrough: Boolean,
    private val onSwipe: () -> Unit,
    /** 上滑达到阈值：返回 true 表示已进入跟手模式（后续 MOVE/UP 由本类持续喂给轮盘） */
    private val onSwipeActivate: () -> Boolean = { false },
    /** 跟手模式下的每次移动：progress ∈ [0,1] 为手指位移进度，(x,y) 为屏幕坐标 */
    private val onSwipeMove: (progress: Float, x: Float, y: Float) -> Unit = { _, _, _ -> },
    /** 跟手模式松手：(x,y) 为抬起点屏幕坐标，由轮盘判定启动/展开 */
    private val onSwipeEnd: (x: Float, y: Float) -> Unit = { _, _ -> },
    /** 跟手模式被系统打断 / 多指：取消唤出 */
    private val onSwipeCancel: () -> Unit = {},
    private val onTapThrough: (x: Float, y: Float) -> Unit,
    /** 整段轨迹（屏幕坐标）+ 时长，交给下层按真实路径注入，避免只投直线的失真 */
    private val onGesturePassThrough: (path: List<Pair<Float, Float>>, durationMs: Long) -> Unit,
) : View(context) {

    companion object {
        private const val LONG_PRESS_MS = 320L
        // 把手轻点上限时长：超过按普通划动处理
        private const val HANDLE_TAP_MAX_MS = 650L
        private const val TAP_TOLERANCE_FACTOR = 2f
        private const val FLASH_MS = 2200L
        private const val PATH_STEP_DP = 6f
        private const val MAX_PATH_POINTS = 120
        /** 跟手滑出总行程：手指从起滑点累计移动这么多距离，轮盘完全展开 */
        private const val FOLLOW_TRAVEL_DP = 170f
    }

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val tapTolerance = touchSlop * TAP_TOLERANCE_FACTOR
    private val thresholdPx = thresholdDp * density

    private var armed = false
    private var activated = false
    /** 达到阈值后 onSwipeActivate 返回 true：本次手势由跟手链路接管 */
    private var followStream = false
    private var multiTouch = false
    private var longPressTriggered = false
    private var downLocalX = 0f
    private var downLocalY = 0f
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downTime = 0L
    private var flashUntil = 0L
    /** 穿透注入期间暂时让出系统手势排除区，避免下层收不到边缘手势 */
    private var exclusionSuspended = false
    /** 键盘在屏让位中：不接收触摸（窗口标志由 Service 设置），也不画把手 */
    private var imeYielded = false
    /** 本次手势的采样轨迹（屏幕坐标），用于还原曲线而非直线 */
    private val gesturePath = ArrayList<Pair<Float, Float>>(MAX_PATH_POINTS)
    private val location = IntArray(2)
    private val handleRect = RectF()
    private val flashRect = RectF()

    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33000000.toInt() }
    private val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x4DFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val handleDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6FFFFFF.toInt() }
    private val flashFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x3B3B82F6.toInt() }
    private val flashStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC3B82F6.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val flashText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2F6BFF.toInt()
        textAlign = Paint.Align.CENTER
        textSize = dp(12f)
    }

    private val longPressRunnable = Runnable {
        if (armed && !activated && !multiTouch && showHandle && handleContains(downLocalX, downLocalY)) {
            activated = true
            longPressTriggered = true
            if (Prefs.haptic(context)) Haptics.open(context)
            onSwipe()
        }
    }

    private fun dp(value: Float): Float = value * density

    init {
        isClickable = true
        isFocusable = false
        setWillNotDraw(false)
        // 维度2：NO_HIDE_DESCENDANTS 连子树一起从节点树摘除（NO 只排除自身）。
        // 已确认 AccessibilityServiceInfo 未开 flagIncludeNotImportantViews，不会被抵消，
        // 因此这里对本服务自己的读屏/节点读取同样生效——热区控件不再污染 rootInActiveWindow。
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        // 维度2（API 34+ 增强）：把覆盖层标记为无障碍数据敏感，从所有无障碍服务摘出。
        // 与 NO_HIDE_DESCENDANTS 是叠加关系；API < 34 时上者是等价兜底。
        if (Build.VERSION.SDK_INT >= 34) {
            setAccessibilityDataSensitive(ACCESSIBILITY_DATA_SENSITIVE_YES)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        applyGestureExclusion(width, height)
        // 系统手势 inset 常在 attach 后一帧才上报，下一帧用真实值重算一次
        post { applyGestureExclusion(width, height) }
    }

    override fun onDetachedFromWindow() {
        runCatching { systemGestureExclusionRects = emptyList() }
        removeCallbacks(longPressRunnable)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyGestureExclusion(w, h)
        layoutHandle()
    }

    /**
     * 维度3：排除区只覆盖「窗口内不与系统手势带重叠」的部分。
     * 旧实现整窗 Rect(0,0,w,h) 抢排，热区一旦压到系统返回手势带就会吞掉系统返回。
     * 现按 WindowInsets.Type.systemGestures() 的 bottom inset 裁剪：
     * 系统带内让给系统（短滑返回），带外保留排除区（长滑上滑呼出热区），二者共存。
     * ROM 未上报 inset 时退化为整窗排除（旧行为），不引入回归。
     */
    private fun applyGestureExclusion(w: Int, h: Int) {
        if (w <= 0 || h <= 0 || exclusionSuspended) return
        runCatching {
            val gestureBottom = rootWindowInsets
                ?.getInsets(WindowInsets.Type.systemGestures())?.bottom ?: 0
            // 55 号：排除区的底边同样按物理显示算——本视图贴的是物理底边，
            // 用 displayMetrics.heightPixels 会少算导航条那一段，排除区整体偏上。
            val screenH = runCatching {
                val wm = context.getSystemService(android.view.WindowManager::class.java)
                val dm = resources.displayMetrics
                wm.physicalBounds(dm.widthPixels, dm.heightPixels).bottom
            }.getOrDefault(resources.displayMetrics.heightPixels)
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            // 排除区底边（窗口本地坐标）不得超过系统手势带顶边
            val bottom = if (gestureBottom > 0) {
                (screenH - gestureBottom - loc[1]).coerceIn(0, h)
            } else {
                h
            }
            systemGestureExclusionRects =
                if (bottom > 0) listOf(Rect(0, 0, w, bottom)) else emptyList()
        }.onFailure {
            // 任何 ROM 差异都退回旧行为，绝不因排除区异常破坏热区触发
            runCatching { systemGestureExclusionRects = listOf(Rect(0, 0, w, h)) }
        }
    }

    /** 穿透注入时调用：让出排除区；结束后按系统手势带重算恢复 */
    fun setExclusionSuspended(suspended: Boolean) {
        if (exclusionSuspended == suspended) return
        exclusionSuspended = suspended
        if (suspended) {
            runCatching { systemGestureExclusionRects = emptyList() }
        } else {
            applyGestureExclusion(width, height)
        }
    }

    private fun layoutHandle() {
        // 把手 22×58dp，距底 80dp，左右距边 4dp。
        // 窗口高由设置滑杆控制（最低 16dp）：旧算法在 h≤28dp 时 bottom=min(h-14, y+58)≤y，
        // RectF 高 ≤0 —— drawHandle 只查 width 画不出任何东西，handleContains 恒 false，
        // 轻点/长按把手呼出静默失效。这里保证把手至少 16dp 高（极端小窗贴底边也不归零）。
        val x = if (left) dp(4f) else width - dp(4f) - dp(22f)
        val y = max(height - dp(80f), dp(4f))
        val bottom = min(
            height.toFloat(),
            max(min(height - dp(14f), y + dp(58f)), y + dp(16f)),
        )
        handleRect.set(x, y, x + dp(22f), max(bottom, y + dp(1f)))
    }

    private fun handleContains(x: Float, y: Float): Boolean {
        if (handleRect.width() <= 0 || handleRect.height() <= 0) return false
        val pad = dp(5f)
        return x >= handleRect.left - pad && x <= handleRect.right + pad &&
            y >= handleRect.top - pad && y <= handleRect.bottom + pad
    }

    /**
     * 键盘在屏时让位：窗口由 Service 设成 FLAG_NOT_TOUCHABLE（触摸直达输入法），
     * 这里同步把把手与闪烁提示一起隐藏 —— 否则键盘上会盖着一个小方块，
     * 用户会以为那里还能点。
     */
    fun setImeYielded(yielded: Boolean) {
        if (imeYielded == yielded) return
        imeYielded = yielded
        if (yielded) flashUntil = 0L
        invalidate()
    }

    /** 供「显示热区」使用：蓝色闪烁提示，2.2 秒后自动淡出 */
    fun flashZone() {
        flashUntil = SystemClock.elapsedRealtime() + FLASH_MS
        invalidate()
        postInvalidateDelayed(FLASH_MS + 100L)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (imeYielded) return
        drawFlash(canvas)
        drawHandle(canvas)
    }

    private fun drawFlash(canvas: Canvas) {
        val now = SystemClock.elapsedRealtime()
        if (now >= flashUntil) return
        val alpha = min(1f, (flashUntil - now) / 600f)
        flashFill.alpha = (50 * alpha).toInt()
        flashStroke.alpha = (205 * alpha).toInt()
        flashText.alpha = (255 * alpha).toInt()
        flashRect.set(dp(1f), dp(1f), width - dp(1f), height - dp(1f))
        canvas.drawRoundRect(flashRect, dp(14f), dp(14f), flashFill)
        canvas.drawRoundRect(flashRect, dp(14f), dp(14f), flashStroke)
        canvas.drawText("上滑 / 长按", width / 2f, height / 2f + dp(4f), flashText)
    }

    private fun drawHandle(canvas: Canvas) {
        if (!showHandle || handleRect.width() <= 0 || handleRect.height() <= 0) return
        canvas.drawRoundRect(handleRect, dp(11f), dp(11f), handleFill)
        canvas.drawRoundRect(handleRect, dp(11f), dp(11f), handleStroke)
        val cx = (handleRect.left + handleRect.right) / 2f
        val cy = (handleRect.top + handleRect.bottom) / 2f
        for (i in -1..1) {
            canvas.drawCircle(cx, cy + i * dp(7f), dp(1.7f), handleDot)
        }
    }

    private fun screenX(event: MotionEvent): Float {
        getLocationOnScreen(location)
        return location[0] + event.x
    }

    private fun screenY(event: MotionEvent): Float {
        getLocationOnScreen(location)
        return location[1] + event.y
    }

    private fun cancelInternal(notify: Boolean) {
        removeCallbacks(longPressRunnable)
        val wasActivated = activated
        val wasFollowing = followStream
        armed = false
        activated = false
        followStream = false
        if (notify && wasFollowing) {
            onSwipeCancel()
        } else if (notify && wasActivated) {
            onSwipe()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> onActionDown(event)
            MotionEvent.ACTION_MOVE -> onActionMove(event)
            MotionEvent.ACTION_UP -> onActionUp(event)
            MotionEvent.ACTION_CANCEL -> onActionCancel()
        }
        return true
    }

    private fun onActionDown(event: MotionEvent) {
        downLocalX = event.x
        downLocalY = event.y
        downX = screenX(event)
        downY = screenY(event)
        lastX = downX
        lastY = downY
        downTime = event.eventTime
        armed = true
        activated = false
        multiTouch = false
        longPressTriggered = false
        gesturePath.clear()
        gesturePath.add(downX to downY)
        removeCallbacks(longPressRunnable)
        postDelayed(longPressRunnable, LONG_PRESS_MS)
    }

    private fun onActionMove(event: MotionEvent) {
        if (!armed) return
        if (event.pointerCount > 1) {
            // 多指手势不该被热区吞掉：标记后继续采集，抬起时整段穿透给下层
            multiTouch = true
            removeCallbacks(longPressRunnable)
            return
        }
        lastX = screenX(event)
        lastY = screenY(event)
        sampleGesturePath()
        val dx = lastX - downX
        val dy = lastY - downY
        val dist = hypot(dx, dy)
        if (!activated) {
            if (dist > tapTolerance) removeCallbacks(longPressRunnable)
            // thresholdPx（22dp）此前只赋值不参与判定：真实门槛退化成 touchSlop*1.15 ≈ 9dp，
            // 在微信看视频/小说时手指稍往上带就被判成「呼出」，应用自己的上滑收不到。
            if (SwipeDetector.shouldActivate(left, touchSlop, dx, dy, dist, thresholdPx)) {
                activated = true
                removeCallbacks(longPressRunnable)
                // 滑动达到呼出阈值的瞬间给一个更明显的双脉冲震动（触发区专用波形）
                if (Prefs.haptic(context)) Haptics.open(context)
                // 40 号：阈值一到就建轮盘进跟手模式，此后手指拖多远轮盘出多少
                followStream = onSwipeActivate()
                if (followStream) sendFollowMove()
            }
        } else if (followStream) {
            sendFollowMove()
        }
    }

    /** 跟手进度 = 手指距起滑点的直线距离 / 总行程（170dp 滑满） */
    private fun sendFollowMove() {
        val dist = hypot(lastX - downX, lastY - downY)
        val progress = min(1f, dist / (FOLLOW_TRAVEL_DP * density))
        onSwipeMove(progress, lastX, lastY)
    }

    /** 按距离采样轨迹（最多 MAX_PATH_POINTS 个点），还原曲线而非直线 */
    private fun sampleGesturePath() {
        val last = gesturePath.lastOrNull()
        if (last == null || hypot(lastX - last.first, lastY - last.second) >= PATH_STEP_DP * density) {
            if (gesturePath.size < MAX_PATH_POINTS) gesturePath.add(lastX to lastY)
        }
    }

    private fun onActionUp(event: MotionEvent) {
        if (!armed) return
        removeCallbacks(longPressRunnable)
        val endX = screenX(event)
        val endY = screenY(event)
        val duration = event.eventTime - downTime
        val wasActivated = activated
        val wasFollowing = followStream
        armed = false
        activated = false
        followStream = false
        if (wasActivated) {
            if (wasFollowing && multiTouch) {
                // 跟手途中多指按下：按取消处理，避免抬起时误启动图标
                onSwipeCancel()
            } else if (wasFollowing) {
                // 松手点交给轮盘：落在图标上 → 直接启动；否则轮盘原地展开
                onSwipeEnd(endX, endY)
            } else {
                onSwipe()
            }
            return
        }

        val dist = hypot(endX - downX, endY - downY)
        // 小把手：快速轻点等价于呼出轮盘，即便穿透开启也不透传
        if (showHandle && !multiTouch && !longPressTriggered &&
            duration < HANDLE_TAP_MAX_MS && dist < tapTolerance &&
            handleContains(downLocalX, downLocalY)
        ) {
            // 只要有点击动作就震动反馈
            if (Prefs.haptic(context)) Haptics.select(context)
            onSwipe()
            return
        }

        // 静止释放（位移在容差内）一律按点击处理，不再限制时长窗口：
        // 之前 50-250ms 之外的快速/慢速点击会掉进手势透传分支，
        // 单点路径（path.size < 2）又被注入层丢弃，导致「穿透点了没反应」。
        if (!multiTouch && !longPressTriggered && dist < tapTolerance) {
            // 普通点击（穿透/呼出两种去向都算点击动作）也给震动反馈
            if (Prefs.haptic(context)) Haptics.select(context)
            if (passThrough) onTapThrough(downX, downY) else onSwipe()
            return
        }

        // 划动但没达到呼出阈值（含多指、反向划、横向划）：一律交回下层。
        // 之前只有开着「点击穿透」才透传，关着时这段手势直接消失 ——
        // 在微信视频/阅读器里就是「滑了一下没反应」。点击仍受穿透开关控制，
        // 因为点击是「要不要把这一下交给应用」的语义选择，而划动本来就是应用的。
        appendGesturePath(endX, endY)
        onGesturePassThrough(gesturePath.toList(), duration)
    }

    private fun appendGesturePath(endX: Float, endY: Float) {
        if (gesturePath.isEmpty() || gesturePath.last() != (endX to endY)) {
            if (gesturePath.size < MAX_PATH_POINTS) gesturePath.add(endX to endY)
        }
    }

    private fun onActionCancel() {
        removeCallbacks(longPressRunnable)
        val wasFollowing = followStream
        armed = false
        activated = false
        followStream = false
        if (wasFollowing) onSwipeCancel()
    }
}
