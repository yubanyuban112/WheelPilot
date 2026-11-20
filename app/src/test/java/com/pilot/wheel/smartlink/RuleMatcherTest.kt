package com.pilot.wheel.smartlink

import com.pilot.wheel.smartlink.rule.LinkRule
import com.pilot.wheel.smartlink.rule.RuleEngine
import com.pilot.wheel.smartlink.rule.RuleMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 规则匹配语义锁定：host 后缀（不是子串）、正则优先、priority 决胜。
 * 纯 JVM 单测，不碰 Android 类。
 */
class RuleMatcherTest {

    private fun rule(
        id: String,
        pkg: String,
        vararg domains: String,
        priority: Int = 100,
        regex: String? = null,
        enabled: Boolean = true,
    ) = LinkRule(
        ruleId = id,
        domainMatches = domains.toList(),
        urlRegex = regex,
        displayName = id,
        targetPackage = pkg,
        priority = priority,
        enabled = enabled,
    )

    // 与 assets/smartlink_rules.json 同构的关键子集
    private val xhs = rule("xhs", "com.xingin.xhs", "xiaohongshu.com", "xhslink.com", "xhs.cn")
    private val zhihu = rule("zhihu", "com.zhihu.android", "zhihu.com")
    private val douyin = rule("douyin", "com.ss.android.ugc.aweme", "douyin.com", "iesdouyin.com")
    private val bili = rule("bili", "tv.danmaku.bili", "bilibili.com", "b23.tv")
    private val weibo = rule("weibo", "com.sina.weibo", "weibo.com", "weibo.cn", "t.cn")
    private val wechat = rule("wechat", "com.tencent.mm", "mp.weixin.qq.com", "weixin.qq.com", priority = 200)
    private val qq = rule("qq", "com.tencent.mobileqq", "qm.qq.com", "url.cn", priority = 80)
    private val taobao = rule("taobao", "com.taobao.taobao", "taobao.com", "tb.cn")
    private val idlefish = rule("idlefish", "com.taobao.idlefish", "goofish.com", "2.taobao.com", priority = 130)
    private val jd = rule("jd", "com.jingdong.app.mall", "jd.com", "jd.hk", "3.cn")

    private val all = listOf(xhs, zhihu, douyin, bili, weibo, wechat, qq, taobao, idlefish, jd)

    private fun pkgOf(url: String): String? = RuleEngine.pick(all, url)?.targetPackage

    // ---------- hostOf ----------

    @Test
    fun hostOf_stripsSchemePortPathQuery() {
        assertEquals("xiaohongshu.com", RuleMatcher.hostOf("https://xiaohongshu.com/a/b?c=1"))
        assertEquals("m.tb.cn", RuleMatcher.hostOf("http://m.tb.cn:8080/x"))
        assertEquals("b23.tv", RuleMatcher.hostOf("HTTPS://B23.TV/abc"))
        assertEquals("zhihu.com", RuleMatcher.hostOf("https://user@zhihu.com/q/1"))
    }

    @Test
    fun hostOf_noSchemeStillParsed() {
        assertEquals("www.bilibili.com", RuleMatcher.hostOf("www.bilibili.com/video/BV1xx"))
        assertNull(RuleMatcher.hostOf("复制这条消息"))
        assertNull(RuleMatcher.hostOf(""))
    }

    // ---------- 后缀匹配边界 ----------

    @Test
    fun matchesHost_requiresDotBoundary() {
        assertTrue(RuleMatcher.matchesHost("m.tb.cn", "tb.cn"))
        assertTrue(RuleMatcher.matchesHost("tb.cn", "tb.cn"))
        assertFalse(RuleMatcher.matchesHost("xtb.cn", "tb.cn"))
        assertFalse(RuleMatcher.matchesHost("test.cn", "t.cn"))
        assertFalse(RuleMatcher.matchesHost("w3.cn", "3.cn"))
        assertFalse(RuleMatcher.matchesHost("evil-xiaohongshu.com", "xiaohongshu.com"))
    }

    @Test
    fun rejectsLookalikeHost() {
        assertNull(pkgOf("https://evil-xiaohongshu.com/p/1"))
        assertNull(pkgOf("https://test.cn/abc"))
        assertNull(pkgOf("https://notzhihu.com/q/1"))
    }

    // ---------- 真实分享链接形态 ----------

    @Test
    fun typicalShareLinksResolve() {
        assertEquals("com.xingin.xhs", pkgOf("https://xhslink.com/a/AbCdEf"))
        assertEquals("com.xingin.xhs", pkgOf("https://www.xiaohongshu.com/discovery/item/123"))
        assertEquals("com.zhihu.android", pkgOf("https://zhuanlan.zhihu.com/p/123456"))
        assertEquals("com.zhihu.android", pkgOf("https://www.zhihu.com/question/1/answer/2"))
        assertEquals("com.ss.android.ugc.aweme", pkgOf("https://v.douyin.com/iRxAbcd/"))
        assertEquals("tv.danmaku.bili", pkgOf("https://b23.tv/BV1xx411c7mD"))
        assertEquals("com.sina.weibo", pkgOf("https://t.cn/A6xyz"))
        assertEquals("com.tencent.mm", pkgOf("https://mp.weixin.qq.com/s/AbCdEf"))
        assertEquals("com.jingdong.app.mall", pkgOf("https://3.cn/1AbCd-ef"))
        assertEquals("com.taobao.taobao", pkgOf("https://m.tb.cn/h.5abcdef"))
    }

    // ---------- priority 决胜 ----------

    @Test
    fun priorityWinsOnOverlap() {
        assertEquals("com.taobao.idlefish", pkgOf("https://2.taobao.com/item.htm?id=1"))
        assertEquals("com.taobao.taobao", pkgOf("https://item.taobao.com/item.htm?id=1"))
        // 微信域名同时被 weixin.qq.com 覆盖，但 priority 200 已足够；QQ 不写 qq.com 全域
        assertEquals("com.tencent.mm", pkgOf("https://mp.weixin.qq.com/s/x"))
    }

    @Test
    fun disabledRuleNeverMatches() {
        val off = listOf(xhs.copy(enabled = false))
        assertNull(RuleEngine.pick(off, "https://xhslink.com/a/1"))
    }

    // ---------- 正则优先语义 ----------

    @Test
    fun urlRegexOverridesDomainList() {
        val r = rule("r", "p", "zhihu.com", regex = "^https?://zhuanlan\\.zhihu\\.com/")
        assertTrue(RuleMatcher.matches(r, "https://zhuanlan.zhihu.com/p/1"))
        // 正则不命中时，域名表也不参与（原语义，勿静默改成 OR）
        assertFalse(RuleMatcher.matches(r, "https://www.zhihu.com/question/1"))
    }

    @Test
    fun invalidRegexDoesNotCrash() {
        val r = rule("bad", "p", "x.com", regex = "([unclosed")
        assertFalse(RuleMatcher.matches(r, "https://x.com/a"))
    }
}
