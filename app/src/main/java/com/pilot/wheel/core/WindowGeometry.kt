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

    /**
     * 需要完整交互的场景（分享、登录授权）用的几何。
     *
     * 原生小窗只有屏高 58%，微信聊天页在这个高度下会把顶部消息和底部输入框裁掉 ——
     * 录屏里「选完联系人后聊天气泡被切一半、输入框只剩一条缝」就是这个原因。
     * 这类场景窗口必须接近全屏可用区，两侧留边即可保持浮窗形态。
     */
    const val CONTENT_WIDTH_FACTOR = 0.88f
    const val CONTENT_HEIGHT_FACTOR = 0.80f
    const val CONTENT_TOP_FACTOR = 0.10f

    /** 由屏幕尺寸计算小窗目标矩形；非法尺寸返回 null */
    fun freeformBounds(screenW: Int, screenH: Int, scalePercent: Int = 100): WindowRect? {
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
        val scaled = applyVendorScale(
            width = width,
            height = height,
            top = (screenH * topFactor).toInt(),
            screenW = screenW,
            screenH = screenH,
            scalePercent = scalePercent,
        )
        return scaled
    }

    /** 完整交互型浮窗：接近全屏可用区，避免聊天/授权页内容被裁切 */
    fun contentBounds(screenW: Int, screenH: Int, scalePercent: Int = 100): WindowRect? {
        if (screenW <= 0 || screenH <= 0) return null
        val width = (screenW * CONTENT_WIDTH_FACTOR).toInt()
        val height = (screenH * CONTENT_HEIGHT_FACTOR).toInt()
        return applyVendorScale(
            width = width,
            height = height,
            top = (screenH * CONTENT_TOP_FACTOR).toInt(),
            screenW = screenW,
            screenH = screenH,
            scalePercent = scalePercent,
        )
    }

    /**
     * 厂商缩放补偿（A4）。
     *
     * 背景：多个 ROM 会把应用请求的 freeform 尺寸再打一次折 —— MagicOS 实测
     * 请求 74% 屏宽只渲染出 60%，等于乘 0.81。请求多少就渲染多少的前提不成立，
     * 于是「设成 83% 宽」在真机上永远偏小。
     *
     * 做法：把目标尺寸乘一个按机型给的系数（见 [VendorScale]），再夹回屏幕内 ——
     * 宁可退化成满宽，也不要让窗口跑到屏幕外。系数本身可被用户标定（设置页滑杆），
     * 因为各家 ROM 的折算比例只有真机能量准。
     */
    private fun applyVendorScale(
        width: Int,
        height: Int,
        top: Int,
        screenW: Int,
        screenH: Int,
        scalePercent: Int,
    ): WindowRect {
        val factor = scalePercent.coerceIn(70, 160) / 100f
        val maxW = screenW - (screenW * EDGE_MARGIN_FACTOR).toInt()
        val w = (width * factor).toInt().coerceAtMost(maxW)
        val maxH = screenH - top - (screenH * EDGE_MARGIN_FACTOR).toInt()
        val h = (height * factor).toInt().coerceAtMost(maxH)
        val left = ((screenW - w) / 2).coerceAtLeast(0)
        return WindowRect(left, top, left + w, top + h)
    }

    /** 补偿后仍要留的边距：满宽窗口会贴住屏幕边缘，视觉上不像浮窗 */
    private const val EDGE_MARGIN_FACTOR = 0.02f
}

/**
 * 各厂商 freeform 请求的折算比例（百分比）。
 *
 * 这些值不是猜的：MagicOS 的 74%→60% 来自 2026-11-17 真机测量；
 * 小米侧 0.7407 来自「时光」4.2 的 BASE_FREEFORM_SCALE 常量（它对 MIUI 小窗
 * 自身 0.74 缩放做反向抵消）。没有测量数据的品牌一律给 100（不补偿）。
 */
object VendorScale {

    const val HONOR = 120
    const val XIAOMI = 100
    const val DEFAULT = 100

    fun defaultPercent(manufacturer: String, brand: String): Int {
        val m = manufacturer.lowercase()
        val b = brand.lowercase()
        return when {
            m.contains("honor") || b.contains("honor") || m.contains("huawei") -> HONOR
            m.contains("xiaomi") || b.contains("xiaomi") ||
                b.contains("redmi") || b.contains("poco") -> XIAOMI
            else -> DEFAULT
        }
    }
}
