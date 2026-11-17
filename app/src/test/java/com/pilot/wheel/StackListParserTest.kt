package com.pilot.wheel

import com.pilot.wheel.core.StackListParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** `am stack list` 解析（StackListParser）行为基线测试 */
class StackListParserTest {

    private val sample = """
        *Task id=12
          displayId=0
          realActivity=com.android.launcher/.Launcher
        *Task id=35
          displayId=0
          realActivity=com.lihuan.bili/.ui.MainActivity
        *Task id=40
          displayId=0
          realActivity=com.other.app/.Main
    """.trimIndent()

    @Test
    fun `finds task id of target package`() {
        assertEquals("35", StackListParser.findTaskId(sample, "com.lihuan.bili"))
    }

    @Test
    fun `finds last task before package line when multiple tasks`() {
        assertEquals("40", StackListParser.findTaskId(sample, "com.other.app"))
    }

    @Test
    fun `package absent returns last seen task id (documented quirk)`() {
        // 当前实现语义：包名未出现时不返回 null，而是返回扫描到的最后一个 task id。
        // 实际调用时机（刚拉起目标应用后立刻查栈）下最后一个 task 即目标，行为可接受；
        // 本测试锁定该行为，防止重构时悄然改变语义。
        assertEquals("40", StackListParser.findTaskId(sample, "com.not.installed"))
    }

    @Test
    fun `empty output returns null`() {
        assertNull(StackListParser.findTaskId("", "com.lihuan.bili"))
    }
}
