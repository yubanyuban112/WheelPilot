package com.pilot.wheel.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 41B：freeform 小窗几何单测（≥10 条） */
class FreeformLayoutTest {

    private val screenW = 1000
    private val screenH = 2000

    // ------------------------------------------------------------------
    // nextBounds
    // ------------------------------------------------------------------

    @Test
    fun `nextBounds 默认宽高为屏宽70屏高60`() {
        val r = FreeformLayout.nextBounds(screenW, screenH, 0, null)
        assertEquals(700, r.width)
        assertEquals(1200, r.height)
    }

    @Test
    fun `nextBounds 第 0 窗居中且略偏上`() {
        val r = FreeformLayout.nextBounds(screenW, screenH, 0, null)
        assertEquals(150, r.left)
        // 居中顶 = (2000-1200)/2 = 400，略偏上再减 5% 屏高 = 300
        assertEquals(300, r.top)
    }

    @Test
    fun `nextBounds 第 1 窗按 40px 错开`() {
        val r0 = FreeformLayout.nextBounds(screenW, screenH, 0, null)
        val r1 = FreeformLayout.nextBounds(screenW, screenH, 1, null)
        assertEquals(r0.left + 40, r1.left)
        assertEquals(r0.top + 40, r1.top)
        assertEquals(r0.width, r1.width)
    }

    @Test
    fun `nextBounds 错位出屏时 clamp 回屏内`() {
        // 8 个窗口累计错位 320px，仍不出屏；直接给一个贴底的原矩形验证 clamp
        val base = WindowRect(600, 1600, 1300, 1900)
        val r = FreeformLayout.nextBounds(screenW, screenH, 3, base)
        assertTrue(r.right <= screenW)
        assertTrue(r.bottom <= screenH)
        assertTrue(r.left >= 0)
        assertTrue(r.top >= 0)
        assertEquals(base.width, r.width)
    }

    @Test
    fun `nextBounds 给 base 时沿用其宽高只加错位`() {
        val base = WindowRect(100, 200, 500, 800)
        val r = FreeformLayout.nextBounds(screenW, screenH, 0, base)
        assertEquals(base, r)
        val r2 = FreeformLayout.nextBounds(screenW, screenH, 2, base)
        assertEquals(WindowRect(180, 280, 580, 880), r2)
    }

    // ------------------------------------------------------------------
    // dragResizeBounds
    // ------------------------------------------------------------------

    @Test
    fun `右下角放大时左上角锚定`() {
        val start = WindowRect(100, 200, 500, 700)
        val r = FreeformLayout.dragResizeBounds(start, FreeformLayout.Corner.BR, 80, 60, screenW, screenH)
        assertEquals(WindowRect(100, 200, 580, 760), r)
    }

    @Test
    fun `左下角拖动时右上角锚定`() {
        val start = WindowRect(100, 200, 500, 700)
        val r = FreeformLayout.dragResizeBounds(start, FreeformLayout.Corner.BL, -50, 0, screenW, screenH)
        assertEquals(WindowRect(50, 200, 500, 700), r)
    }

    @Test
    fun `左上角拖动同时改 left 和 top`() {
        val start = WindowRect(100, 200, 500, 700)
        val r = FreeformLayout.dragResizeBounds(start, FreeformLayout.Corner.TL, -30, -40, screenW, screenH)
        assertEquals(WindowRect(70, 160, 500, 700), r)
    }

    @Test
    fun `缩到最小尺寸以下被顶回 minW minH`() {
        val start = WindowRect(100, 200, 500, 700)
        // 右下角向左上拖 400x500 → 400x500 起点，再拖会小于最小值
        val r = FreeformLayout.dragResizeBounds(
            start, FreeformLayout.Corner.BR, -400, -500, screenW, screenH,
        )
        assertEquals(340, r.width)
        assertEquals(420, r.height)
        // 锚定角（左上）不动
        assertEquals(100, r.left)
        assertEquals(200, r.top)
    }

    @Test
    fun `拖出屏幕右缘被 clamp`() {
        val start = WindowRect(100, 200, 500, 700)
        val r = FreeformLayout.dragResizeBounds(start, FreeformLayout.Corner.BR, 1200, 0, screenW, screenH)
        assertEquals(1000, r.right)
    }

    @Test
    fun `拖出屏幕下缘被 clamp`() {
        val start = WindowRect(100, 200, 500, 700)
        val r = FreeformLayout.dragResizeBounds(start, FreeformLayout.Corner.BR, 0, 3000, screenW, screenH)
        assertEquals(screenH, r.bottom)
    }

    // ------------------------------------------------------------------
    // ballRestoreBounds
    // ------------------------------------------------------------------

    @Test
    fun `恢复矩形完全在屏内则原样返回`() {
        val prev = WindowRect(100, 300, 500, 900)
        assertEquals(prev, FreeformLayout.ballRestoreBounds(prev, screenW, screenH))
    }

    @Test
    fun `恢复矩形右缘偏出则向左贴边`() {
        val prev = WindowRect(900, 300, 1400, 900)
        val r = FreeformLayout.ballRestoreBounds(prev, screenW, screenH)
        assertEquals(WindowRect(500, 300, 1000, 900), r)
    }

    @Test
    fun `恢复矩形底缘偏出则向上贴边`() {
        val prev = WindowRect(100, 1600, 500, 2300)
        val r = FreeformLayout.ballRestoreBounds(prev, screenW, screenH)
        assertEquals(WindowRect(100, 1300, 500, 2000), r)
    }

    @Test
    fun `恢复矩形宽高超屏则收窄贴边`() {
        val prev = WindowRect(0, 0, 1500, 2500)
        val r = FreeformLayout.ballRestoreBounds(prev, screenW, screenH)
        assertEquals(WindowRect(0, 0, 1000, 2000), r)
    }

    // ------------------------------------------------------------------
    // shouldCollapseToBall
    // ------------------------------------------------------------------

    @Test
    fun `拖过屏幕底缘 64px 之外收球`() {
        assertTrue(FreeformLayout.shouldCollapseToBall(screenH + 64f + 1f, screenH))
        assertTrue(FreeformLayout.shouldCollapseToBall(screenH + 100f, screenH))
    }

    @Test
    fun `屏幕底缘之内不收球`() {
        assertFalse(FreeformLayout.shouldCollapseToBall(screenH - 10f, screenH))
        assertFalse(FreeformLayout.shouldCollapseToBall(screenH + 63f, screenH))
    }
}
