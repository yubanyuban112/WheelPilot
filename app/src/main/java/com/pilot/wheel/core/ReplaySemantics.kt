package com.pilot.wheel.core

/**
 * 回放语义边界（53 号 · 维度二 · 第 5 项）。
 *
 * 事实前提（不得绕过）：`GestureDescription` 只有**相对时间轴**，
 * `StrokeDescription(path, startTime, duration)` 无法承载原始 `downTime/eventTime`。
 * 因此依赖惯性/甩动语义的序列一旦回放，速度由我们对 `duration` 的设定人造，
 * 与用户真实手势不一致 —— 与其伪装成正常触摸，不如显式放弃回放。
 *
 * 本对象只做一件事：把「真实位移 + 真实时长」换算成速度，供调用方决定
 * 是否标记 `REPLAY_SEMANTIC_LOST` 并旁路放行。纯函数，可单测。
 */
object ReplaySemantics {

    /** 默认阈值：1500 px/s。低于此值的拖动按「可回放」处理。 */
    const val DEFAULT_MAX_VELOCITY_PX_S = 1500f

    /**
     * DOWN→UP 端点位移速度（px/s）。
     *
     * @param durationMs 真实时长（MotionEvent UP.eventTime - DOWN.downTime），必须用**原始值**，
     *                   不能用注入时夹取过的 80–800ms，否则速度被系统性低估。
     */
    fun displacementVelocity(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long,
    ): Float {
        if (durationMs <= 0L) return 0f
        val dx = endX - startX
        val dy = endY - startY
        val dist = kotlin.math.hypot(dx, dy)
        return dist / (durationMs / 1000f)
    }

    /** 是否应放弃回放（速度超阈值）。thresholdPxS <= 0 视为关闭该规则，永不放行。 */
    fun shouldAbandon(velocityPxS: Float, thresholdPxS: Float): Boolean =
        thresholdPxS > 0f && velocityPxS > thresholdPxS
}
