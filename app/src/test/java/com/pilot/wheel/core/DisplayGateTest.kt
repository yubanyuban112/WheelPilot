package com.pilot.wheel.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 55 号：几何基准的纯函数测试。
 *
 * 关键用例对应真机事实：HONOR AAK-AN00 竖屏物理 1272×2800，
 * `currentWindowMetrics` 若排除导航条则底边小于 2800（参考机实测差 65px）。
 * 全部用例只用 [WindowRect]，不触碰 android.graphics.Rect（JVM 单测里是 stub）。
 */
class DisplayGateTest {

    private val full = WindowRect(0, 0, 1272, 2800)
    private val noNav = WindowRect(0, 0, 1272, 2735) // 假设排除 65px 导航条

    // -----------------------------------------------------------------------
    // unionBounds：只扩不缩
    // -----------------------------------------------------------------------

    @Test
    fun union_picksPhysicalBottomWhenCurrentExcludesNavBar() {
        val out = DisplayGate.unionBounds(noNav, full, full)
        assertEquals(2800, out.bottom)
        assertEquals(0, out.top)
        assertEquals(1272, out.width)
    }

    @Test
    fun union_neverShrinksWhenCurrentIsSmaller() {
        // 分屏 / 小窗场景：current 只有半屏，仍以 maximum 为准
        val half = WindowRect(0, 0, 1272, 1400)
        assertEquals(full, DisplayGate.unionBounds(half, full, full))
    }

    @Test
    fun union_fallsBackWhenBothNull() {
        assertEquals(full, DisplayGate.unionBounds(null, null, full))
    }

    @Test
    fun union_ignoresInvalidRect() {
        val empty = WindowRect(0, 0, 0, 0)
        assertEquals(full, DisplayGate.unionBounds(empty, full, WindowRect(0, 0, 1, 1)))
        assertEquals(full, DisplayGate.unionBounds(full, empty, WindowRect(0, 0, 1, 1)))
    }

    @Test
    fun union_handlesLandscapeSwap() {
        val land = WindowRect(0, 0, 2800, 1272)
        val out = DisplayGate.unionBounds(land, land, WindowRect(0, 0, 1272, 2800))
        assertEquals(2800, out.width)
        assertEquals(1272, out.height)
    }

    // -----------------------------------------------------------------------
    // judgeRect / visualRect：几何错位量
    // -----------------------------------------------------------------------

    @Test
    fun drift_isZeroWhenJudgeDomainIsPhysical() {
        val judge = DisplayGate.judgeRect(full, 144, 288, 0, 168, left = true)
        val visual = DisplayGate.visualRect(full, 144, 288, 0, 168, left = true)
        assertEquals(0, DisplayGate.verticalDriftPx(judge, visual))
        assertEquals(judge, visual)
    }

    @Test
    fun drift_reportsNavBarMismatch() {
        // 判定域用排除导航条的 current，视觉窗口贴物理底边 → 判定区整体上浮 65px
        val judge = DisplayGate.judgeRect(noNav, 144, 288, 0, 168, left = true)
        val visual = DisplayGate.visualRect(full, 144, 288, 0, 168, left = true)
        assertEquals(-65, DisplayGate.verticalDriftPx(judge, visual))
        assertEquals(noNav.bottom - 168 - 288, judge.top)
        assertEquals(full.bottom - 168 - 288, visual.top)
    }

    @Test
    fun judgeRect_matchesInHotZoneSemantics() {
        // 与 HotspotHitRules.inHotZone 同源校验：矩形内的点必须命中
        val density = 3f
        val w = (48 * density).toInt()
        val h = (96 * density).toInt()
        val bottomPx = (56 * density).toInt() + 65 // 含导航栏护栏
        val rect = DisplayGate.judgeRect(full, w, h, 0, bottomPx, left = true)
        val p = HotspotHitRules.ScreenPoint(30f, rect.top + 10f)
        assertTrue(
            HotspotHitRules.inHotZone(
                p, 48f, 96f, 0f, bottomPx.toFloat() / density, density,
                1272, 2800, fullEdge = false,
            ),
        )
        assertEquals(0, rect.left)
        assertEquals(2800 - bottomPx, rect.bottom)
    }

    @Test
    fun visualRect_rightSideAnchorsToRightEdge() {
        val visual = DisplayGate.visualRect(full, 144, 288, 30, 168, left = false)
        assertEquals(1272 - 30, visual.right)
        assertEquals(1272 - 30 - 144, visual.left)
    }

    // -----------------------------------------------------------------------
    // gateLine：日志可解析
    // -----------------------------------------------------------------------

    @Test
    fun gateLine_containsAllFieldsAndDrift() {
        val g = DisplayGate.Geometry(
            physical = full, current = noNav, maximum = full,
            dmWidth = 1272, dmHeight = 2735, navBottomPx = 65, rotation = 0,
        )
        val judge = DisplayGate.judgeRect(noNav, 144, 288, 0, 168, left = true)
        val visual = DisplayGate.visualRect(full, 144, 288, 0, 168, left = true)
        val line = DisplayGate.gateLine(g, judge, visual, 2.75f)
        listOf("phys=", "cur=", "max=", "dm=1272x2735", "nav=65", "rot=0", "dTop=-65").forEach {
            assertTrue("missing $it in $line", line.contains(it))
        }
    }

    @Test
    fun gateLine_appendsExtraWithoutTrailingSpace() {
        val g = DisplayGate.Geometry(full, full, full, 1272, 2800, 0, 1)
        val r = DisplayGate.judgeRect(full, 10, 10, 0, 10, left = true)
        val line = DisplayGate.gateLine(g, r, r, 2.75f, extra = "pass=true")
        assertTrue(line.endsWith("pass=true"))
        assertTrue(line.contains("dTop=0"))
    }

    // -----------------------------------------------------------------------
    // 56 号：四分之一椭圆命中（真机参数：1272×2800、d=3.5、w=125、h=175、inset=36、bottom=245）
    // -----------------------------------------------------------------------

    private val w = 125
    private val h = 175
    private val inset = 36
    private val bottom = 245
    private val cornerX = 36f
    private val centerY = 2555f // 2800 - 245

    private fun sector(x: Float, y: Float, left: Boolean = true) = DisplayGate.sectorContains(
        x = x, y = y, bounds = full,
        insetPx = inset, bottomPx = bottom,
        bottomExtentPx = w, edgeExtentPx = h, left = left,
    )

    @Test
    fun sector_hitsRightAtTheCorner() {
        assertTrue(sector(cornerX + 1f, centerY - 1f))
    }

    @Test
    fun sector_rejectsFarDiagonalThatRectWouldAccept() {
        // 包围盒的远端对角：u≈1、v≈1 → 椭圆外；而矩形（judgeRect）把它算作热区
        val x = 160f
        val y = 2380f
        assertFalse(sector(x, y))
        val rect = DisplayGate.judgeRect(full, w, h, inset, bottom, left = true)
        assertTrue("矩形确实包含该点（正是本号要切掉的区域）", x <= rect.right && y >= rect.top)
    }

    @Test
    fun sector_rejectsPointsBelowBandOrAboveReach() {
        assertFalse("低于离底带下沿", sector(cornerX + 10f, centerY + 5f))
        assertFalse("高于侧边触及范围", sector(cornerX + 10f, centerY - h - 5f))
    }

    @Test
    fun sector_respectsInset() {
        assertFalse("内缩以内不算", sector(cornerX - 5f, centerY - 10f))
        assertTrue("内缩以内侧一点算", sector(cornerX + 5f, centerY - 10f))
    }

    @Test
    fun sector_mirrorsOnRightSide() {
        val rightCornerX = 1272f - inset
        assertTrue(sector(rightCornerX - 5f, centerY - 10f, left = false))
        assertFalse(sector(rightCornerX + 5f, centerY - 10f, left = false))
        // 右侧的远端对角同样被切掉
        assertFalse(sector(1272f - 160f, 2380f, left = false))
    }

    @Test
    fun sector_boundaryIsInclusive() {
        // u=0.6, v=0.8 → 0.36+0.64 = 1.0，边界应算命中
        assertTrue(sector(cornerX + 0.6f * w, centerY - 0.8f * h))
        // u=0.7, v=0.8 → 1.13 在外
        assertFalse(sector(cornerX + 0.7f * w, centerY - 0.8f * h))
    }

    @Test
    fun sector_zeroExtentNeverHits() {
        assertFalse(
            DisplayGate.sectorContains(
                cornerX + 1f, centerY - 1f, full, inset, bottom,
                bottomExtentPx = 0, edgeExtentPx = h, left = true,
            ),
        )
    }

    @Test
    fun sectorBoundsRect_equalsEllipseBoundingBox() {
        val r = DisplayGate.sectorBoundsRect(full, inset, bottom, w, h, left = true)
        assertEquals(cornerX.toInt(), r.left)
        assertEquals((cornerX + w).toInt(), r.right)
        assertEquals((centerY - h).toInt(), r.top)
        assertEquals(centerY.toInt(), r.bottom)
        // 与视觉窗口矩形的底边、左边一致 —— 换了形状不换锚点
        val visual = DisplayGate.visualRect(full, w, h, inset, bottom, left = true)
        assertEquals(visual.bottom, r.bottom)
        assertEquals(visual.left, r.left)
    }
}
