package com.pilot.wheel.core

import kotlin.math.abs

/**
 * 角落上滑呼出判定（纯函数，可单测）。
 *
 * 距离门槛取「touchSlop 的 1.15 倍」与调用方给的 [minDistancePx] 的较大值。
 * 只用 touchSlop（约 9dp）时，只要在热区里稍微往上一推就呼出轮盘 ——
 * 微信视频号上滑切下一条、阅读器上滑滚动都会被打断，表现为「热区把应用的手势吃掉了」。
 * 抬到 22dp 后，短促的轻扫一律交回给应用，明确的角落上滑仍然照常呼出。
 */
object SwipeDetector {

    private const val MIN_UP_SLOP_RATIO = 0.7f
    private const val MAX_INWARD_SLOP_RATIO = 0.8f
    private const val MIN_VERTICAL_RATIO = 0.9f
    private const val MIN_DISTANCE_SLOP_RATIO = 1.15f

    /**
     * 是否达到「上滑呼出」阈值。
     * @param left 热区是否在屏幕左侧（决定"向内"的方向）
     * @param dx/dy 相对按下点的位移（屏幕坐标，dy 向上为负）
     * @param dist 位移总长
     * @param minDistancePx 呼出的最小位移（px）；0 = 只按 touchSlop 判
     */
    fun shouldActivate(
        left: Boolean,
        touchSlop: Float,
        dx: Float,
        dy: Float,
        dist: Float,
        minDistancePx: Float = 0f,
    ): Boolean {
        val inwardDx = if (left) dx else -dx
        val upEnough = dy <= -MIN_UP_SLOP_RATIO * touchSlop
        val inwardOk = inwardDx >= -MAX_INWARD_SLOP_RATIO * touchSlop
        val vertical = abs(dy) >= MIN_VERTICAL_RATIO * abs(dx)
        val minDist = maxOf(MIN_DISTANCE_SLOP_RATIO * touchSlop, minDistancePx)
        return dist >= minDist && upEnough && inwardOk && vertical
    }
}
