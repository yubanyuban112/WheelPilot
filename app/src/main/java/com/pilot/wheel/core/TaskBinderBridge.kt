package com.pilot.wheel.core

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.pilot.wheel.AppLauncher
import com.pilot.wheel.PilotShellClient

/**
 * 以 shell 身份操作 task 的通道，两条路：
 *
 * 1. **常驻 binder（首选）** —— [PilotShellClient] 绑一个 Shizuku UserService，
 *    进程常驻，每次操作只是一次 binder 往返（个位数毫秒）。
 * 2. **app_process 冷启（兜底）** —— 每次 fork 一个 JVM 加载 [MAIN_CLASS]，
 *    数百毫秒，但在 Shizuku 不可用、只有 priv-kit 特权服务时仍能用。
 *
 * 为什么需要这条通道（而不是用 `am` 命令）：`am start -n <栈顶Activity> --windowingMode 5`
 * 会做 exported 检查，微信分享页与小红书详情页都是未导出的内部 Activity，从 shell 拉
 * 必然 SecurityException；而 `setTaskWindowingMode` 是 task 级操作，没有这层检查，
 * 能把「已经在全屏的 task」原地改成浮窗，不重投 Intent、不动 Activity 栈、
 * resultTo 链完整保留。
 */
object TaskBinderBridge {

    private const val TAG = "WheelPilot"

    /** shell 侧 app_process 入口，必须与 proguard 的 keep 规则保持一致 */
    private const val MAIN_CLASS = "com.pilot.wheel.shell.AtmCli"

    /** 冷启通道失效后的冷却时间：冷却是为避免每次转窗都白等一次 app_process */
    private const val RETRY_GAP_MS = 60_000L

    /** app_process 启动 + binder 往返的合理上限 */
    private const val TIMEOUT_MS = 8_000L

    @Volatile
    private var disabledAt: Long = Long.MIN_VALUE / 2

    /** 通道当前是否可尝试（冷却期内直接走老路，不浪费时间） */
    fun usable(): Boolean =
        PilotShellClient.available() || SystemClock.elapsedRealtime() - disabledAt > RETRY_GAP_MS

    /** 通道现状，写日志用：binder-bound / app-process / cooling */
    fun describe(): String =
        if (PilotShellClient.available()) "binder-bound" else if (usable()) "app-process" else "cooling"

    /** 把已有 task 原地改成指定窗口模式（5 = AOSP freeform，100 = 厂商私有） */
    suspend fun setWindowingMode(
        context: Context,
        taskId: String,
        mode: Int,
        onTop: Boolean = true,
    ): Boolean {
        val id = taskId.toIntOrNull() ?: return badTaskId("windowing", taskId)
        if (PilotShellClient.setWindowingMode(id, mode, onTop)) return true
        return call(context, "windowing", taskId, mode.toString())
    }

    /** 只改几何：把 task 拉到指定矩形（不改窗口模式） */
    suspend fun resize(context: Context, taskId: String, bounds: WindowRect): Boolean {
        val id = taskId.toIntOrNull() ?: return badTaskId("resize", taskId)
        if (PilotShellClient.resize(id, bounds)) return true
        return call(
            context,
            "resize",
            taskId,
            "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}",
        )
    }

    /**
     * 重聚焦：先失焦再设焦点（A3）。
     * 用于「已在后台的任务转窗后回到应用入口页」——moveTaskToFront 会重置栈，
     * setFocusedTask 只改焦点，不回栈底。
     */
    suspend fun refocus(context: Context, taskId: String): Boolean {
        val id = taskId.toIntOrNull() ?: return badTaskId("refocus", taskId)
        return runCatching { PilotShellClient.refocus(id) }.getOrDefault(false)
    }

    /** 移除残留 task（A5 清理用） */
    suspend fun removeTask(context: Context, taskId: String): Boolean {
        val id = taskId.toIntOrNull() ?: return badTaskId("remove", taskId)
        return runCatching { PilotShellClient.removeTask(id) }.getOrDefault(false)
    }

    /**
     * 以 shell 身份启动到指定窗口模式（A2）。
     * 只有常驻通道能提供这条能力 —— app_process 通道需要重建 Intent，语义不同。
     */
    suspend fun startAsUser(
        context: Context,
        pkg: String,
        cls: String,
        userId: Int,
        windowingMode: Int,
        bounds: WindowRect?,
        quiet: Boolean = true,
    ): Boolean {
        if (!PilotShellClient.possible()) return false
        return PilotShellClient.startAsUser(pkg, cls, userId, windowingMode, bounds, quiet)
    }

    /**
     * 读任务表：优先走常驻进程的 dumpsys（省一次 priv-kit 往返），失败退回原路。
     */
    suspend fun dumpTasks(context: Context?): String? =
        PilotShellClient.dumpTasks() ?: AppLauncher.shellStdout("dumpsys activity activities")

    // -----------------------------------------------------------------------

    private fun badTaskId(op: String, taskId: String): Boolean {
        Log.d(TAG, "atmbinder $op skipped: bad taskId=$taskId")
        return false
    }

    private suspend fun call(context: Context, vararg argv: String): Boolean {
        if (SystemClock.elapsedRealtime() - disabledAt <= RETRY_GAP_MS) return false
        val result = AppLauncher.runAppProcess(context, MAIN_CLASS, argv.toList(), TIMEOUT_MS)
        if (result == null) {
            markDown("no-result")
            return false
        }
        val ok = result.exitCode == 0 && result.stdout.startsWith("OK")
        val brief = "exit=${result.exitCode} out=${result.stdout.take(120)} " +
            "err=${result.stderr.take(120)}"
        Log.d(TAG, "atmbinder ${argv.joinToString(" ")} -> $brief")
        if (!ok) markDown(brief)
        return ok
    }

    private fun markDown(reason: String) {
        disabledAt = SystemClock.elapsedRealtime()
        Log.d(TAG, "atmbinder disabled ${RETRY_GAP_MS / 1000}s: $reason")
    }
}
