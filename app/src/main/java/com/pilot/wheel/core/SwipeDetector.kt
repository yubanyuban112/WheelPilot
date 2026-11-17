package com.pilot.wheel.core

import kotlin.math.abs

/**
 * 角落上滑呼出判定（纯函数，可单测）。
 * 阈值取自系统 touchSlop，
 * 而不是热区尺寸——从触发区划出时只要达到很短的上滑距离就会激活。
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
     */
    fun shouldActivate(left: Boolean, touchSlop: Float, dx: Float, dy: Float, dist: Float): Boolean {
        val inwardDx = if (left) dx else -dx
        val upEnough = dy <= -MIN_UP_SLOP_RATIO * touchSlop
        val inwardOk = inwardDx >= -MAX_INWARD_SLOP_RATIO * touchSlop
        val vertical = abs(dy) >= MIN_VERTICAL_RATIO * abs(dx)
        return dist >= MIN_DISTANCE_SLOP_RATIO * touchSlop && upEnough && inwardOk && vertical
    }
}
