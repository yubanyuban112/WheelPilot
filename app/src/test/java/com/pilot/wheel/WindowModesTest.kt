package com.pilot.wheel

import com.pilot.wheel.core.WindowModes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** 启动候选 windowingMode（WindowModes）行为基线测试 */
class WindowModesTest {

    private val auto = Prefs.LAUNCH_AUTO
    private val vendor = Prefs.LAUNCH_VENDOR
    private val freeform = Prefs.LAUNCH_FREEFORM
    private val full = Prefs.LAUNCH_FULL

    @Test
    fun `honor device tries freeform first`() {
        assertArrayEquals(intArrayOf(5, 100), WindowModes.candidates(vendor, honor = true))
        assertArrayEquals(intArrayOf(5, 100), WindowModes.candidates(auto, honor = true))
    }

    @Test
    fun `non-honor device tries vendor first`() {
        assertArrayEquals(intArrayOf(100, 5), WindowModes.candidates(vendor, honor = false))
        assertArrayEquals(intArrayOf(5, 100), WindowModes.candidates(auto, honor = false))
    }

    @Test
    fun `freeform mode only tries aosp freeform`() {
        assertArrayEquals(intArrayOf(5), WindowModes.candidates(freeform, honor = true))
        assertArrayEquals(intArrayOf(5), WindowModes.candidates(freeform, honor = false))
    }

    @Test
    fun `shell channel appends fullscreen fallback`() {
        assertArrayEquals(intArrayOf(5, 100, 0), WindowModes.candidates(auto, honor = true, withFullscreen = true))
        assertArrayEquals(intArrayOf(100, 5, 0), WindowModes.candidates(vendor, honor = false, withFullscreen = true))
        assertArrayEquals(intArrayOf(5, 0), WindowModes.candidates(freeform, honor = false, withFullscreen = true))
    }

    @Test
    fun `unknown mode falls back like auto`() {
        assertArrayEquals(WindowModes.candidates(auto, honor = true), WindowModes.candidates("whatever", honor = true))
        // full 档不走 candidates（调用方已拦截），此处仅验证与 auto 等价映射
        assertArrayEquals(WindowModes.candidates(auto, honor = false), WindowModes.candidates(full, honor = false))
    }
}
