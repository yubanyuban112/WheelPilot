package com.pilot.wheel

import org.junit.Assert.assertEquals
import org.junit.Test

/** 拼音首字母分组（LetterUtil）行为基线测试 */
class LetterUtilTest {

    @Test
    fun `ascii letters normalized to uppercase`() {
        assertEquals("A", LetterUtil.firstLetter("Alpha"))
        assertEquals("Z", LetterUtil.firstLetter("zoom"))
        assertEquals("B", LetterUtil.firstLetter("bilibili"))
    }

    @Test
    fun `non-letter ascii goes to hash`() {
        assertEquals("#", LetterUtil.firstLetter("12306"))
        assertEquals("#", LetterUtil.firstLetter("!@#"))
    }

    @Test
    fun `empty and blank go to hash`() {
        assertEquals("#", LetterUtil.firstLetter(""))
        assertEquals("#", LetterUtil.firstLetter("　")) // 全角空格
    }

    @Test
    fun `chinese characters grouped by pinyin initial`() {
        assertEquals("W", LetterUtil.firstLetter("微信"))
        assertEquals("Z", LetterUtil.firstLetter("支付宝"))
        assertEquals("T", LetterUtil.firstLetter("淘宝"))
    }

    @Test
    fun `rare chars outside gb2312 range go to hash (documented quirk)`() {
        // 哔(U+55E4) 不在 GB2312 区位码表内（GBK 扩展区），当前实现归入 #。
        // 本测试锁定现状，防止重构时悄然改变分组行为。
        assertEquals("#", LetterUtil.firstLetter("哔哩哔哩"))
    }
}
