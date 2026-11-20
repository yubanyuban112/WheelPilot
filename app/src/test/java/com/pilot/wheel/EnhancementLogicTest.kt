package com.pilot.wheel

import com.pilot.wheel.core.StackListParser
import com.pilot.wheel.core.VendorScale
import com.pilot.wheel.core.WindowGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A4 / A5 两个纯逻辑点的行为基线。
 *
 * 这两处都不碰 Android 框架，因此可以在 JVM 上直接验证：
 * - 厂商尺寸补偿（A4）：补偿后必须仍在屏幕内；
 * - 残留窗口清理（A5）：只清 visible=false，且同包必须有可见窗口。
 */
class EnhancementLogicTest {

    // ------------------------------------------------------------------ A4

    @Test
    fun `default scale keeps native geometry unchanged`() {
        val r = WindowGeometry.freeformBounds(1272, 2800, 100)!!
        assertEquals((1272 * WindowGeometry.PORTRAIT_WIDTH_FACTOR).toInt(), r.width)
    }

    @Test
    fun `vendor scale enlarges window but never leaves screen`() {
        val normal = WindowGeometry.freeformBounds(1272, 2800, 100)!!
        val scaled = WindowGeometry.freeformBounds(1272, 2800, 120)!!
        assertTrue("补偿后应更宽", scaled.width > normal.width)
        assertTrue("右边界不得越界", scaled.right <= 1272)
        assertTrue("左边界不得为负", scaled.left >= 0)
        assertTrue("底边不得越界", scaled.bottom <= 2800)
    }

    @Test
    fun `vendor scale is clamped to sane range`() {
        val tooBig = WindowGeometry.freeformBounds(1272, 2800, 999)!!
        assertTrue(tooBig.right <= 1272)
        val tooSmall = WindowGeometry.freeformBounds(1272, 2800, 1)!!
        assertTrue(tooSmall.width > 0)
    }

    @Test
    fun `scale defaults follow brand`() {
        assertEquals(VendorScale.HONOR, VendorScale.defaultPercent("HONOR", "HONOR"))
        assertEquals(VendorScale.HONOR, VendorScale.defaultPercent("HUAWEI", "HUAWEI"))
        assertEquals(VendorScale.XIAOMI, VendorScale.defaultPercent("Xiaomi", "Redmi"))
        assertEquals(VendorScale.DEFAULT, VendorScale.defaultPercent("samsung", "samsung"))
    }

    @Test
    fun `content bounds also respect vendor scale`() {
        val scaled = WindowGeometry.contentBounds(1272, 2800, 130)!!
        assertTrue(scaled.right <= 1272)
        assertTrue(scaled.bottom <= 2800)
    }

    // ------------------------------------------------------------------ A5

    private val dump = """
        * Task{8ba7f5a #12 type=standard A=10123:com.tencent.mm U=0 visible=true mode=freeform}
          ActivityRecord{aaa u0 com.tencent.mm/.ui.LauncherUI}
        * Task{7c1d2b3 #13 type=standard A=10123:com.tencent.mm U=0 visible=false mode=freeform}
          ActivityRecord{bbb u0 com.tencent.mm/.ui.chatting.ChattingUI}
        * Task{5d4e3f2 #14 type=standard A=10345:com.other.app U=0 visible=true mode=fullscreen}
          ActivityRecord{ccc u0 com.other.app/.Main}
    """.trimIndent()

    @Test
    fun `orphan cleanup only targets invisible task of same package`() {
        val ids = StackListParser.orphanTaskIds(dump, "com.tencent.mm", 0)
        assertEquals(listOf("13"), ids)
    }

    @Test
    fun `orphan cleanup skips package when no visible task`() {
        val onlyHidden = """
            * Task{aaaaaaa #21 type=standard A=10123:com.tencent.mm U=0 visible=false mode=fullscreen}
        """.trimIndent()
        assertTrue(StackListParser.orphanTaskIds(onlyHidden, "com.tencent.mm", 0).isEmpty())
    }

    @Test
    fun `orphan cleanup does nothing when visible flag missing`() {
        val noFlag = """
            * Task{aaaaaaa #31 type=standard A=10123:com.tencent.mm U=0 mode=freeform}
        """.trimIndent()
        assertTrue(StackListParser.orphanTaskIds(noFlag, "com.tencent.mm", 0).isEmpty())
    }

    @Test
    fun `orphan cleanup ignores other packages`() {
        assertTrue(StackListParser.orphanTaskIds(dump, "com.absent.pkg", 0).isEmpty())
    }

    @Test
    fun `user filter keeps duplicates apart`() {
        val clone = """
            * Task{aaaaaaa #41 type=standard A=10123:com.tencent.mm U=0 visible=true mode=freeform}
            * Task{bbbbbbb #42 type=standard A=10123:com.tencent.mm U=999 visible=false mode=freeform}
        """.trimIndent()
        // 只清用户 0 的残留：用户 999（分身）那条不该被动
        assertTrue(StackListParser.orphanTaskIds(clone, "com.tencent.mm", 0).isEmpty())
        assertEquals(listOf("42"), StackListParser.orphanTaskIds(clone, "com.tencent.mm", 999))
    }

    // ------------------------------------------------------------------ 解析健壮性

    @Test
    fun `window parse of empty dump stays empty`() {
        assertNull(StackListParser.findTask("", "com.tencent.mm"))
    }
}
