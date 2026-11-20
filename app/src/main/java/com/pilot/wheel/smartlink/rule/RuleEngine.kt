package com.pilot.wheel.smartlink.rule

/**
 * 规则匹配引擎：一链接命中多规则时按 priority（降序）+ enabled 过滤选最优。
 * 具体匹配语义见 [RuleMatcher]（host 后缀匹配，正则优先）。
 */
class RuleEngine(private val repository: RuleRepository) {

    fun match(url: String): LinkRule? = pick(repository.getAll(), url)

    fun matches(rule: LinkRule, url: String): Boolean = RuleMatcher.matches(rule, url)

    companion object {
        /** 纯函数入口：给定规则表与 URL 选出最优规则，便于单测复用 */
        fun pick(rules: List<LinkRule>, url: String): LinkRule? {
            if (url.isBlank()) return null
            return rules.asSequence()
                .filter { it.enabled }
                .filter { RuleMatcher.matches(it, url) }
                .maxByOrNull { it.priority }
        }
    }
}
