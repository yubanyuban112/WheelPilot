package com.pilot.wheel.core

/**
 * 41B：freeform 小窗几何（纯函数，无 Android 依赖，可单测）。
 *
 * 双窗共存场景下的四个几何问题都在这里：
 * - [nextBounds]：新小窗的默认矩形（70% 宽 × 60% 高、居中略偏上），
 *   第 n 个已开窗口之后的新窗按 (n*40px, n*40px) 错开，clamp 回屏内；
 * - [dragResizeBounds]：底角把手按角缩放（对角锚定），最小尺寸与屏幕边界双 clamp；
 * - [ballRestoreBounds]：悬浮球恢复时把旧矩形 clamp 回屏内（偏出则贴边）；
 * - [shouldCollapseToBall]：把手拖过屏幕底缘下方 [COLLAPSE_MARGIN_PX] → 收球。
 */
object FreeformLayout {

    /** 新窗默认宽占屏宽比例 */
    const val WIDTH_FACTOR = 0.70f

    /** 新窗默认高占屏高比例 */
    const val HEIGHT_FACTOR = 0.60f

    /** 默认位置整体上移占屏高比例（「居中略偏上」） */
    const val TOP_UP_FACTOR = 0.05f

    /** 多窗错位步长（px） */
    const val STAGGER_PX = 40

    /** 收球判定：拖拽点越过屏幕底缘的余量（px） */
    const val COLLAPSE_MARGIN_PX = 64

    /** 把手缩放的最小窗口尺寸（px） */
    const val MIN_WIDTH = 340
    const val MIN_HEIGHT = 420

    /** 缩放角：BR=右下 BL=左下 TR=右上 TL=左上；对角为锚定点 */
    enum class Corner { BR, BL, TR, TL }

    /**
     * 新小窗的默认 bounds。
     *
     * [base] 给定时沿用其宽高与位置（与 WindowGeometry 的厂商补偿结果衔接），
     * 只叠加第 [openCount] 个窗口的错位；为 null 时用 70%×60% 居中略偏上的默认值。
     * 错位后任何一条边出屏都整体平移 clamp 回屏内。
     */
    fun nextBounds(screenW: Int, screenH: Int, openCount: Int, base: WindowRect?): WindowRect {
        val w = base?.width ?: (screenW * WIDTH_FACTOR).toInt()
        val h = base?.height ?: (screenH * HEIGHT_FACTOR).toInt()
        val left0 = base?.left ?: ((screenW - w) / 2)
        val top0 = base?.top ?: (((screenH - h) / 2) - (screenH * TOP_UP_FACTOR).toInt())
        val stagger = openCount.coerceAtLeast(0) * STAGGER_PX
        var left = left0 + stagger
        var top = top0 + stagger
        // clamp：保持宽高，整体平移回屏内
        left = left.coerceIn(0, (screenW - w).coerceAtLeast(0))
        top = top.coerceIn(0, (screenH - h).coerceAtLeast(0))
        return WindowRect(left, top, left + w, top + h)
    }

    /**
     * 按角缩放：[corner] 是被拖的角，其对角锚定不动。
     * [dx]/[dy] 为拖动位移，clamp 两层：屏幕边界、[minW]×[minH] 最小尺寸。
     * 宽高小于最小值时以锚定角为基准向外顶回（不是整体平移，锚点绝不移动）。
     */
    fun dragResizeBounds(
        start: WindowRect,
        corner: Corner,
        dx: Int,
        dy: Int,
        screenW: Int,
        screenH: Int,
        minW: Int = MIN_WIDTH,
        minH: Int = MIN_HEIGHT,
    ): WindowRect {
        val anchoredLeft = corner == Corner.BR || corner == Corner.TR
        val anchoredTop = corner == Corner.BR || corner == Corner.BL
        var left = if (anchoredLeft) start.left else start.left + dx
        var top = if (anchoredTop) start.top else start.top + dy
        var right = if (anchoredLeft) start.right + dx else start.right
        var bottom = if (anchoredTop) start.bottom + dy else start.bottom
        // 屏幕边界 clamp（锚定边不动）
        left = left.coerceIn(0, screenW)
        right = right.coerceIn(0, screenW)
        top = top.coerceIn(0, screenH)
        bottom = bottom.coerceIn(0, screenH)
        // 最小尺寸 clamp：以锚定边为基准把活动边顶回
        if (right - left < minW) {
            if (anchoredLeft) right = left + minW else left = right - minW
        }
        if (bottom - top < minH) {
            if (anchoredTop) bottom = top + minH else top = bottom - minH
        }
        return WindowRect(left, top, right.coerceAtLeast(left), bottom.coerceAtLeast(top))
    }

    /**
     * 悬浮球恢复时的窗口矩形：clamp 回屏内，整体偏出则贴边。
     * 宽高超出屏幕时也贴边收窄（不留屏外部分）。
     */
    fun ballRestoreBounds(prev: WindowRect, screenW: Int, screenH: Int): WindowRect {
        val w = prev.width.coerceAtMost(screenW)
        val h = prev.height.coerceAtMost(screenH)
        var left = prev.left
        var top = prev.top
        if (left < 0) left = 0
        if (left + w > screenW) left = screenW - w
        if (top < 0) top = 0
        if (top + h > screenH) top = screenH - h
        return WindowRect(left, top, left + w, top + h)
    }

    /** 把手拖拽点越过屏幕底缘下方 [COLLAPSE_MARGIN_PX] → 收球 */
    fun shouldCollapseToBall(dragY: Float, screenH: Int): Boolean =
        dragY > screenH + COLLAPSE_MARGIN_PX
}
