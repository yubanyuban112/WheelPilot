package com.pilot.wheel

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

import com.pilot.wheel.core.SwipeDetector

/**
 * 角落感应区。手势判定规则：
 *
 *  - 上滑：呼出轮盘（主交互）；
 *  - 小把手轻点 / 长按：呼出轮盘（备选交互）；
 *  - 普通点击：开启穿透时，临时把窗口设成 NOT_TOUCHABLE，把这一下点击注入给下层应用；
 *  - 其它手势（斜划、短距离划动等）：同样穿透给下层，而不是被热区吞掉。
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
    }

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val tapTolerance = touchSlop * TAP_TOLERANCE_FACTOR
    private val thresholdPx = thresholdDp * density

    private var armed = false
    private var activated = false
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
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        applyGestureExclusion(width, height)
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

    /** 底部系统返回手势很容易吃掉角落上滑，这里显式声明排除区 */
    private fun applyGestureExclusion(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        if (exclusionSuspended) return
        runCatching { systemGestureExclusionRects = listOf(Rect(0, 0, w, h)) }
    }

    /** 穿透注入时调用：让出排除区；结束后恢复 */
    fun setExclusionSuspended(suspended: Boolean) {
        if (exclusionSuspended == suspended) return
        exclusionSuspended = suspended
        runCatching {
            systemGestureExclusionRects = if (suspended) emptyList() else listOf(Rect(0, 0, width, height))
        }
    }

    private fun layoutHandle() {
        // 把手 22×58dp，距底 80dp，左右距边 4dp
        val x = if (left) dp(4f) else width - dp(4f) - dp(22f)
        val y = max(height - dp(80f), dp(4f))
        handleRect.set(x, y, x + dp(22f), min(height - dp(14f), y + dp(58f)))
    }

    private fun handleContains(x: Float, y: Float): Boolean {
        if (handleRect.width() <= 0 || handleRect.height() <= 0) return false
        val pad = dp(5f)
        return x >= handleRect.left - pad && x <= handleRect.right + pad &&
            y >= handleRect.top - pad && y <= handleRect.bottom + pad
    }

    /** 供「显示热区」使用：蓝色闪烁提示，2.2 秒后自动淡出 */
    fun flashZone() {
        flashUntil = SystemClock.elapsedRealtime() + FLASH_MS
        invalidate()
        postInvalidateDelayed(FLASH_MS + 100L)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
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
        if (!showHandle || handleRect.width() <= 0) return
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
        armed = false
        activated = false
        if (notify && wasActivated) onSwipe()
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
            if (SwipeDetector.shouldActivate(left, touchSlop, dx, dy, dist)) {
                activated = true
                removeCallbacks(longPressRunnable)
                // 滑动达到呼出阈值的瞬间给一个更明显的双脉冲震动（触发区专用波形）
                if (Prefs.haptic(context)) Haptics.open(context)
            }
        }
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
        armed = false
        activated = false
        if (wasActivated) {
            onSwipe()
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

        // 划动但没达到呼出阈值（含多指）：交给下层，而不是白白吞掉
        if (passThrough) {
            appendGesturePath(endX, endY)
            onGesturePassThrough(gesturePath.toList(), duration)
        }
    }

    private fun appendGesturePath(endX: Float, endY: Float) {
        if (gesturePath.isEmpty() || gesturePath.last() != (endX to endY)) {
            if (gesturePath.size < MAX_PATH_POINTS) gesturePath.add(endX to endY)
        }
    }

    private fun onActionCancel() {
        removeCallbacks(longPressRunnable)
        armed = false
        activated = false
    }
}
