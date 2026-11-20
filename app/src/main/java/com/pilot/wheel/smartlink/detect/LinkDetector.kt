package com.pilot.wheel.smartlink.detect

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import java.util.regex.Pattern

/**
 * 链接检测：节点树提取 + 分享文本正则，归一输出 [DetectedLink]。
 *
 * 不保证拿到完整 URL，[DetectedLink.isValid] 为 false 时由上层决定是否降级提示。
 * 剪贴板读取（Android 13+ 会触发系统「读取剪贴板」提示）由编排层决定时机，
 * 本类只负责对已读到的文本做提取，不主动碰 ClipboardManager。
 */
class LinkDetector {

    private val urlPattern: Pattern = Pattern.compile(
        "(?i)\\b(?:https?://|www\\.)[^\\s<>\"']+",
    )

    @Volatile private var currentPackage: String? = null
    @Volatile private var lastHandledUrl: String? = null

    /** 前台宿主切换：重置会话、清理去重状态 */
    fun onForegroundChanged(pkg: String?) {
        currentPackage = pkg
        lastHandledUrl = null
    }

    /** 节点树提取：BFS 收集 text / contentDescription，限深度与数量 */
    fun extractFromNode(root: AccessibilityNodeInfo?, sourcePkg: String): DetectedLink? {
        root ?: return null
        currentPackage = sourcePkg
        val sb = StringBuilder()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        var depth = 0
        var visited = 0
        while (queue.isNotEmpty() && depth < MAX_DEPTH && visited < MAX_NODES) {
            repeat(queue.size) {
                val node = queue.removeFirstOrNull() ?: return@repeat
                visited++
                node.text?.toString()?.let { sb.append(it).append(' ') }
                node.contentDescription?.toString()?.let { sb.append(it).append(' ') }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.addLast(it) }
                }
            }
            depth++
        }
        val text = sb.toString()
        val url = extractUrl(text)
        // 诊断：text 非空但没 URL 时，打印节点树收集到的文本片段，便于判断微信里链接以何种形态暴露
        if (url == null && text.isNotBlank()) {
            Log.w("WheelPilot", "smartlink: node text len=${text.length} head=${text.take(160)}")
        }
        return build(text, url, sourcePkg, DetectedLink.Channel.NODE_TREE)
    }

    /** 分享/已读文本正则提取 */
    fun extractFromText(text: String, sourcePkg: String): DetectedLink? {
        currentPackage = sourcePkg
        return build(text, extractUrl(text), sourcePkg, DetectedLink.Channel.SHARE_TEXT)
    }

    /** 剪贴板兜底：编排层已读到剪贴板文本后调用 */
    fun extractFromClipboardText(text: String, sourcePkg: String): DetectedLink? {
        currentPackage = sourcePkg
        return build(text, extractUrl(text), sourcePkg, DetectedLink.Channel.CLIPBOARD)
    }

    /** 是否应处理：去重（同一 URL 不重复弹）+ 有效性 */
    fun shouldHandle(link: DetectedLink): Boolean {
        val u = link.url ?: return false
        return u != lastHandledUrl
    }

    fun markHandled(link: DetectedLink) {
        lastHandledUrl = link.url
    }

    fun clear() {
        currentPackage = null
        lastHandledUrl = null
    }

    private fun extractUrl(text: String): String? {
        if (text.isBlank()) return null
        val m = urlPattern.matcher(text)
        if (m.find()) return normalize(m.group())
        return null
    }

    /** 归一化：补全 www. 前缀缺 http 的情况，去尾部标点 */
    private fun normalize(raw: String): String {
        var s = raw.trim().trimEnd('.', ',', ';', '!', '?', ')', ']', '}')
        if (s.startsWith("www.", ignoreCase = true) && !s.lowercase().startsWith("http")) {
            s = "https://$s"
        }
        return s
    }

    private fun build(
        text: String,
        url: String?,
        pkg: String,
        ch: DetectedLink.Channel,
    ): DetectedLink? {
        if (url == null) return null
        val conf = when (ch) {
            DetectedLink.Channel.NODE_TREE -> 0.7f
            DetectedLink.Channel.SHARE_TEXT -> 0.8f
            DetectedLink.Channel.CLIPBOARD -> 0.5f
            // 57 号：整页扫描由 ScreenLinkScanner 直接构造 DetectedLink，不走这里
            DetectedLink.Channel.SCREEN_SCAN -> 0.65f
        }
        return DetectedLink(text, url, pkg, confidence = conf, channel = ch)
    }

    companion object {
        private const val MAX_DEPTH = 12
        private const val MAX_NODES = 400
    }
}
