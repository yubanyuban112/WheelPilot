package com.pilot.wheel.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * GestureArbiter 意图区分单测。
 *
 * 覆盖：DOWN 不立即触发、普通点击 CLICK_PASS、边缘内滑 EDGE_SWIPE_TRIGGER、
 * 方向不符不触发、CANCEL 清理状态、UP 后状态复位。
 */
class GestureArbiterTest {

    private val config = GestureArbiter.Config(
        zoneLeft = 0f,
        zoneTop = 1800f,
        zoneRight = 1080f,
        zoneBottom = 2400f,
        triggerDistancePx = 66f, // 22dp * 3
        clickTolerancePx = 24f,
        maxClickDurationMs = 500L,
        requireInwardDirection = true,
    )

    private fun arbiter() = GestureArbiter(config)

    @Test
    fun `down in zone does not trigger immediately`() {
        val arb = arbiter()
        val r = arb.feed(GestureArbiter.ACTION_DOWN, 100f, 2000f, 1000L)
        assertEquals(GestureArbiter.Arbitration.NONE, r)
    }

    @Test
    fun `down outside zone is none`() {
        val arb = arbiter()
        val r = arb.feed(GestureArbiter.ACTION_DOWN, 100f, 100f, 1000L) // y 不在热区
        assertEquals(GestureArbiter.Arbitration.NONE, r)
    }

    @Test
    fun `tap within tolerance yields click pass`() {
        val arb = arbiter()
        arb.feed(GestureArbiter.ACTION_DOWN, 100f, 2000f, 1000L)
        val up = arb.feed(GestureArbiter.ACTION_UP, 105f, 2003f, 1200L)
        assertEquals(GestureArbiter.Arbitration.CLICK_PASS, up)
    }

    @Test
    fun `edge swipe inward upward triggers`() {
        val arb = arbiter()
        arb.feed(GestureArbiter.ACTION_DOWN, 100f, 2000f, 1000L) // 左半屏
        val r = arb.feed(GestureArbiter.ACTION_MOVE, 120f, 1900f, 1100L) // 向右 + 向上，位移 > 66
        assertEquals(GestureArbiter.Arbitration.EDGE_SWIPE_TRIGGER, r)
    }

    @Test
    fun `swipe with wrong direction does not trigger`() {
        val arb = arbiter()
        arb.feed(GestureArbiter.ACTION_DOWN, 100f, 2000f, 1000L) // 左半屏
        // 向左（错误方向）+ 向下：不符合「左缘向右」的内滑
        val r = arb.feed(GestureArbiter.ACTION_MOVE, 20f, 2100f, 1100L)
        assertEquals(GestureArbiter.Arbitration.NONE, r)
    }

    @Test
    fun `cancel clears state`() {
        val arb = arbiter()
        arb.feed(GestureArbiter.ACTION_DOWN, 100f, 2000f, 1000L)
        arb.feed(GestureArbiter.ACTION_MOVE, 120f, 1900f, 1100L)
        val r = arb.feed(GestureArbiter.ACTION_CANCEL, 120f, 1900f, 1200L)
        assertEquals(GestureArbiter.Arbitration.NONE, r)
        // cancel 后 lastDownPoint 应为 null
        assertEquals(null, arb.lastDownPoint)
    }

    @Test
    fun `up after move resets state`() {
        val arb = arbiter()
        arb.feed(GestureArbiter.ACTION_DOWN, 100f, 2000f, 1000L)
        arb.feed(GestureArbiter.ACTION_MOVE, 50f, 1980f, 1100L) // 位移不足，不触发
        arb.feed(GestureArbiter.ACTION_UP, 50f, 1980f, 1200L)
        assertEquals(null, arb.lastDownPoint)
    }

    @Test
    fun `slow press beyond max duration is not click pass`() {
        val arb = arbiter()
        arb.feed(GestureArbiter.ACTION_DOWN, 100f, 2000f, 1000L)
        // 超过 500ms 的静止按压，位移在容差内，但时长超限 → 非点击
        val up = arb.feed(GestureArbiter.ACTION_UP, 100f, 2000f, 2000L)
        assertEquals(GestureArbiter.Arbitration.NONE, up)
    }
}
