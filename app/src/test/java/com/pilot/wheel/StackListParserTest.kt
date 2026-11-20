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
    fun `package absent returns null`() {
        // 29 号修正：旧实现在包名未出现时返回「最后扫描到的 task id」，调用方拿它去
        // resize 会改到另一个应用。启动后改为轮询任务表，首轮目标可能尚未出现，
        // 旧语义会把错误 task 当目标，故改为找不到即 null。
        assertNull(StackListParser.findTaskId(sample, "com.not.installed"))
    }

    @Test
    fun `parses task id mode and user from modern block format`() {
        // Android 10+ 的 `* Task{hash #id ... U=0 ... mode=freeform}` 行格式
        val modern = """
            Display #0 (activities from top to bottom):
              * Task{8ba7f5a #176 type=standard A=10123:com.tencent.mm U=0 visible=true mode=freeform}
                bounds=[80,300][900,1500]
              * Task{9cc1b2d #209 type=standard A=10999:com.tencent.mm U=999 visible=true mode=fullscreen}
        """.trimIndent()

        val main = StackListParser.findTask(modern, "com.tencent.mm", userId = 0)
        assertEquals("176", main?.taskId)
        assertEquals("freeform", main?.mode)
        assertEquals(0, main?.userId)

        // 分身（U=999）与原体同包名，按用户号必须取到另一条
        val clone = StackListParser.findTask(modern, "com.tencent.mm", userId = 999)
        assertEquals("209", clone?.taskId)
    }

    @Test
    fun `parses real dumpsys activity activities block`() {
        // 按 Android 12+ `dumpsys activity activities` 实际格式构造（含 ActivityRecord
        // 子行、translucent/sz 尾部字段、以及非 Task 的 RecentTaskInfo 干扰行）。
        // 任务表轮询与 preflight 的数据源已从 `am stack list`（Android 12+ 已移除）
        // 改为这份 dump，解析必须对得上，否则确认环节永远 task-not-found。
        val dump = """
            ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)
            Display #0 (activities from top to bottom):
              RootTask id=1
                * Task{2c1e8a3 #176 type=standard A=10234:com.zhihu.android U=0 visible=true mode=freeform translucent=true sz=1}
                  mResumedActivity: ActivityRecord{1a2b3c4 u0 com.zhihu.android/.app.ui.activity.LauncherActivity t176}
                  * ActivityRecord{1a2b3c4 u0 com.zhihu.android/.app.ui.activity.LauncherActivity t176}
                * Task{9cc1b2d #209 type=standard A=10999:com.zhihu.android U=999 visible=false mode=fullscreen}
              * RecentTaskInfo{abcdef #305 visible=false}
        """.trimIndent()

        val main = StackListParser.findTask(dump, "com.zhihu.android", userId = 0)
        assertEquals("176", main?.taskId)
        assertEquals("freeform", main?.mode)
        assertEquals(true, main?.visible)

        val clone = StackListParser.findTask(dump, "com.zhihu.android", userId = 999)
        assertEquals("209", clone?.taskId)
        assertEquals(false, clone?.visible)
    }

    @Test
    fun `empty output returns null`() {
        assertNull(StackListParser.findTaskId("", "com.lihuan.bili"))
    }
}
