package com.pilot.wheel.smartlink.detect

import com.pilot.wheel.core.WindowRect

/**
 * 统一链接检测结果模型，三个检测通道归一到这里。
 *
 * 57 号新增 [bounds]：命中的链接在屏幕上的矩形（屏幕坐标，左上原点）。
 * 有它就等于有了「这条链接在哪」，胶囊可以贴住该链接而不是固定停在左下角；
 * 来源为整页扫描（[ScreenLinkScanner]），剪贴板/分享通道恒为 null。
 */
data class DetectedLink(
    val rawText: String,
    val url: String?,
    val sourcePackage: String,
    val timestamp: Long = System.currentTimeMillis(),
    val confidence: Float,
    val channel: Channel,
    val bounds: WindowRect? = null,
) {
    enum class Channel { CLIPBOARD, NODE_TREE, SHARE_TEXT, SCREEN_SCAN }

    val isValid: Boolean get() = !url.isNullOrBlank()
    val isHighConfidence: Boolean get() = isValid && confidence >= HIGH_CONF

    companion object {
        const val HIGH_CONF = 0.6f
    }
}
