package com.pilot.wheel

import com.pilot.wheel.core.WindowStateParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * dumpsys 解析。样本按 AOSP 13/14 的实际输出结构构造，
 * 重点是三件事不能错：认对 task、取到栈顶 Activity、读出窗口模式。
 */
class WindowStateParserTest {

    private val dump = """
        ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)
        Display #0 (activities from top to bottom):

          * Task{8b1a2c3 #456 type=standard A=10345:com.tencent.mm u=0 visible=true mode=fullscreen translucent=false sz=2}
            mLastPausedActivity: ActivityRecord{111 u0 com.tencent.mm/.ui.LauncherUI t456}
            * Hist  #1: ActivityRecord{222 u0 com.tencent.mm/.plugin.webview.ui.tools.WebViewUI t456}
              packageName=com.tencent.mm processName=com.tencent.mm
              realActivity=com.tencent.mm/.plugin.webview.ui.tools.WebViewUI
            * Hist  #0: ActivityRecord{111 u0 com.tencent.mm/.ui.LauncherUI t456}
              realActivity=com.tencent.mm/.ui.LauncherUI

          * Task{c9d8e7f #789 type=standard A=10123:com.android.chrome u=0 visible=false mode=fullscreen translucent=false sz=1}
            realActivity=com.android.chrome/com.google.android.apps.chrome.Main

          * Task{aa11bb2 #790 type=standard A=10999:com.tencent.mmp u=0 visible=false mode=fullscreen}
            realActivity=com.tencent.mmp/.Main
    """.trimIndent()

    @Test
    fun findTaskId_取到目标应用的task() {
        assertEquals("456", WindowStateParser.findTaskId(dump, "com.tencent.mm"))
        assertEquals("789", WindowStateParser.findTaskId(dump, "com.android.chrome"))
    }

    @Test
    fun findTaskId_包名前缀不同不会误匹配() {
        // com.tencent.mm 不能命中 com.tencent.mmp
        assertEquals("790", WindowStateParser.findTaskId(dump, "com.tencent.mmp"))
    }

    @Test
    fun findTaskIds_认出微信分享时的两个task() {
        // 分享流程里「选择聊天」与「聊天页」是两个 task，只改其中一个会叠出双层窗口
        val two = dump + """
          * Task{b0c1d2e #912 type=standard A=10345:com.tencent.mm u=0 visible=true mode=fullscreen}
            realActivity=com.tencent.mm/.ui.ChattingUI
        """.trimIndent()
        assertEquals(listOf("456", "912"), WindowStateParser.findTaskIds(two, "com.tencent.mm"))
        assertEquals(listOf("456"), WindowStateParser.findTaskIds(dump, "com.tencent.mm"))
        assertEquals(emptyList<String>(), WindowStateParser.findTaskIds(dump, "com.none.app"))
    }

    @Test
    fun findTopActivity_取栈顶而不是栈底() {
        assertEquals(
            "com.tencent.mm/.plugin.webview.ui.tools.WebViewUI",
            WindowStateParser.findTopActivity(dump, "com.tencent.mm"),
        )
    }

    @Test
    fun windowingMode_读出全屏() {
        assertEquals("fullscreen", WindowStateParser.windowingMode(dump, "com.tencent.mm"))
        assertFalse(WindowStateParser.isFreeform(WindowStateParser.windowingMode(dump, "com.tencent.mm")))
    }

    @Test
    fun windowingMode_读出浮窗() {
        val free = dump.replace("mode=fullscreen", "mode=freeform")
        assertTrue(WindowStateParser.isFreeform(WindowStateParser.windowingMode(free, "com.tencent.mm")))
    }

    @Test
    fun isFreeform_认荣耀私有轻窗与AOSP数字模式() {
        // 用户报障双窗口：dumpsys 输出 mode=102（荣耀轻窗）/mode=5（AOSP freeform）时，
        // 旧判据只认字符串 "freeform"，把已是浮窗的 task 当全屏，重复转窗叠出第二个窗口。
        assertTrue(WindowStateParser.isFreeform("102"))
        assertTrue(WindowStateParser.isFreeform("5"))
        assertTrue(WindowStateParser.isFreeform("freeform"))
        assertFalse(WindowStateParser.isFreeform("fullscreen"))
        assertFalse(WindowStateParser.isFreeform("1"))
        assertFalse(WindowStateParser.isFreeform(null))
    }

    @Test
    fun windowingMode_兼容windowingMode字段() {
        // 部分版本在 ActivityRecord 行输出 windowingMode= 而不是 Task 行的 mode=
        val alt = """
            * Task{8b1a2c3 #456 type=standard A=10345:com.example u=0 visible=true}
              * Hist  #0: ActivityRecord{111 u0 com.example/.Main t456}
                windowingMode=freeform
        """.trimIndent()
        assertEquals("freeform", WindowStateParser.windowingMode(alt, "com.example"))
    }

    @Test
    fun 找不到目标应用时返回空() {
        assertNull(WindowStateParser.findTaskId(dump, "com.not.installed"))
        assertNull(WindowStateParser.findTopActivity(dump, "com.not.installed"))
        assertNull(WindowStateParser.windowingMode(dump, "com.not.installed"))
    }

    @Test
    fun 空输出不抛异常() {
        assertEquals(0, WindowStateParser.taskBlocks("").size)
        assertNull(WindowStateParser.findTaskId("", "com.tencent.mm"))
    }

    @Test
    fun taskId只认头行_正文里的井号不抢答() {
        // 正文（Hist 行 / 耗时统计）里可能出现 #N，旧实现取整块第一个 #N 会取错
        val body = """
            * Task{8b1a2c3 #456 type=standard A=10345:com.example u=0 visible=true mode=fullscreen}
              * Hist  #99: ActivityRecord{333 u0 com.example/.Main t456}
                realActivity=com.example/.Main
        """.trimIndent()
        assertEquals("456", WindowStateParser.findTaskId(body, "com.example"))
    }

    @Test
    fun 尾部非task段落提到包名不会顶替最后一个task() {
        // 真机现象：同一份 dump 里 li.songe.gkd 与 com.tencent.mm 都解析成 task 7，
        // resize 打到别人的窗口上。根因是最后一个 Task 块的缓冲区把 dumpsys 尾部
        // （mFocusedApp / 最近任务 / 历史栈）一起吞了，那些段落里提到别的包名。
        val polluted = """
            * Task{8b1a2c3 #7 type=standard A=10345:com.tencent.mm u=0 visible=true mode=fullscreen}
              realActivity=com.tencent.mm/.ui.LauncherUI

            mFocusedApp=ActivityRecord{444 u0 li.songe.gkd/.ui.MainActivity t7}
            topResumedActivity=ActivityRecord{444 u0 li.songe.gkd/.ui.MainActivity t7}
        """.trimIndent()
        assertEquals(listOf("7"), WindowStateParser.findTaskIds(polluted, "com.tencent.mm"))
        assertEquals(emptyList<String>(), WindowStateParser.findTaskIds(polluted, "li.songe.gkd"))
    }

    @Test
    fun isAnyFreeform_同包多task任一是浮窗即算成功() {
        val mixed = dump + """
          * Task{b0c1d2e #912 type=standard A=10345:com.tencent.mm u=0 visible=true mode=freeform}
            realActivity=com.tencent.mm/.ui.ChattingUI
        """.trimIndent()
        assertTrue(WindowStateParser.isAnyFreeform(mixed, "com.tencent.mm"))
        assertFalse(WindowStateParser.isAnyFreeform(dump, "com.tencent.mm"))
        assertFalse(WindowStateParser.isAnyFreeform(dump, "com.not.installed"))
    }

    @Test
    fun windowingModes_与task列表同序() {
        val mixed = dump + """
          * Task{b0c1d2e #912 type=standard A=10345:com.tencent.mm u=0 visible=true mode=102}
            realActivity=com.tencent.mm/.ui.ChattingUI
        """.trimIndent()
        assertEquals(listOf("fullscreen", "102"), WindowStateParser.windowingModes(mixed, "com.tencent.mm"))
    }

    @Test
    fun 没有realActivity时退回ActivityRecord() {
        val noReal = """
            * Task{8b1a2c3 #456 type=standard A=10345:com.example u=0 visible=true mode=fullscreen}
              * Hist  #0: ActivityRecord{9f8e u0 com.example/.OnlyRecord t456}
        """.trimIndent()
        assertEquals("com.example/.OnlyRecord", WindowStateParser.findTopActivity(noReal, "com.example"))
    }
}
