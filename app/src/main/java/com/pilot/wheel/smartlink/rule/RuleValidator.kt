package com.pilot.wheel.smartlink.rule

/**
 * 导入规则校验：纯函数、无 Android 依赖，便于 JVM 单测。
 *
 * 定位：导入是「外部可变输入」，必须在落库**之前**整体拒绝而不是逐条丢弃——
 * 一条坏规则静默消失，用户会以为导入成功但结果不对。
 */
object RuleValidator {

    /** 单次导入上限，防误导入巨型文件拖慢匹配 */
    const val MAX_RULES = 200

    /** 每段必须以字母或下划线开头（`com.123` 不是合法包名） */
    private val PACKAGE_NAME = Regex("^[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)+$")
    private val DOMAIN = Regex(
        "^[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?(\\.[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?)+$",
    )
    private const val PRIORITY_MIN = -1000
    private const val PRIORITY_MAX = 1000

    data class Issue(val ruleId: String, val field: String, val message: String) {
        override fun toString(): String = "[$ruleId] $field: $message"
    }

    data class Report(val issues: List<Issue>) {
        val ok: Boolean get() = issues.isEmpty()
        /** 给用户看的一句话（最多列 3 条） */
        fun summary(): String = issues.take(3).joinToString("；") { it.toString() } +
            if (issues.size > 3) " 等 ${issues.size} 处" else ""
    }

    fun validate(rules: List<LinkRule>): Report {
        val issues = mutableListOf<Issue>()
        if (rules.isEmpty()) {
            issues += Issue("-", "rules", "没有任何规则")
            return Report(issues)
        }
        if (rules.size > MAX_RULES) {
            issues += Issue("-", "rules", "超过 $MAX_RULES 条上限（实际 ${rules.size} 条）")
        }
        val seen = HashSet<String>()
        rules.forEachIndexed { index, r ->
            val id = r.ruleId.ifBlank { "#${index + 1}" }
            if (r.ruleId.isBlank()) {
                issues += Issue(id, "ruleId", "不能为空")
            } else if (!seen.add(r.ruleId)) {
                issues += Issue(id, "ruleId", "同一文件内重复")
            }
            if (r.displayName.isBlank()) issues += Issue(id, "displayName", "不能为空")
            if (!PACKAGE_NAME.matches(r.targetPackage)) {
                issues += Issue(id, "targetPackage", "不是合法包名：${r.targetPackage.ifBlank { "空" }}")
            }
            if (r.domainMatches.isEmpty() && r.urlRegex.isNullOrBlank()) {
                issues += Issue(id, "domainMatches", "域名与 urlRegex 至少要有一个")
            }
            r.domainMatches.forEach { d ->
                when {
                    d.isBlank() -> issues += Issue(id, "domainMatches", "含空域名")
                    d.contains("://") || d.contains('/') ->
                        issues += Issue(id, "domainMatches", "只写域名，不要带协议或路径：$d")
                    d.startsWith(".") || d.endsWith(".") ->
                        issues += Issue(id, "domainMatches", "域名首尾不能有点：$d")
                    d.startsWith("*") || d.contains('*') ->
                        issues += Issue(id, "domainMatches", "不支持通配符，子域自动匹配：$d")
                    !DOMAIN.matches(d) -> issues += Issue(id, "domainMatches", "域名格式不合法：$d")
                }
            }
            r.urlRegex?.takeIf { it.isNotBlank() }?.let { re ->
                runCatching { Regex(re, RegexOption.IGNORE_CASE) }.onFailure {
                    issues += Issue(id, "urlRegex", "正则无法编译：${it.message}")
                }
            }
            if (r.priority < PRIORITY_MIN || r.priority > PRIORITY_MAX) {
                issues += Issue(id, "priority", "超出 $PRIORITY_MIN..$PRIORITY_MAX")
            }
        }
        return Report(issues)
    }
}
