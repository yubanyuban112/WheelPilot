package com.pilot.wheel

import com.pilot.wheel.core.WindowStateParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * shell 验证通道的解析测试（纯函数，不依赖 Android 运行时）。
 *
 * 场景来自真机：startActivityOneStep 是 void，invoke 成功不代表窗口出现，
 * 所以必须用 dumpsys 判「resume 栈顶是不是目标 + 目标是不是浮窗」，
 * 任何一个环节读不出来都要保守回落，绝不能当成功。
 */
class ForegroundCheckTest {

    private fun dump(resumedPkg: String, mode: String = "freeform") = """
        ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)
        Display #0 (activities from top to bottom):
          ResumedActivity: ActivityRecord{abc u0 com.pilot.wheel/.QuickWheelActivity t1}
          * Task{8b1a2c3 #456 type=standard A=10345:$resumedPkg u=0 visible=true mode=$mode}
            realActivity=$resumedPkg/.MainActivity
            ResumedActivity: ActivityRecord{def u0 $resumedPkg/.MainActivity t456}
          * Task{c9d8e7f #789 type=standard A=10123:com.other.app u=0 visible=false mode=fullscreen}
            realActivity=com.other.app/.Main
    """.trimIndent()

    @Test
    fun resumedPackage_取栈顶目标而不是轮盘自己() {
        // 轮盘 Activity 先打印、目标后打印，必须取最后一条；且要跳过 com.pilot.wheel
        assertEquals("com.coolapk.market", ForegroundCheck.resumedPackageFromDump(dump("com.coolapk.market")))
    }

    @Test
    fun resumedPackage_大dump里的历史行不能带偏() {
        // 13:16:33 真机：dump 高达 75KB，几十条 ResumedActivityHistory 历史行。
        // 历史行里最后打开的是别的应用，绝不能被当成当前 resume 栈顶。
        val withHistory = dump("com.coolapk.market") + """

          ResumedActivityHistory #3:
            * Hist #0: ActivityRecord{ghi u0 com.zhihu.android/.MainActivity t999}
          ResumedActivityHistory #2:
            * Hist #0: ActivityRecord{jkl u0 com.legado.app/.Welcome t888}
          mResumedActivityHistory:
            ActivityRecord{mno u0 com.other.app/.Main t777}
        """.trimIndent()
        assertEquals("com.coolapk.market", ForegroundCheck.resumedPackageFromDump(withHistory))
    }

    @Test
    fun verifyViaShell_目标是前台浮窗判成功() {
        assertEquals(
            ForegroundCheck.ShellVerify.OK,
            ForegroundCheck.verifyViaShell(dump("com.coolapk.market", "freeform"), "com.coolapk.market"),
        )
        // 荣耀私有轻窗 102 同样算浮窗
        assertEquals(
            ForegroundCheck.ShellVerify.OK,
            ForegroundCheck.verifyViaShell(dump("com.coolapk.market", "102"), "com.coolapk.market"),
        )
    }

    @Test
    fun verifyViaShell_目标前台但全屏判失败() {
        // 102 没生效退化成普通全屏：前台确实是目标，但不是小窗 → 必须回落重试
        assertEquals(
            ForegroundCheck.ShellVerify.FAILED,
            ForegroundCheck.verifyViaShell(dump("com.coolapk.market", "fullscreen"), "com.coolapk.market"),
        )
    }

    @Test
    fun verifyViaShell_前台是别的应用判失败() {
        assertEquals(
            ForegroundCheck.ShellVerify.FAILED,
            ForegroundCheck.verifyViaShell(dump("com.other.app"), "com.coolapk.market"),
        )
    }

    @Test
    fun verifyViaShell_空dump或解析不出返回不确定() {
        assertEquals(
            ForegroundCheck.ShellVerify.INCONCLUSIVE,
            ForegroundCheck.verifyViaShell(null, "com.coolapk.market"),
        )
        assertEquals(
            ForegroundCheck.ShellVerify.INCONCLUSIVE,
            ForegroundCheck.verifyViaShell("", "com.coolapk.market"),
        )
        assertEquals(
            ForegroundCheck.ShellVerify.INCONCLUSIVE,
            ForegroundCheck.verifyViaShell("ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)", "com.coolapk.market"),
        )
    }

    @Test
    fun resumedPackage_无resume行返回null() {
        assertNull(ForegroundCheck.resumedPackageFromDump("* Task{1 #1 visible=true mode=fullscreen}"))
    }

    @Test
    fun mode读取与浮窗判定兼容荣耀私有102() {
        val d = dump("com.coolapk.market", "102")
        assertEquals("102", WindowStateParser.windowingMode(d, "com.coolapk.market"))
        assertEquals(true, WindowStateParser.isFreeform(WindowStateParser.windowingMode(d, "com.coolapk.market")))
    }

    @Test
    fun verifyViaShell_不可见的浮窗task返回不确定() {
        // 后台残留的旧小窗 task：形态是浮窗但 visible=false，不能当成这次拉起成功
        val invisible = dump("com.coolapk.market", "freeform")
            .replace("visible=true", "visible=false")
        assertEquals(
            ForegroundCheck.ShellVerify.INCONCLUSIVE,
            ForegroundCheck.verifyViaShell(invisible, "com.coolapk.market"),
        )
    }

    @Test
    fun verifyViaShell_无task段返回不确定() {
        // 13:16:33 那种 75KB dump 若结构异常（没有 Task 块）应保守回落，不能误判成功
        assertEquals(
            ForegroundCheck.ShellVerify.INCONCLUSIVE,
            ForegroundCheck.verifyViaShell("ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)\n some noise", "com.coolapk.market"),
        )
    }

    @Test
    fun verifyViaShell_大dump带历史行仍判定目标浮窗成功() {
        // 复现真机 13:16:30/38 成功那两次：历史行再多，只看目标 task 本身
        val withHistory = dump("com.coolapk.market", "102") + """

          ResumedActivityHistory #9:
            * Hist #0: ActivityRecord{xyz u0 com.zhihu.android/.Main t999}
        """.trimIndent()
        assertEquals(
            ForegroundCheck.ShellVerify.OK,
            ForegroundCheck.verifyViaShell(withHistory, "com.coolapk.market"),
        )
    }

    @Test
    fun verifyDetail_目标task不存在归为absent可重试() {
        // 11-19 09:42 知乎冷启动：点击后 325ms 查 dumpsys，知乎的 task 还没建出来。
        // 这种必须与「task 在但全屏」区分开 —— 前者要再等一轮，后者应立即回落。
        val detail = ForegroundCheck.verifyViaShellDetailed(dump("com.other.app"), "com.coolapk.market")
        assertEquals(ForegroundCheck.ShellVerify.FAILED, detail.verdict)
        assertEquals(ForegroundCheck.VerifyReason.TASK_ABSENT, detail.reason)
    }

    @Test
    fun verifyDetail_目标已在但不浮窗归为notFreeform不重试() {
        val detail = ForegroundCheck.verifyViaShellDetailed(
            dump("com.coolapk.market", "fullscreen"),
            "com.coolapk.market",
        )
        assertEquals(ForegroundCheck.ShellVerify.FAILED, detail.verdict)
        assertEquals(ForegroundCheck.VerifyReason.NOT_FREEFORM, detail.reason)
        assertEquals(true, detail.mode != null)
    }

    @Test
    fun verifyDetail_浮窗可见带出mode供日志() {
        val detail = ForegroundCheck.verifyViaShellDetailed(dump("com.coolapk.market", "102"), "com.coolapk.market")
        assertEquals(ForegroundCheck.ShellVerify.OK, detail.verdict)
        assertEquals(ForegroundCheck.VerifyReason.VISIBLE_FREEFORM, detail.reason)
        assertEquals(true, detail.describe().startsWith("ok"))
    }
}
