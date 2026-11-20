package com.pilot.wheel

import com.pilot.wheel.core.LightOpenPolicy
import com.pilot.wheel.core.LightOpenReject
import com.pilot.wheel.core.LightOpenRules
import com.pilot.wheel.core.LightOpenScene
import com.pilot.wheel.core.LightOpenSnapshot
import com.pilot.wheel.core.LightOpenStatsCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 轻量打开判据。这里锁的是"什么情况必须转窗、什么情况必须不转窗"，
 * 因为误转（把支付页转成浮窗）比漏转严重得多。
 */
class LightOpenRulesTest {

    private val now = 1_000_000L

    private fun snap(
        foreground: String? = "com.tencent.mm",
        previous: String? = "com.miui.home",
        shadeAt: Long = 0L,
        qsAt: Long = 0L,
        headsUpAt: Long = 0L,
        headsUpPkg: String? = null,
        chooserAt: Long = 0L,
        anchorReturn: String? = null,
        notif: Map<String, Long> = emptyMap(),
        enabled: Boolean = true,
        scenes: Set<LightOpenScene> = LightOpenScene.entries.toSet(),
        wheelOnly: Boolean = false,
        wheel: Set<String> = emptySet(),
        lastConvertAt: Long = 0L,
        cooldownMs: Long = LightOpenRules.DEFAULT_COOLDOWN_MS,
        manualAt: Long = 0L,
        manualPkg: String? = null,
        flowTarget: String? = null,
        flowAt: Long = 0L,
        flowSource: String? = null,
        excluded: Set<String> = setOf("com.android.systemui", "com.android.settings"),
    ) = LightOpenSnapshot(
        now = now,
        foregroundPkg = foreground,
        previousPkg = previous,
        launcherPkg = "com.miui.home",
        selfPkg = "com.pilot.wheel",
        excluded = excluded,
        shadeOpenedAt = shadeAt,
        quickSettingsOpenedAt = qsAt,
        headsUpAt = headsUpAt,
        headsUpPkg = headsUpPkg,
        chooserSeenAt = chooserAt,
        anchorReturnPkg = anchorReturn,
        notifPkgs = notif,
        wheelPkgs = wheel,
        enabled = enabled,
        enabledScenes = scenes,
        wheelOnly = wheelOnly,
        cooldownMs = cooldownMs,
        lastConvertAt = lastConvertAt,
        manualAt = manualAt,
        manualPkg = manualPkg,
        flowTargetPkg = flowTarget,
        flowAt = flowAt,
        flowSourcePkg = flowSource,
    )

    @Test
    fun `总开关关闭时一律不转窗`() {
        val d = LightOpenRules.decide(snap(enabled = false))
        assertFalse(d.accept)
        assertEquals(LightOpenReject.DISABLED, d.reject)
    }

    @Test
    fun `拿不到前台应用时放弃`() {
        val d = LightOpenRules.decide(snap(foreground = null))
        assertEquals(LightOpenReject.NO_FOREGROUND, d.reject)
    }

    @Test
    fun `目标是轻窗自己不转窗`() {
        val d = LightOpenRules.decide(snap(foreground = "com.pilot.wheel", previous = "com.tencent.mm"))
        assertEquals(LightOpenReject.SELF, d.reject)
    }

    @Test
    fun `系统界面与设置在排除表里`() {
        val d = LightOpenRules.decide(snap(foreground = "com.android.settings", previous = "com.tencent.mm"))
        assertEquals(LightOpenReject.EXCLUDED, d.reject)
    }

    @Test
    fun `前台没变化不算切换`() {
        val d = LightOpenRules.decide(snap(foreground = "com.tencent.mm", previous = "com.tencent.mm"))
        assertEquals(LightOpenReject.NOT_A_SWITCH, d.reject)
    }

    @Test
    fun `从桌面点图标启动不转窗`() {
        val d = LightOpenRules.decide(snap(previous = "com.miui.home"))
        assertFalse(d.accept)
        assertEquals(LightOpenReject.SCENE_UNKNOWN, d.reject)
    }

    @Test
    fun `通知超出有效期后不认`() {
        val d = LightOpenRules.decide(
            snap(notif = mapOf("com.tencent.mm" to now - 20_000)),
        )
        assertEquals(LightOpenReject.SCENE_UNKNOWN, d.reject)
    }

    @Test
    fun `锁屏点通知没有通知栏锚点也能认`() {
        val d = LightOpenRules.decide(
            snap(previous = "com.android.systemui", notif = mapOf("com.tencent.mm" to now - 2_500)),
        )
        assertTrue(d.accept)
        assertEquals(LightOpenScene.NOTIF_HEADS_UP, d.scene)
    }

    @Test
    fun `通知场景不受只对轮盘应用生效的限制`() {
        val d = LightOpenRules.decide(
            snap(
                previous = "com.android.systemui",
                notif = mapOf("com.tencent.mm" to now - 1_000),
                wheelOnly = true,
                wheel = setOf("com.android.chrome"),
            ),
        )
        assertTrue(d.accept)
        assertEquals(LightOpenScene.NOTIF_HEADS_UP, d.scene)
    }

    @Test
    fun `从桌面点刚发过通知的应用也不转窗`() {
        val d = LightOpenRules.decide(
            snap(
                foreground = "com.xingin.xhs",
                previous = "com.miui.home",
                notif = mapOf("com.xingin.xhs" to now - 2_000),
            ),
        )
        assertFalse(d.accept)
        assertEquals(LightOpenReject.SCENE_UNKNOWN, d.reject)
    }

    @Test
    fun `应用跳转仍然受轮盘范围限制`() {
        val d = LightOpenRules.decide(
            snap(
                previous = "com.android.chrome",
                wheelOnly = true,
                wheel = setOf("com.android.chrome"),
            ),
        )
        assertEquals(LightOpenReject.OUT_OF_SCOPE, d.reject)
    }

    @Test
    fun `授权只允许改几何 跳转默认也保守`() {
        assertEquals(
            LightOpenPolicy.GEOMETRY_ONLY,
            LightOpenRules.policyFor(LightOpenScene.AUTH, aggressiveJump = true),
        )
        assertEquals(
            LightOpenPolicy.GEOMETRY_ONLY,
            LightOpenRules.policyFor(LightOpenScene.APP_JUMP, aggressiveJump = false),
        )
        assertEquals(
            LightOpenPolicy.FULL,
            LightOpenRules.policyFor(LightOpenScene.APP_JUMP, aggressiveJump = true),
        )
    }

    @Test
    fun `横幅允许重投 冷启动由执行器升级`() {
        assertEquals(
            LightOpenPolicy.FULL,
            LightOpenRules.policyFor(LightOpenScene.NOTIF_HEADS_UP, aggressiveJump = false),
        )
        assertEquals(
            LightOpenPolicy.GEOMETRY_ONLY,
            LightOpenRules.policyFor(null, aggressiveJump = true),
        )
    }

    @Test
    fun `横幅优先于应用跳转`() {
        val d = LightOpenRules.decide(
            snap(
                previous = "com.android.chrome",
                headsUpAt = now - 500,
                headsUpPkg = "com.tencent.mm",
            ),
        )
        assertTrue(d.accept)
        assertEquals(LightOpenScene.NOTIF_HEADS_UP, d.scene)
    }

    @Test
    fun `横幅属于别的应用时不算横幅`() {
        val d = LightOpenRules.decide(
            snap(previous = "com.android.chrome", headsUpAt = now - 300, headsUpPkg = "com.other"),
        )
        assertEquals(LightOpenScene.APP_JUMP, d.scene)
    }

    @Test
    fun `一个应用里点开另一个应用算应用跳转`() {
        val d = LightOpenRules.decide(snap(previous = "com.android.chrome"))
        assertTrue(d.accept)
        assertEquals(LightOpenScene.APP_JUMP, d.scene)
    }

    @Test
    fun `场景开关关闭时不转窗`() {
        val d = LightOpenRules.decide(
            snap(
                previous = "com.android.chrome",
                scenes = setOf(LightOpenScene.NOTIF_HEADS_UP),
            ),
        )
        assertEquals(LightOpenReject.SCENE_OFF, d.reject)
        assertEquals(LightOpenScene.APP_JUMP, d.scene)
    }

    @Test
    fun `只开授权即放行跳转判据`() {
        val onlyAuth = LightOpenRules.decide(
            snap(previous = "com.android.chrome", scenes = setOf(LightOpenScene.AUTH)),
        )
        assertTrue(onlyAuth.accept)
        assertEquals(LightOpenScene.APP_JUMP, onlyAuth.scene)
    }

    @Test
    fun `只对轮盘应用生效时范围外不转窗`() {
        val d = LightOpenRules.decide(
            snap(
                previous = "com.android.chrome",
                wheelOnly = true,
                wheel = setOf("com.tencent.mobileqq"),
            ),
        )
        assertEquals(LightOpenReject.OUT_OF_SCOPE, d.reject)
    }

    @Test
    fun `只对轮盘应用生效时范围内正常转窗`() {
        val d = LightOpenRules.decide(
            snap(
                previous = "com.android.chrome",
                wheelOnly = true,
                wheel = setOf("com.tencent.mm"),
            ),
        )
        assertTrue(d.accept)
    }

    @Test
    fun `冷却期内不重复转窗`() {
        val d = LightOpenRules.decide(
            snap(previous = "com.android.chrome", lastConvertAt = now - 1_000),
        )
        assertEquals(LightOpenReject.COOLDOWN, d.reject)
    }

    @Test
    fun `冷却期结束后可以再次转窗`() {
        val d = LightOpenRules.decide(
            snap(previous = "com.android.chrome", lastConvertAt = now - 4_000),
        )
        assertTrue(d.accept)
    }

    @Test
    fun `统计键随结果变化`() {
        val ok = LightOpenRules.decide(snap(previous = "com.android.chrome"))
        assertEquals("app_jump|ok", ok.statKey())

        val rejected = LightOpenRules.decide(snap(previous = "com.miui.home"))
        assertEquals("none|scene-unknown", rejected.statKey())
    }

    @Test
    fun `时间窗对未发生的事件恒为假`() {
        assertFalse(LightOpenRules.within(now, 0L, 3_000L))
        assertTrue(LightOpenRules.within(now, now - 100, 3_000L))
        assertFalse(LightOpenRules.within(now, now + 100, 3_000L))
    }

    @Test
    fun `场景按 key 反查`() {
        assertEquals(LightOpenScene.NOTIF_HEADS_UP, LightOpenScene.of("notif_heads_up"))
        assertNull(LightOpenScene.of("nope"))
    }

    @Test
    fun `从轮盘点图标启动时不被当成应用跳转`() {
        val d = LightOpenRules.decide(
            snap(
                foreground = "com.taobao.taobao",
                previous = "com.tencent.mm",
                manualAt = now - 300,
            ),
        )
        assertFalse(d.accept)
        assertEquals(LightOpenReject.MANUAL, d.reject)
    }

    @Test
    fun `手动启动保护期过后照常判定`() {
        val d = LightOpenRules.decide(
            snap(
                foreground = "com.taobao.taobao",
                previous = "com.tencent.mm",
                manualAt = now - LightOpenRules.MANUAL_GRACE_MS - 1,
            ),
        )
        assertTrue(d.accept)
        assertEquals(LightOpenScene.APP_JUMP, d.scene)
    }

    @Test
    fun `冷启动慢时按包名继续拦住`() {
        val d = LightOpenRules.decide(
            snap(
                foreground = "com.taobao.taobao",
                previous = "com.tencent.mm",
                manualAt = now - 5_000,
                manualPkg = "com.taobao.taobao",
            ),
        )
        assertFalse(d.accept)
        assertEquals(LightOpenReject.MANUAL, d.reject)
    }

    @Test
    fun `包名宽限不牵连别的应用`() {
        val d = LightOpenRules.decide(
            snap(
                foreground = "com.taobao.taobao",
                previous = "com.tencent.mm",
                manualAt = now - 5_000,
                manualPkg = "com.jd.jrapp",
            ),
        )
        assertTrue(d.accept)
        assertEquals(LightOpenScene.APP_JUMP, d.scene)
    }

    @Test
    fun `共用判据的两项联动成一组`() {
        assertEquals(LightOpenScene.SHARED, LightOpenScene.linkedTo(LightOpenScene.AUTH))
        assertEquals(LightOpenScene.SHARED, LightOpenScene.linkedTo(LightOpenScene.APP_JUMP))
        assertEquals(2, LightOpenScene.SHARED.size)
        // 不共用判据的场景不受影响
        assertEquals(
            setOf(LightOpenScene.NOTIF_HEADS_UP),
            LightOpenScene.linkedTo(LightOpenScene.NOTIF_HEADS_UP),
        )
    }

    @Test
    fun `只开登录授权时应用跳转也放行`() {
        val d = LightOpenRules.decide(
            snap(
                foreground = "com.taobao.taobao",
                previous = "com.tencent.mm",
                scenes = setOf(LightOpenScene.AUTH),
            ),
        )
        assertTrue(d.accept)
        assertEquals(LightOpenScene.APP_JUMP, d.scene)
    }

    @Test
    fun `授权流程中同一包的后续窗口变化不再转窗`() {
        val d = LightOpenRules.decide(
            snap(
                previous = "com.android.chrome",
                flowTarget = "com.tencent.mm",
                flowAt = now - 8_000,
            ),
        )
        assertFalse(d.accept)
        assertEquals(LightOpenReject.FLOW_LOCK, d.reject)
    }

    @Test
    fun `流程锁到期后可以照常判定`() {
        val d = LightOpenRules.decide(
            snap(
                previous = "com.miui.home",
                flowTarget = "com.tencent.mm",
                flowAt = now - 60_000,
            ),
        )
        assertEquals(LightOpenReject.SCENE_UNKNOWN, d.reject)
    }

    @Test
    fun `流程锁只锁目标包与来源包`() {
        val d = LightOpenRules.decide(
            snap(
                foreground = "com.android.chrome",
                previous = "com.tencent.mm",
                flowTarget = "com.tencent.mm",
                flowAt = now - 1_000,
            ),
        )
        assertNotEquals(LightOpenReject.FLOW_LOCK, d.reject)

        val back = LightOpenRules.decide(
            snap(
                foreground = "com.xingin.xhs",
                previous = "com.tencent.mm",
                flowTarget = "com.tencent.mm",
                flowSource = "com.xingin.xhs",
                flowAt = now - 8_000,
            ),
        )
        assertFalse(back.accept)
        assertEquals(LightOpenReject.FLOW_LOCK, back.reject)
    }

    @Test
    fun `只有授权加流程锁`() {
        assertTrue(LightOpenRules.needsFlowLock(LightOpenScene.AUTH))
        assertFalse(LightOpenRules.needsFlowLock(LightOpenScene.APP_JUMP))
        assertFalse(LightOpenRules.needsFlowLock(LightOpenScene.NOTIF_HEADS_UP))
        assertFalse(LightOpenRules.needsFlowLock(null))
    }

    @Test
    fun `统计串往返一致`() {
        val map = linkedMapOf("app_jump|ok" to 3, "notif_heads_up|cooldown" to 1)
        val raw = LightOpenStatsCodec.encode(map)
        assertEquals(map, LightOpenStatsCodec.decode(raw))
    }

    @Test
    fun `计数为零的键不写入统计串`() {
        assertEquals("", LightOpenStatsCodec.encode(mapOf("a|ok" to 0)))
    }

    @Test
    fun `坏统计串读取时不抛异常`() {
        val map = LightOpenStatsCodec.decode("garbage;=3;ok=notanumber;a|ok=1")
        assertEquals(1, map["a|ok"])
        assertEquals(1, map.size)
    }
}
