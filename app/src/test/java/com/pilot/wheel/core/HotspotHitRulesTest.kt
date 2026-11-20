package com.pilot.wheel.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HotspotHitRulesTest {

    private val touchXMax = 1080f
    private val touchYMax = 2400f
    private val screenW = 1080
    private val screenH = 2400

    private fun p(x: Float, y: Float) = HotspotHitRules.RawTouchPoint(x, y)

    // -----------------------------------------------------------------------
    // 归一化 + 方向映射
    // -----------------------------------------------------------------------

    @Test
    fun normalizeRotation0_isIdentity() {
        val out = HotspotHitRules.normalize(
            p(touchXMax / 2f, touchYMax), touchXMax, touchYMax, screenW, screenH, 0,
        )
        assertEquals(540f, out.x, 0.01f)
        assertEquals(2400f, out.y, 0.01f)
    }

    @Test
    fun normalizeRotation90_mapsNaturalBottomToLogicalLeft() {
        // 自然方向「底部中点」旋转 90° 后应落在逻辑屏「左侧中点」
        val out = HotspotHitRules.normalize(
            p(touchXMax / 2f, touchYMax), touchXMax, touchYMax, screenW, screenH, 90,
        )
        // nx=0.5, ny=1.0 -> x=(1-1)*W=0, y=0.5*H=1200
        assertEquals(0f, out.x, 0.01f)
        assertEquals(1200f, out.y, 0.01f)
    }

    @Test
    fun normalizeRotation180_mapsNaturalBottomToLogicalTop() {
        // 180°：nx=0.5, ny=1.0 -> x=(1-0.5)*W=540, y=(1-1)*H=0
        val out = HotspotHitRules.normalize(
            p(touchXMax / 2f, touchYMax), touchXMax, touchYMax, screenW, screenH, 180,
        )
        assertEquals(540f, out.x, 0.01f)
        assertEquals(0f, out.y, 0.01f)
    }

    @Test
    fun normalizeRotation270_mapsNaturalBottomToLogicalRight() {
        // 270°：nx=0.5, ny=1.0 -> x=1*W=1080, y=(1-0.5)*H=1200
        val out = HotspotHitRules.normalize(
            p(touchXMax / 2f, touchYMax), touchXMax, touchYMax, screenW, screenH, 270,
        )
        assertEquals(1080f, out.x, 0.01f)
        assertEquals(1200f, out.y, 0.01f)
    }

    @Test
    fun normalizeRealDeviceScale_eightFoldPanelMapsIntoScreen() {
        // 真机实测：event8 量程 10175x22399，屏幕 1272x2800，DOWN raw=(6668,14138)
        // 期望 nx=0.655, ny=0.631 -> 屏幕内 (833,1767)，证明 8 倍面板归一化不越界
        val out = HotspotHitRules.normalize(
            p(6668f, 14138f), 10175f, 22399f, 1272, 2800, 0,
        )
        assertEquals(6668f / 10175f * 1272f, out.x, 0.01f)
        assertEquals(14138f / 22399f * 2800f, out.y, 0.01f)
        assertTrue("x in screen", out.x in 0f..1272f)
        assertTrue("y in screen", out.y in 0f..2800f)
    }

    @Test
    fun normalizeClampsOutOfRange() {
        val out = HotspotHitRules.normalize(
            p(touchXMax * 2f, -100f), touchXMax, touchYMax, screenW, screenH, 0,
        )
        assertEquals(1080f, out.x, 0.01f) // clamp 到 1.0
        assertEquals(0f, out.y, 0.01f) // clamp 到 0
    }

    @Test
    fun normalizeWithoutMax_fallsBackToRawScale() {
        val out = HotspotHitRules.normalize(p(540f, 1200f), 0f, 0f, screenW, screenH, 0)
        assertEquals(540f, out.x, 0.01f)
        assertEquals(1200f, out.y, 0.01f)
    }

    // -----------------------------------------------------------------------
    // 热区命中
    // -----------------------------------------------------------------------

    @Test
    fun inHotZone_hitsBottomLeftCorner() {
        val density = 3f // 1080/360
        // 左下角：x=30px(10dp)，y 落在热区垂直范围内（1944..2232 之间）
        val point = HotspotHitRules.ScreenPoint(30f, 2100f)
        val hit = HotspotHitRules.inHotZone(
            point, 48f, 96f, 0f, 56f, density, screenW, screenH, fullEdge = false,
        )
        assertTrue(hit)
    }

    @Test
    fun inHotZone_missesScreenCenter() {
        val density = 3f
        val point = HotspotHitRules.ScreenPoint(screenW / 2f, screenH / 2f)
        val hit = HotspotHitRules.inHotZone(
            point, 48f, 96f, 0f, 56f, density, screenW, screenH, fullEdge = false,
        )
        assertFalse(hit)
    }

    @Test
    fun inHotZone_missesTooHighAboveZone() {
        val density = 3f
        // y 远在热区顶之上
        val point = HotspotHitRules.ScreenPoint(30f, 100f)
        val hit = HotspotHitRules.inHotZone(
            point, 48f, 96f, 0f, 56f, density, screenW, screenH, fullEdge = false,
        )
        assertFalse(hit)
    }

    @Test
    fun inHotZone_fullEdgeHitsMiddleBottom() {
        val density = 3f
        val point = HotspotHitRules.ScreenPoint(screenW / 2f, 2100f)
        val hit = HotspotHitRules.inHotZone(
            point, 48f, 96f, 0f, 56f, density, screenW, screenH, fullEdge = true,
        )
        assertTrue(hit)
    }

    // -----------------------------------------------------------------------
    // 点击窗外判定
    // -----------------------------------------------------------------------

    @Test
    fun outsideRect_returnsTrueOutside() {
        val rect = WindowRect(100, 100, 500, 500)
        assertTrue(HotspotHitRules.outsideRect(HotspotHitRules.ScreenPoint(600f, 600f), rect))
        assertTrue(HotspotHitRules.outsideRect(HotspotHitRules.ScreenPoint(50f, 300f), rect))
    }

    @Test
    fun outsideRect_returnsFalseInside() {
        val rect = WindowRect(100, 100, 500, 500)
        assertFalse(HotspotHitRules.outsideRect(HotspotHitRules.ScreenPoint(300f, 300f), rect))
    }

    @Test
    fun outsideRect_invalidRectReturnsFalse() {
        val rect = WindowRect(100, 100, 100, 100) // width 0
        assertFalse(HotspotHitRules.outsideRect(HotspotHitRules.ScreenPoint(300f, 300f), rect))
    }

    // -----------------------------------------------------------------------
    // 上滑判定（复用 SwipeDetector）
    // -----------------------------------------------------------------------

    @Test
    fun shouldActivate_detectsUpSwipe() {
        val down = HotspotHitRules.ScreenPoint(100f, 2000f)
        val up = HotspotHitRules.ScreenPoint(100f, 1800f) // 向上 200px
        assertTrue(HotspotHitRules.shouldActivate(down, up, left = true, touchSlop = 24f, minDistancePx = 60f))
    }

    @Test
    fun shouldActivate_rejectsTinyMovement() {
        val down = HotspotHitRules.ScreenPoint(100f, 2000f)
        val up = HotspotHitRules.ScreenPoint(102f, 1998f) // 位移 < minDistance
        assertFalse(HotspotHitRules.shouldActivate(down, up, left = true, touchSlop = 24f, minDistancePx = 60f))
    }

    // -----------------------------------------------------------------------
    // 容差 / 阈值 / 垂直度
    // -----------------------------------------------------------------------

    @Test
    fun withinTapTolerance_works() {
        val down = HotspotHitRules.ScreenPoint(100f, 100f)
        assertTrue(HotspotHitRules.withinTapTolerance(down, HotspotHitRules.ScreenPoint(105f, 100f), 10f))
        assertFalse(HotspotHitRules.withinTapTolerance(down, HotspotHitRules.ScreenPoint(120f, 100f), 10f))
    }

    @Test
    fun isVerticalMotion_detectsVertical() {
        val down = HotspotHitRules.ScreenPoint(100f, 2000f)
        assertTrue(HotspotHitRules.isVerticalMotion(down, HotspotHitRules.ScreenPoint(100f, 1800f)))
        assertFalse(HotspotHitRules.isVerticalMotion(down, HotspotHitRules.ScreenPoint(300f, 1900f)))
    }
}
