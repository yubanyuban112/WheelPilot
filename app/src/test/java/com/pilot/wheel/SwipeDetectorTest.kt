package com.pilot.wheel

import com.pilot.wheel.core.SwipeDetector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/** 上滑呼出判定（SwipeDetector）行为基线测试。阈值基于系统 touchSlop 推导。 */
class SwipeDetectorTest {

    private val slop = 24f

    @Test
    fun `clear upward swipe activates`() {
        // 上滑 60px，略偏内 10px：dy=-60, dist≈61 ≥ 1.15×24=27.6
        val dx = 10f; val dy = -60f
        assertTrue(SwipeDetector.shouldActivate(left = true, touchSlop = slop, dx = dx, dy = dy, dist = hypot(dx, dy)))
        assertTrue(SwipeDetector.shouldActivate(left = false, touchSlop = slop, dx = -10f, dy = -60f, dist = hypot(10f, 60f)))
    }

    @Test
    fun `downward swipe never activates`() {
        val dx = 5f; val dy = 60f
        assertFalse(SwipeDetector.shouldActivate(left = true, touchSlop = slop, dx = dx, dy = dy, dist = hypot(dx, dy)))
    }

    @Test
    fun `too short swipe does not activate`() {
        // 上滑 20px < 1.15×slop=27.6
        val dx = 0f; val dy = -20f
        assertFalse(SwipeDetector.shouldActivate(left = true, touchSlop = slop, dx = dx, dy = dy, dist = hypot(dx, dy)))
    }

    @Test
    fun `diagonal swipe does not activate`() {
        // 斜划：dx=-80 dy=-60，|dy|=60 < 0.9×|dx|=72，非垂直 → 不激活
        val dx = -80f; val dy = -60f
        assertFalse(SwipeDetector.shouldActivate(left = false, touchSlop = slop, dx = dx, dy = dy, dist = hypot(dx, dy)))
    }

    @Test
    fun `outward drift beyond limit blocks activation`() {
        // 左侧热区：dx=-100（向左即向屏幕边缘）达到 -0.8×slop=19.2 之外，即使上滑足够也不激活
        val dx = -100f; val dy = -120f
        assertFalse(SwipeDetector.shouldActivate(left = true, touchSlop = slop, dx = dx, dy = dy, dist = hypot(dx, dy)))
    }

    @Test
    fun `slight inward drift still activates`() {
        // dx=-15（向内）在容差内
        val dx = -15f; val dy = -80f
        assertTrue(SwipeDetector.shouldActivate(left = false, touchSlop = slop, dx = dx, dy = dy, dist = hypot(dx, dy)))
    }
}
