package com.pilot.wheel

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import com.pilot.wheel.core.WindowStateParser

/**
 * 前台应用检测：用于验证荣耀 startActivityOneStep 是否真的把目标拉到前台。
 *
 * 该 API 是 void 返回，静默失败收不到报错（云闪付实测：invoke 成功但窗口不出）。
 * 普通应用查别的应用前台状态只有 UsageStatsManager 一条路，需要
 * 「使用情况访问权限」（AppOps，设置里手动授予，非运行时权限）。
 */
object ForegroundCheck {

    private const val OP_STR = AppOpsManager.OPSTR_GET_USAGE_STATS

    /** 是否已授予「使用情况访问权限」 */
    fun hasUsageAccess(context: Context): Boolean = runCatching {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        ops.checkOpNoThrow(OP_STR, Process.myUid(), context.packageName) ==
            AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    /** 跳转系统「使用情况访问」设置页 */
    fun openUsageAccessSettings(context: Context) {
        runCatching {
            context.startActivity(
                android.content.Intent(
                    android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS,
                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /**
     * 最近 withinMs 毫秒内最后进入前台的应用包名；查不到返回 null。
     * 只读 ACTIVITY_RESUMED 事件（minSdk 30 恒可用）。
     */
    fun currentForeground(context: Context, withinMs: Long): String? = runCatching {
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - withinMs, now)
        var last: String? = null
        val bucket = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(bucket)
            if (bucket.eventType == UsageEvents.Event.ACTIVITY_RESUMED) last = bucket.packageName
        }
        last
    }.getOrNull()

    // -------------------------------------------------------------------------
    // shell 验证通道：无 usage access 时，用特权 shell 的 dumpsys 输出确认
    // 目标是否真的被拉到前台。解析是纯函数，可单测；dumpsys 由 AppLauncher 执行。
    // -------------------------------------------------------------------------

    enum class ShellVerify { OK, FAILED, INCONCLUSIVE }

    // 只认「当前 resume」字段。绝不能带 *History —— 13:16:33 真机日志里 dump 高达
    // 75KB，历史行几十条，"取最后一条"会把上次打开的应用当成当前前台，误判失败回落。
    private val RESUMED_LINE = Regex(
        "(mResumedActivity|topResumedActivity|ResumedActivity)\\s*[:=][^\\n]*",
    )
    private val PKG_ACTIVITY = Regex("([a-zA-Z][\\w.]*)/[\\w.$]+")
    // 前台栈里会有系统 launcher / 本应用自己的轮盘 Activity，这些都算「不是目标」
    private val IGNORED = setOf("com.pilot.wheel", "com.android.launcher", "com.hihonor.android.launcher")
    private val VISIBLE_TRUE = Regex("\\bvisible=true\\b")
    private val TASK_START = Regex("\\* (Root)?Task\\{")

    /**
     * 从 `dumpsys activity activities` 文本里取 resume 栈顶部的应用包名。
     * 跳过所有 *History 行，只认当前 resume 字段；取最后一条（dumpsys 按底→顶打印）。
     */
    fun resumedPackageFromDump(dump: String): String? {
        var last: String? = null
        for (match in RESUMED_LINE.findAll(dump)) {
            if (match.value.contains("History", ignoreCase = true)) continue
            PKG_ACTIVITY.findAll(match.value).forEach { m ->
                val candidate = m.groupValues[1]
                if (candidate !in IGNORED) last = candidate
            }
        }
        return last
    }

    /**
     * 用 dumpsys 判断目标应用是否真的被拉起成浮窗。
     *
     * 判据从「resume 栈顶是不是目标」改为「目标自己的 task 是不是可见的浮窗」：
     * 前者在 75KB 大 dump / 小窗多焦点场景会被历史行和别的 resume 行带偏
     * （13:16:33 实测 dump=75207B 解析不出，误回落 mode 5）。后者只看目标 task，
     * 与栈顶是谁无关，直接对应「小窗真的出现了」。
     *
     * - dump 没有 task 段（内容不完整）→ INCONCLUSIVE
     * - 目标连 task 都没有 → FAILED / reason=TASK_ABSENT（102 没起来，或还在冷启动）
     * - 有 task 但形态是全屏 → FAILED / reason=NOT_FREEFORM（102 没生效）
     * - 有 task、形态是浮窗、但不可见 → INCONCLUSIVE（后台残留，保守回落）
     * - 有 task、浮窗、可见 → OK
     *
     * FAILED 有两种成因，调用方必须区别对待：`TASK_ABSENT` 在冷启动首轮
     * 是「还没起来」，值得再等一轮；`NOT_FREEFORM` 是「已经起来但形态不对」，
     * 全屏不会自己变浮窗，应立即回落。11-19 09:42 日志里知乎冷启 325ms
     * 判 FAILED、热启同样 322ms 却 OK，就是两者被混为一谈所致。
     */
    fun verifyViaShell(dump: String?, targetPkg: String): ShellVerify =
        verifyViaShellDetailed(dump, targetPkg).verdict

    /** 带成因的详细判定；[verifyViaShell] 是它的取值简写 */
    fun verifyViaShellDetailed(dump: String?, targetPkg: String): ShellVerifyDetail {
        if (dump.isNullOrBlank()) {
            return ShellVerifyDetail(ShellVerify.INCONCLUSIVE, VerifyReason.NO_DUMP)
        }
        if (!TASK_START.containsMatchIn(dump)) {
            return ShellVerifyDetail(ShellVerify.INCONCLUSIVE, VerifyReason.NO_TASK_SECTION)
        }
        val block = WindowStateParser.findBlock(dump, targetPkg)
            ?: return ShellVerifyDetail(ShellVerify.FAILED, VerifyReason.TASK_ABSENT)
        val mode = WindowStateParser.windowingMode(dump, targetPkg)
            ?: return ShellVerifyDetail(ShellVerify.INCONCLUSIVE, VerifyReason.MODE_UNREADABLE)
        if (!WindowStateParser.isFreeform(mode)) {
            return ShellVerifyDetail(ShellVerify.FAILED, VerifyReason.NOT_FREEFORM, mode)
        }
        if (!VISIBLE_TRUE.containsMatchIn(block)) {
            return ShellVerifyDetail(ShellVerify.INCONCLUSIVE, VerifyReason.INVISIBLE, mode)
        }
        return ShellVerifyDetail(ShellVerify.OK, VerifyReason.VISIBLE_FREEFORM, mode)
    }

    /** FAILED / INCONCLUSIVE 的具体成因，进日志用 */
    enum class VerifyReason { NO_DUMP, NO_TASK_SECTION, TASK_ABSENT, MODE_UNREADABLE, NOT_FREEFORM, INVISIBLE, VISIBLE_FREEFORM }

    data class ShellVerifyDetail(
        val verdict: ShellVerify,
        val reason: VerifyReason,
        val mode: String? = null,
    ) {
        /** 单行诊断文本，直接拼进日志 */
        fun describe(): String {
            val m = mode?.let { " mode=$it" } ?: ""
            return when (reason) {
                VerifyReason.NO_DUMP -> "dump-empty"
                VerifyReason.NO_TASK_SECTION -> "no-task-section"
                VerifyReason.TASK_ABSENT -> "task-absent$m"
                VerifyReason.MODE_UNREADABLE -> "mode-unreadable"
                VerifyReason.NOT_FREEFORM -> "not-freeform$m"
                VerifyReason.INVISIBLE -> "invisible$m"
                VerifyReason.VISIBLE_FREEFORM -> "ok$m"
            }
        }
    }
}
