package com.pilot.wheel.smartlink

import com.pilot.wheel.smartlink.rule.LinkRule
import com.pilot.wheel.smartlink.rule.RuleValidator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导入校验语义锁定：整批拒绝（不是逐条丢弃）+ 明确的字段级报错。
 */
class RuleValidatorTest {

    private fun rule(
        id: String = "r1",
        pkg: String = "com.xingin.xhs",
        name: String = "小红书",
        domains: List<String> = listOf("xiaohongshu.com"),
        regex: String? = null,
        priority: Int = 100,
    ) = LinkRule(
        ruleId = id,
        domainMatches = domains,
        urlRegex = regex,
        displayName = name,
        targetPackage = pkg,
        priority = priority,
    )

    private fun issues(rules: List<LinkRule>) = RuleValidator.validate(rules).issues

    @Test
    fun validRulePasses() {
        assertTrue(RuleValidator.validate(listOf(rule())).ok)
    }

    @Test
    fun emptyListRejected() {
        assertFalse(RuleValidator.validate(emptyList()).ok)
    }

    @Test
    fun blankRuleIdRejected() {
        val r = issues(listOf(rule(id = "")))
        assertTrue(r.any { it.field == "ruleId" })
    }

    @Test
    fun duplicateRuleIdRejected() {
        val r = issues(listOf(rule(id = "dup"), rule(id = "dup", pkg = "com.zhihu.android")))
        assertTrue(r.any { it.field == "ruleId" && it.message.contains("重复") })
    }

    @Test
    fun blankDisplayNameRejected() {
        val r = issues(listOf(rule(name = "")))
        assertTrue(r.any { it.field == "displayName" })
    }

    @Test
    fun illegalPackageRejected() {
        listOf("xiaohongshu", "com..x", "com.123", "").forEach { bad ->
            val r = issues(listOf(rule(pkg = bad)))
            assertTrue("应拒绝包名 $bad", r.any { it.field == "targetPackage" })
        }
    }

    @Test
    fun needsDomainOrRegex() {
        val r = issues(listOf(rule(domains = emptyList())))
        assertTrue(r.any { it.field == "domainMatches" })
        // 有正则即可，不需要域名
        assertTrue(RuleValidator.validate(listOf(rule(domains = emptyList(), regex = "^https?://x\\.com/"))).ok)
    }

    @Test
    fun domainWithSchemeOrPathRejected() {
        assertTrue(issues(listOf(rule(domains = listOf("https://xiaohongshu.com")))).any { it.field == "domainMatches" })
        assertTrue(issues(listOf(rule(domains = listOf("xiaohongshu.com/explore")))).any { it.field == "domainMatches" })
    }

    @Test
    fun wildcardDomainRejected() {
        val r = issues(listOf(rule(domains = listOf("*.xiaohongshu.com"))))
        assertTrue(r.any { it.message.contains("通配符") })
    }

    @Test
    fun leadingDotRejected() {
        assertTrue(issues(listOf(rule(domains = listOf(".xiaohongshu.com")))).any { it.field == "domainMatches" })
    }

    @Test
    fun invalidRegexRejected() {
        val r = issues(listOf(rule(regex = "([unclosed")))
        assertTrue(r.any { it.field == "urlRegex" })
    }

    @Test
    fun priorityOutOfRangeRejected() {
        assertTrue(issues(listOf(rule(priority = 9999))).any { it.field == "priority" })
        assertTrue(issues(listOf(rule(priority = -9999))).any { it.field == "priority" })
    }

    @Test
    fun tooManyRulesRejected() {
        val many = (1..RuleValidator.MAX_RULES + 1).map { rule(id = "r$it") }
        val r = issues(many)
        assertTrue(r.any { it.field == "rules" && it.message.contains("上限") })
    }

    @Test
    fun summaryLimitedToThreeItems() {
        val bad = (1..5).map { rule(id = "r$it", pkg = "bad") }
        val report = RuleValidator.validate(bad)
        assertFalse(report.ok)
        assertTrue(report.summary().contains("等"))
    }
}
