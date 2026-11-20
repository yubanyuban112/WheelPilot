package com.pilot.wheel.core

/**
 * 从一段文本里提取第一个链接（纯函数，可单测）。
 *
 * 用在「复制链接」场景：Android 10 起非前台应用读剪贴板会拿到 null，
 * 所以这条链路不是自动的 —— 用户从工具面板点「打开链接」时本应用在前台，
 * 才读得到内容。这里只负责把内容里的 URL 抠出来。
 */
object LinkParser {

    /** 中文标点必须一起排除，否则"看这个链接。下一句"会把整句都吞进去 */
    private val URL = Regex(
        """(https?://|www\.)[^\s"'<>，。、；：！？（）【】《》「」]+""",
        RegexOption.IGNORE_CASE,
    )

    private val TRAILING = charArrayOf('.', ',', ')', ']', '}', '>', ';', '!', '?')

    fun firstUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val match = URL.find(text) ?: return null
        var raw = match.value.trimEnd(*TRAILING)
        if (raw.startsWith("www.", ignoreCase = true)) raw = "https://$raw"
        // 至少要有 "https://" + 一个域名，太短的当噪音丢掉
        return raw.takeIf { it.length > "https://a.co".length - 1 }
    }
}
