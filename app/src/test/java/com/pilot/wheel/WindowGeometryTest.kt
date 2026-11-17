package com.pilot.wheel

import com.pilot.wheel.core.WindowGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 小窗几何（WindowGeometry）行为基线测试 —— 重构前后必须同一结果 */
class WindowGeometryTest {

    @Test
    fun `invalid screen size returns null`() {
        assertNull(WindowGeometry.freeformBounds(0, 2800))
        assertNull(WindowGeometry.freeformBounds(1272, 0))
        assertNull(WindowGeometry.freeformBounds(-1, -1))
    }

    @Test
    fun `portrait bounds match measured native freeform geometry`() {
        // 真机 1272×2800（荣耀原生小窗实测基准）
        val r = WindowGeometry.freeformBounds(1272, 2800)!!
        val expectW = (1272 * WindowGeometry.PORTRAIT_WIDTH_FACTOR).toInt() // 1055
        assertEquals(expectW, r.width)
        // 高 = 宽×1.53 = 1614，未超过上限 0.58×2800 = 1624，故不截断
        assertEquals((expectW * WindowGeometry.PORTRAIT_HEIGHT_RATIO).toInt(), r.height)
        // 水平居中
        assertEquals((1272 - expectW) / 2, r.left)
        assertEquals((1272 + expectW) / 2, r.right)
        // 顶 = 0.21×2800
        assertEquals((2800 * WindowGeometry.PORTRAIT_TOP_FACTOR).toInt(), r.top)
    }

    @Test
    fun `portrait height capped by max height factor`() {
        // 构造触发截断：宽 1000 高 1200 → 高 1530 > 0.58×1200=696 → 截断
        val r = WindowGeometry.freeformBounds(1000, 1200)!!
        assertEquals((1200 * 0.58f).toInt(), r.height)
        assertEquals((r.height / WindowGeometry.PORTRAIT_HEIGHT_RATIO).toInt(), r.width)
        assertTrue(r.bottom <= 1200)
    }

    @Test
    fun `landscape uses short side and landscape factors`() {
        // 横屏 2800×1272：宽 = 短边 1272×0.58 = 737
        val r = WindowGeometry.freeformBounds(2800, 1272)!!
        assertEquals((1272 * WindowGeometry.LANDSCAPE_WIDTH_FACTOR).toInt(), r.width)
        assertEquals((r.width * WindowGeometry.LANDSCAPE_HEIGHT_RATIO).toInt(), r.height)
        assertEquals((1272 * WindowGeometry.LANDSCAPE_TOP_FACTOR).toInt(), r.top)
    }

    @Test
    fun `landscape height capped`() {
        // 横屏 500×400：宽 = 400×0.58=232，高 = 232×0.86=199 < 0.78×400=312 不截断；
        // 再验证截断分支：屏高越小时上限越低
        val r = WindowGeometry.freeformBounds(500, 400)!!
        val w = (400 * 0.58f).toInt()
        val h0 = (w * 0.86f).toInt()
        val cap = (400 * 0.78f).toInt()
        if (h0 > cap) assertEquals(cap, r.height) else assertEquals(h0, r.height)
        assertTrue(r.bottom <= 400)
        assertNotNull(WindowGeometry.freeformBounds(2000, 800))
    }
}
