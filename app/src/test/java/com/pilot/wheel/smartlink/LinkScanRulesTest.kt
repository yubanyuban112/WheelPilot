package com.pilot.wheel.smartlink

import com.pilot.wheel.core.WindowRect
import com.pilot.wheel.smartlink.detect.LinkCandidate
import com.pilot.wheel.smartlink.detect.LinkScanRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 57 号：整页扫描结果的去重/排序规则（纯函数）。
 *
 * 这些规则决定「胶囊贴哪一条链接」，所以顺序必须稳定：同一行内按 x，跨行按 y，
 * 行内小位移（≤10px）归到同一桶。
 */
class LinkScanRulesTest {

    private fun c(
        url: String,
        left: Int,
        top: Int,
        textOffset: Int = 0,
        text: String = "text",
    ) = LinkCandidate(url, text, WindowRect(left, top, left + 100, top + 40), textOffset)

    @Test
    fun dedupe_dropsSameUrlAtSamePosition() {
        val list = listOf(
            c("https://a.com/1", 10, 100),
            c("https://a.com/1", 10, 100),
        )
        assertEquals(1, LinkScanRules.dedupe(list).size)
    }

    @Test
    fun dedupe_keepsSameUrlAtDifferentPositions() {
        val list = listOf(
            c("https://a.com/1", 10, 100),
            c("https://a.com/1", 300, 900),
        )
        assertEquals(2, LinkScanRules.dedupe(list).size)
    }

    @Test
    fun sort_ordersByRowsThenLeft() {
        val list = listOf(
            c("https://c.com", 400, 100),
            c("https://a.com", 10, 100),
            c("https://b.com", 10, 300),
        )
        val sorted = LinkScanRules.sort(list).map { it.url }
        assertEquals(listOf("https://a.com", "https://c.com", "https://b.com"), sorted)
    }

    @Test
    fun sort_treatsSmallVerticalDriftAsSameRow() {
        // 100 与 104 同属 10px 桶 → 由 x 决定先后
        val list = listOf(
            c("https://right.com", 300, 104),
            c("https://left.com", 10, 100),
        )
        assertEquals(
            listOf("https://left.com", "https://right.com"),
            LinkScanRules.sort(list).map { it.url },
        )
    }

    @Test
    fun sort_largerDriftStartsNewRow() {
        val list = listOf(
            c("https://second-row.com", 10, 120),
            c("https://first-row.com", 900, 100),
        )
        assertEquals(
            listOf("https://first-row.com", "https://second-row.com"),
            LinkScanRules.sort(list).map { it.url },
        )
    }

    @Test
    fun finalize_appliesLimitAndKeepsReadingOrder() {
        val list = (0 until 5).map { c("https://n$it.com", 10, 100 + it * 20) }
        val out = LinkScanRules.finalize(list, limit = 3)
        assertEquals(3, out.size)
        assertEquals(listOf("https://n0.com", "https://n1.com", "https://n2.com"), out.map { it.url })
    }

    @Test
    fun finalize_handlesEmptyAndNonPositiveLimit() {
        assertTrue(LinkScanRules.finalize(emptyList()).isEmpty())
        val one = listOf(c("https://a.com", 0, 0))
        assertEquals(1, LinkScanRules.finalize(one, limit = 0).size) // limit 至少 1
    }

    @Test
    fun lineBucketConstantIsTenPixels() {
        assertEquals(10, LinkScanRules.LINE_BUCKET_PX)
    }
}
