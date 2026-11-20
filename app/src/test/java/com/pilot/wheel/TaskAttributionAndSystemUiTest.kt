package com.pilot.wheel

import com.pilot.wheel.core.StackListParser
import com.pilot.wheel.core.SystemUiWindow
import com.pilot.wheel.core.SystemUiWindows
import com.pilot.wheel.core.WindowStateParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本次重构新增判据的回归测试。
 *
 * 两类断言：
 * 1. **task 块归属**：dumpsys 尾部段落（mFocusedApp / 最近任务）会并入最后一个 Task 块，
 *    按整块 `contains` 匹配包名就会「查 A 包拿到 B 的 task id」。真机曾因此把 resize
 *    打到别人的窗口上。两个解析器必须给出同一结论。
 * 2. **系统界面归类**：QS / SHADE / 分享面板的类名判定下沉到 core 后可直接断言，
 *    不必再靠真机日志反复试。
 */
class TaskAttributionAndSystemUiTest {

    private val dump = """
        ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)
        Display #0 (activities from top to bottom):
          * Task{8b1a2c3 #456 type=standard A=10345:com.tencent.mm U=0 visible=true}
            mResumedActivity: ActivityRecord{abc u0 com.tencent.mm/.ui.LauncherUI t456}
            * Hist  #0: ActivityRecord{abc u0 com.tencent.mm/.ui.LauncherUI t456}
          * Task{9c2b3d4 #457 type=standard A=10456:com.example.other U=0 visible=false}
            realActivity=com.example.other/.MainActivity
        mFocusedApp=ActivityRecord{def u0 com.example.other/.MainActivity t457}
        Recent tasks:
          * Recent #0: Task{9c2b3d4 #457 com.example.other}
    """.trimIndent()

    @Test
    fun windowStateParserAttributesBlockByHeader() {
        assertEquals("456", WindowStateParser.findTaskId(dump, "com.tencent.mm"))
        assertEquals("457", WindowStateParser.findTaskId(dump, "com.example.other"))
    }

    @Test
    fun stackListParserAttributesBlockByHeader() {
        // 重构前这里是整块 containsMatchIn：同一份 dump 会把 com.example.other 解析成
        // 最后一个块（含尾部 mFocusedApp 段落）的 id，"com.tencent.mm" 也可能被带偏。
        assertEquals("456", StackListParser.findTaskId(dump, "com.tencent.mm"))
        assertEquals("457", StackListParser.findTaskId(dump, "com.example.other"))
    }

    @Test
    fun packageMentionedOnlyInTailIsNotATask() {
        val tailOnly = dump.replace(
            "mFocusedApp=ActivityRecord{def u0 com.example.other",
            "mFocusedApp=ActivityRecord{def u0 com.tail.only",
        )
        // 只在 mFocusedApp 段落出现过，它不是任何一个 task 的归属
        assertNull(WindowStateParser.findTaskId(tailOnly, "com.tail.only"))
        assertNull(StackListParser.findTaskId(tailOnly, "com.tail.only"))
    }

    @Test
    fun classifierSeparatesQuickSettingsAndShade() {
        assertEquals(
            SystemUiWindow.QS,
            SystemUiWindows.classify("com.android.systemui.qs.QSContainerImpl"),
        )
        assertEquals(
            SystemUiWindow.QS,
            SystemUiWindows.classify("com.miui.systemui.panel.MiuiQsPanel"),
        )
        assertEquals(
            SystemUiWindow.SHADE,
            SystemUiWindows.classify("com.android.systemui.shade.NotificationShadeWindowView"),
        )
        assertEquals(
            SystemUiWindow.SHADE,
            SystemUiWindows.classify("com.android.systemui.statusbar.phone.NotificationPanelView"),
        )
        assertEquals(SystemUiWindow.NONE, SystemUiWindows.classify(null))
        assertEquals(SystemUiWindow.NONE, SystemUiWindows.classify("com.tencent.mm.ui.LauncherUI"))
    }

    @Test
    fun chooserIsRecognisedByClassOrPackage() {
        assertTrue(SystemUiWindows.isChooser("android", "com.android.intentresolver.ChooserActivity"))
        assertTrue(SystemUiWindows.isChooser("com.android.intentresolver", null))
        assertTrue(SystemUiWindows.isChooser("com.google.android.intentresolver", null))
        assertTrue(SystemUiWindows.isChooser("com.miui.securitycenter", "com.miui.MiuiResolverActivity"))
        assertTrue(!SystemUiWindows.isChooser("com.tencent.mm", "com.tencent.mm.ui.LauncherUI"))
    }
}
