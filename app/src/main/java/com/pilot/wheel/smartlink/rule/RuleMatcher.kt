package com.pilot.wheel.smartlink.rule

/**
 * 纯函数匹配器：不依赖任何 Android 类，便于 JVM 单测。
 *
 * 匹配语义：
 *  - [LinkRule.urlRegex] 非空时**只**按正则判（与原实现一致，正则优先即覆盖域名表）；
 *  - 否则取 URL 的 host 做**后缀**匹配：`host == domain` 或 `host.endsWith(".domain")`。
 *
 * 为什么不用子串包含：短域名（`t.cn`、`3.cn`、`tb.cn`）用 contains 会误命中
 * `test.cn` / `w3.cn` / `xtb.cn`，且 `evil-xiaohongshu.com` 也会命中。后缀匹配要求
 * 命中点前是 `.` 或串首，语义上等价于"同域或子域"。
 */
object RuleMatcher {

    fun matches(rule: LinkRule, url: String): Boolean {
        if (url.isBlank()) return false
        // 1) URL 正则优先（精确）
        rule.urlRegex?.let { re ->
            return runCatching { Regex(re, RegexOption.IGNORE_CASE).containsMatchIn(url) }
                .getOrDefault(false)
        }
        if (rule.domainMatches.isEmpty()) return false
        // 2) 域名后缀匹配；解析不出 host 时退回包含（兼容非标准写法）
        val host = hostOf(url)
        if (host != null) return rule.domainMatches.any { matchesHost(host, it) }
        val lower = url.lowercase()
        return rule.domainMatches.any { lower.contains(it.lowercase()) }
    }

    /** 取域名（小写、去 scheme/端口/userinfo/路径），解析不出返回 null */
    fun hostOf(url: String): String? {
        var s = url.trim()
        if (s.isEmpty()) return null
        val scheme = s.indexOf("://")
        s = when {
            scheme >= 0 -> s.substring(scheme + 3)
            s.startsWith("//") -> s.substring(2)
            else -> s
        }
        val cut = s.indexOfFirst { it == '/' || it == '?' || it == '#' }
        if (cut >= 0) s = s.substring(0, cut)
        s = s.substringAfterLast('@')
        s = s.substringBefore(':')
        s = s.trimEnd('.').lowercase()
        if (s.isEmpty() || s.contains(' ') || !s.contains('.')) return null
        return s
    }

    /** 同域或子域：`m.tb.cn` 命中 `tb.cn`，`xtb.cn` 不命中 */
    fun matchesHost(host: String, domain: String): Boolean {
        val d = domain.trim().trimStart('.').lowercase()
        if (d.isEmpty()) return false
        return host == d || host.endsWith(".$d")
    }
}
