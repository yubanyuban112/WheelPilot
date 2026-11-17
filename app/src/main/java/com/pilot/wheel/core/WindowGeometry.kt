package com.pilot.wheel.core

/**
 * 小窗几何计算（纯函数，无 Android 依赖，可单测）。
 *
 * 对齐荣耀原生小窗实测几何（2026-11-17 真机测量 1272×2800）：
 * 竖屏：宽 = 屏宽×[PORTRAIT_WIDTH_FACTOR]，高 = 宽×[PORTRAIT_HEIGHT_RATIO]
 *       （上限屏高×[PORTRAIT_MAX_HEIGHT_FACTOR]），顶 = 屏高×[PORTRAIT_TOP_FACTOR]，水平居中。
 * 横屏：宽 = 短边×[LANDSCAPE_WIDTH_FACTOR]，高 = 宽×[LANDSCAPE_HEIGHT_RATIO]
 *       （上限屏高×[LANDSCAPE_MAX_HEIGHT_FACTOR]），顶 = 屏高×[LANDSCAPE_TOP_FACTOR]。
 */
object WindowGeometry {

    const val PORTRAIT_WIDTH_FACTOR = 0.83f
    const val PORTRAIT_HEIGHT_RATIO = 1.53f
    const val PORTRAIT_MAX_HEIGHT_FACTOR = 0.58f
    const val PORTRAIT_TOP_FACTOR = 0.21f

    const val LANDSCAPE_WIDTH_FACTOR = 0.58f
    const val LANDSCAPE_HEIGHT_RATIO = 0.86f
    const val LANDSCAPE_MAX_HEIGHT_FACTOR = 0.78f
    const val LANDSCAPE_TOP_FACTOR = 0.12f

    /** 由屏幕尺寸计算小窗目标矩形；非法尺寸返回 null */
    fun freeformBounds(screenW: Int, screenH: Int): WindowRect? {
        if (screenW <= 0 || screenH <= 0) return null
        val landscape = screenW > screenH
        val widthFactor = if (landscape) LANDSCAPE_WIDTH_FACTOR else PORTRAIT_WIDTH_FACTOR
        val heightRatio = if (landscape) LANDSCAPE_HEIGHT_RATIO else PORTRAIT_HEIGHT_RATIO
        val maxHeightFactor =
            if (landscape) LANDSCAPE_MAX_HEIGHT_FACTOR else PORTRAIT_MAX_HEIGHT_FACTOR
        val topFactor = if (landscape) LANDSCAPE_TOP_FACTOR else PORTRAIT_TOP_FACTOR
        var width = ((if (landscape) minOf(screenW, screenH) else screenW) * widthFactor).toInt()
        var height = (width * heightRatio).toInt()
        val maxHeight = (screenH * maxHeightFactor).toInt()
        if (height > maxHeight) {
            height = maxHeight
            width = (height / heightRatio).toInt()
        }
        val left = (screenW - width) / 2
        val top = (screenH * topFactor).toInt()
        return WindowRect(left, top, left + width, top + height)
    }
}
