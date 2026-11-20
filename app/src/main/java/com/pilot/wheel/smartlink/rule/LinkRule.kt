package com.pilot.wheel.smartlink.rule

import org.json.JSONArray
import org.json.JSONObject

/**
 * 链接匹配规则。业务代码不硬编码任何宿主/目标 App，所有包名/域名只作为
 * assets 默认规则或用户配置存在，新增目标只需加一条 JSON 记录。
 */
data class LinkRule(
    val ruleId: String,
    val domainMatches: List<String>,
    val urlRegex: String? = null,
    val displayName: String,
    val targetPackage: String,
    val targetActivity: String? = null,
    val deepLinkScheme: String? = null,
    val urlTransform: String? = null,
    val iconSource: IconSource = IconSource.PACKAGE,
    val customIcon: String? = null,
    val priority: Int = 0,
    val enabled: Boolean = true,
) {
    enum class IconSource { PACKAGE, CUSTOM }

    fun toJson(): JSONObject = JSONObject().apply {
        put("ruleId", ruleId)
        put("domainMatches", JSONArray().apply { domainMatches.forEach { put(it) } })
        urlRegex?.let { put("urlRegex", it) }
        put("displayName", displayName)
        put("targetPackage", targetPackage)
        targetActivity?.let { put("targetActivity", it) }
        deepLinkScheme?.let { put("deepLinkScheme", it) }
        urlTransform?.let { put("urlTransform", it) }
        put("iconSource", iconSource.name)
        customIcon?.let { put("customIcon", it) }
        put("priority", priority)
        put("enabled", enabled)
    }

    companion object {
        fun fromJson(o: JSONObject): LinkRule = LinkRule(
            ruleId = o.getString("ruleId"),
            domainMatches = o.optJSONArray("domainMatches")?.let { a ->
                (0 until a.length()).map { a.getString(it) }
            } ?: emptyList(),
            urlRegex = o.optString("urlRegex").takeIf { it.isNotBlank() },
            displayName = o.getString("displayName"),
            targetPackage = o.getString("targetPackage"),
            targetActivity = o.optString("targetActivity").takeIf { it.isNotBlank() },
            deepLinkScheme = o.optString("deepLinkScheme").takeIf { it.isNotBlank() },
            urlTransform = o.optString("urlTransform").takeIf { it.isNotBlank() },
            iconSource = runCatching { IconSource.valueOf(o.optString("iconSource", "PACKAGE")) }
                .getOrDefault(IconSource.PACKAGE),
            customIcon = o.optString("customIcon").takeIf { it.isNotBlank() },
            priority = o.optInt("priority", 0),
            enabled = o.optBoolean("enabled", true),
        )
    }
}
