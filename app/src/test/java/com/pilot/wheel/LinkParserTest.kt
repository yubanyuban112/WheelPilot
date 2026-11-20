package com.pilot.wheel

import com.pilot.wheel.core.LinkParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 剪贴板链接提取：不能吞掉中文标点，不能把普通文字当链接 */
class LinkParserTest {

    @Test
    fun 提取标准链接() {
        assertEquals("https://example.com/a?b=1", LinkParser.firstUrl("看这个 https://example.com/a?b=1 很好"))
    }

    @Test
    fun 中文句号处截断() {
        assertEquals("https://x.cn/p", LinkParser.firstUrl("复制链接 https://x.cn/p。下一句"))
    }

    @Test
    fun 括号包裹时去掉尾部括号() {
        assertEquals("https://x.cn/p", LinkParser.firstUrl("（https://x.cn/p）"))
    }

    @Test
    fun 尾部英文句点被去掉() {
        assertEquals("https://a.com/x", LinkParser.firstUrl("see https://a.com/x."))
    }

    @Test
    fun 无协议头自动补https() {
        assertEquals("https://www.bilibili.com/video/BV1xx", LinkParser.firstUrl("www.bilibili.com/video/BV1xx"))
    }

    @Test
    fun 普通文字返回空() {
        assertNull(LinkParser.firstUrl("这是一段没有链接的文字"))
        assertNull(LinkParser.firstUrl(""))
        assertNull(LinkParser.firstUrl("   "))
        assertNull(LinkParser.firstUrl(null))
    }

    @Test
    fun 过短的假链接被丢弃() {
        assertNull(LinkParser.firstUrl("https://a"))
    }

    @Test
    fun 多行文本取第一个链接() {
        val text = "第一行无关\n第二行 https://a.com/1\n第三行 https://b.com/2"
        assertEquals("https://a.com/1", LinkParser.firstUrl(text))
    }
}
