package com.pilot.wheel.core

import kotlin.math.abs
import kotlin.math.hypot

/**
 * 热区命中判定（纯函数，可单测）。
 *
 * 这是「热区感知与窗口触摸接收解耦」方案的用户态核心：Shizuku 旁路读到的
 * 原始触摸坐标在这里被归一化、按屏幕方向旋转、再判定是否落在热区 / 小窗矩形外。
 * 判定结果只用来触发「呼出 / 关闭」，绝不消费系统触摸 —— 事件照常发给下层应用。
 *
 * 坐标约定：
 *  - [RawTouchPoint.x]/[y] 是触摸屏原始值（范围由设备 ABS_MT_POSITION_X/Y 的 max 定义）；
 *  - 归一化到 0..1 后按 [rotation] 旋转到「当前屏幕逻辑坐标系」（左上原点，x 向右，y 向下）。
 */
object HotspotHitRules {

    /** 热区默认范围约束（dp），与 Prefs 的滑杆边界一致：克制，避免与全面屏手势大面积冲突 */
    const val HOTZONE_MIN_DP = 24f
    const val HOTZONE_MAX_DP = 160f

    /** 触摸屏原始坐标（未归一化），来自 getevent 的 ABS_MT_POSITION_X/Y */
    data class RawTouchPoint(val x: Float, val y: Float)

    /** 归一化后的逻辑屏幕坐标（px，左上原点） */
    data class ScreenPoint(val x: Float, val y: Float)

    /**
     * 归一化 + 方向旋转：原始值 → 屏幕逻辑坐标。
     *
     * @param touchXMax/touchYMax 设备 ABS_MT_POSITION_X/Y 的 max（由 getevent -lp 解析得到）
     * @param screenW/screenH 当前屏幕物理分辨率（px）
     * @param rotation 屏幕旋转角：0/90/180/270
     *
     * 注意：触摸设备坐标通常固定在「自然方向」，屏幕旋转后必须做旋转映射，
     * 否则横屏时热区判定会整体错位。旋转矩阵与 android.view.Surface 的 ROTATION_* 对齐。
     */
    fun normalize(
        raw: RawTouchPoint,
        touchXMax: Float,
        touchYMax: Float,
        screenW: Int,
        screenH: Int,
        rotation: Int,
    ): ScreenPoint {
        if (touchXMax <= 0f || touchYMax <= 0f) {
            // max 未探测到：无法归一化。退回「假设原始坐标就是屏幕逻辑像素」，
            // 直接透传（多数设备在 max 缺失时 raw 值恰好等于屏幕坐标，退化但不崩）。
            return ScreenPoint(raw.x, raw.y)
        }
        val nx = (raw.x / touchXMax).coerceIn(0f, 1f)
        val ny = (raw.y / touchYMax).coerceIn(0f, 1f)
        return when (rotation) {
            90 -> ScreenPoint((1f - ny) * screenW, nx * screenH)
            180 -> ScreenPoint((1f - nx) * screenW, (1f - ny) * screenH)
            270 -> ScreenPoint(ny * screenW, (1f - nx) * screenH)
            else -> ScreenPoint(nx * screenW, ny * screenH) // 0 及未知角都按自然方向
        }
    }

    /**
     * 触点是否落在底部热区（底角优先，可扩展到整条底边）。
     *
     * @param p 归一化后的屏幕坐标
     * @param hotWidthDp/hotHeightDp 热区尺寸（dp）
     * @param hotInsetDp 横向内缩（dp）
     * @param hotBottomDp 热区底边离屏幕底的距离（dp）
     * @param density 屏幕密度
     * @param screenW/screenH 屏幕分辨率
     * @param fullEdge true = 整条底边都算热区（更激进）；false = 只认左右底角（推荐，克制）
     *
     * 命中语义：默认只认「左下/右下角」，与参考实现的底角把手对齐，
     * 避免整条底边热区与系统「上滑返回桌面」手势大面积冲突。
     */
    fun inHotZone(
        p: ScreenPoint,
        hotWidthDp: Float,
        hotHeightDp: Float,
        hotInsetDp: Float,
        hotBottomDp: Float,
        density: Float,
        screenW: Int,
        screenH: Int,
        fullEdge: Boolean = false,
    ): Boolean {
        val wPx = hotWidthDp * density
        val hPx = hotHeightDp * density
        val insetPx = hotInsetDp * density
        val bottomPx = hotBottomDp * density
        // 热区在屏幕上的矩形：顶 = 屏底 - 底边距 - 高，底 = 屏底 - 底边距
        val top = screenH - bottomPx - hPx
        val bottom = screenH - bottomPx
        if (p.y < top || p.y > bottom) return false

        if (fullEdge) return true

        // 左下角 / 右下角：x 落在 [inset, inset+w] 或 [screenW-inset-w, screenW-inset]
        val inLeft = p.x >= insetPx && p.x <= insetPx + wPx
        val inRight = p.x >= screenW - insetPx - wPx && p.x <= screenW - insetPx
        return inLeft || inRight
    }

    /**
     * 触点是否落在 [rect]（小窗矩形，屏幕坐标）之外。
     * 用于「点击小窗外侧关闭小窗但不拦截下层」：判定为 true 时收起小窗，
     * 同时因为窗口不可触摸，这次点击照常传给下层应用。
     */
    fun outsideRect(p: ScreenPoint, rect: WindowRect): Boolean {
        if (rect.width <= 0 || rect.height <= 0) return false
        return p.x < rect.left || p.x > rect.right || p.y < rect.top || p.y > rect.bottom
    }

    /**
     * 上滑呼出判定：把归一化后的位移喂给既有的 [SwipeDetector.shouldActivate]。
     *
     * @param down 按下点（屏幕坐标）
     * @param current 当前点（屏幕坐标）
     * @param left 热区在屏幕左侧（决定「向内」方向）
     * @param touchSlop 系统 touch slop（px）
     * @param minDistancePx 呼出最小位移（px）
     */
    fun shouldActivate(
        down: ScreenPoint,
        current: ScreenPoint,
        left: Boolean,
        touchSlop: Float,
        minDistancePx: Float,
    ): Boolean {
        val dx = current.x - down.x
        val dy = current.y - down.y
        val dist = hypot(dx, dy)
        return SwipeDetector.shouldActivate(left, touchSlop, dx, dy, dist, minDistancePx)
    }

    /** 位移是否仍在「点击容差」内（未构成滑动） */
    fun withinTapTolerance(down: ScreenPoint, current: ScreenPoint, tolerancePx: Float): Boolean {
        return hypot(current.x - down.x, current.y - down.y) <= tolerancePx
    }

    /** 位移是否达到「滑出」阈值（用于区分原地点击与滑出意图） */
    fun beyondSlideThreshold(down: ScreenPoint, current: ScreenPoint, thresholdPx: Float): Boolean {
        return hypot(current.x - down.x, current.y - down.y) >= thresholdPx
    }

    /** 归一化后的方向角是否接近垂直（用于上滑判定前的快速排除，非必需，保留可测） */
    fun isVerticalMotion(down: ScreenPoint, current: ScreenPoint): Boolean {
        val dy = abs(current.y - down.y)
        val dx = abs(current.x - down.x)
        return dy >= 0.9f * (dx + dy)
    }
}
