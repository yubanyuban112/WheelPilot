package com.pilot.wheel

import com.pilot.wheel.core.ConfigRules
import org.junit.Assert.assertEquals
import org.junit.Test

/** 配置取值规则：槽位数夹取、热区离底距离的下限保护 */
class ConfigRulesTest {

    @Test
    fun `slots inside range are kept`() {
        assertEquals(6, ConfigRules.normalizeSlots(6))
        assertEquals(7, ConfigRules.normalizeSlots(7))
        assertEquals(8, ConfigRules.normalizeSlots(8))
    }

    @Test
    fun `slots outside range fall back to max`() {
        assertEquals(ConfigRules.MAX_SLOTS, ConfigRules.normalizeSlots(0))
        assertEquals(ConfigRules.MAX_SLOTS, ConfigRules.normalizeSlots(5))
        assertEquals(ConfigRules.MAX_SLOTS, ConfigRules.normalizeSlots(12))
        assertEquals(ConfigRules.MAX_SLOTS, ConfigRules.normalizeSlots(-3))
    }

    @Test
    fun `double row slots support up to 15`() {
        assertEquals(6, ConfigRules.normalizeSlots(6, doubleRow = true))
        assertEquals(15, ConfigRules.normalizeSlots(15, doubleRow = true))
        assertEquals(ConfigRules.MAX_SLOTS_DOUBLE, ConfigRules.normalizeSlots(20, doubleRow = true))
    }

    @Test
    fun `single row clamps overflow to 8 even with larger stored value`() {
        assertEquals(ConfigRules.MAX_SLOTS, ConfigRules.normalizeSlots(15, doubleRow = false))
    }

    @Test
    fun `user bottom wins when it is the largest constraint`() {
        // 2x 密度：用户 120dp = 240px，导航栏 48px + 16px，内缩 0
        assertEquals(
            240,
            ConfigRules.hotspotBottomPx(bottomDp = 120f, density = 2f, navBarPx = 48, edgeInsetPx = 0),
        )
    }

    @Test
    fun `navigation bar guard wins over a zero user value`() {
        // 用户把离底高度拉到 0 时不能让热区沉进导航栏
        assertEquals(
            64,
            ConfigRules.hotspotBottomPx(bottomDp = 0f, density = 2f, navBarPx = 48, edgeInsetPx = 0),
        )
    }

    @Test
    fun `edge inset wins when it exceeds both`() {
        assertEquals(
            200,
            ConfigRules.hotspotBottomPx(bottomDp = 10f, density = 2f, navBarPx = 48, edgeInsetPx = 200),
        )
    }
}
