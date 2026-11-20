package com.pilot.wheel.core

import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 59 号：热区方向生效范围（纯函数）。
 *
 * `Configuration.ORIENTATION_*` 是编译期内联常量，本地 JVM 单测可直接使用。
 */
class HotspotScopeTest {

    private val portrait = Configuration.ORIENTATION_PORTRAIT
    private val landscape = Configuration.ORIENTATION_LANDSCAPE

    @Test
    fun both_allowsEveryOrientation() {
        assertTrue(HotspotScope.allows(HotspotScope.BOTH, portrait))
        assertTrue(HotspotScope.allows(HotspotScope.BOTH, landscape))
    }

    @Test
    fun portraitOnly_blocksLandscape() {
        assertTrue(HotspotScope.allows(HotspotScope.PORTRAIT, portrait))
        assertFalse(HotspotScope.allows(HotspotScope.PORTRAIT, landscape))
    }

    @Test
    fun landscapeOnly_blocksPortrait() {
        assertTrue(HotspotScope.allows(HotspotScope.LANDSCAPE, landscape))
        assertFalse(HotspotScope.allows(HotspotScope.LANDSCAPE, portrait))
    }

    @Test
    fun unknownValue_fallsBackToBoth() {
        assertEquals(HotspotScope.BOTH, HotspotScope.normalize(7))
        assertEquals(HotspotScope.BOTH, HotspotScope.normalize(-1))
        assertTrue(HotspotScope.allows(99, landscape))
    }

    @Test
    fun cycleOrderIsAllToPortraitToLandscapeToAll() {
        var mode = HotspotScope.BOTH
        mode = HotspotScope.next(mode)
        assertEquals(HotspotScope.PORTRAIT, mode)
        mode = HotspotScope.next(mode)
        assertEquals(HotspotScope.LANDSCAPE, mode)
        mode = HotspotScope.next(mode)
        assertEquals(HotspotScope.BOTH, mode)
    }

    @Test
    fun labelsMatchModes() {
        assertEquals("竖屏与横屏", HotspotScope.label(HotspotScope.BOTH))
        assertEquals("仅竖屏", HotspotScope.label(HotspotScope.PORTRAIT))
        assertEquals("仅横屏", HotspotScope.label(HotspotScope.LANDSCAPE))
    }

    @Test
    fun unspecifiedOrientation_isNotPortraitNorLandscape() {
        // ORIENTATION_UNDEFINED(0)：仅竖屏/仅横屏都拦，兜底偏保守（宁可不触发，也不在未知姿态误触发）
        val undefined = Configuration.ORIENTATION_UNDEFINED
        assertFalse(HotspotScope.allows(HotspotScope.PORTRAIT, undefined))
        assertFalse(HotspotScope.allows(HotspotScope.LANDSCAPE, undefined))
        assertTrue(HotspotScope.allows(HotspotScope.BOTH, undefined))
    }
}
