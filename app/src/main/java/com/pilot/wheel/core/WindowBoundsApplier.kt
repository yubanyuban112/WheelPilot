package com.pilot.wheel.core

import android.content.Context
import com.pilot.wheel.AppLauncher

/**
 * 「把 task 拉到指定矩形」的唯一实现。
 *
 * 重构前同一件事有两份实现，命令表还不一致：
 *
 * | 实现 | 命令序列 |
 * |---|---|
 * | `AppLauncher.applyBounds` | binder → `am task resize id l,t,r,b` → `am stack resize` → `am task resize id w h` |
 * | `LightOpenController.resizeTask` | `am task resizeable id 2` → `am task resize id l,t,r,b` → `am stack resize` |
 *
 * 于是「轮盘启动」会先放开可调整再 resize，而「轻量打开」不做这一步；两条路径在同一台
 * 机器上的成功率因此不同，排查时却只有两份互不相同的日志可看。
 *
 * 这里合并为：**binder 优先 → resizeable 标记 → 三条 CLI 模板**，返回结构化结果，
 * 由调用方决定日志怎么打（保留原来「把每条命令输出写进日志」的诊断能力）。
 */
object WindowBoundsApplier {

    private const val CLI_TIMEOUT_MS = 5_000L

    /**
     * CLI 兜底模板，按「最可能生效」排序。
     *
     * - `am task resize` 在 MagicOS 上被砍掉（NumberFormatException）；
     * - `am stack resize` 在 Android 12+ 随 `am stack` 子命令一起移除；
     * - 最后一条 `w h` 形态是少数旧 ROM 的唯一可用写法。
     *
     * 三条都不成立时 binder 是唯一通道，这也是它排在第一位的理由。
     */
    private val RESIZE_TEMPLATES: List<(String, WindowRect) -> String> = listOf(
        { id, r -> "am task resize $id ${r.left},${r.top},${r.right},${r.bottom}" },
        { id, r -> "am stack resize $id ${r.left},${r.top},${r.right},${r.bottom}" },
        { id, r -> "am task resize $id ${r.width} ${r.height}" },
    )

    /** 一次 resize 的完整过程，供日志原样展开 */
    data class Report(
        val binderOk: Boolean,
        val attempts: List<String>,
    ) {
        /** 单行摘要：`binder-ok` 或 `am task resize=ok,am stack resize=Unknown command...` */
        val detail: String
            get() = if (binderOk) "binder-ok" else attempts.joinToString(",")
    }

    /**
     * 把 [taskId] 拉到 [bounds]。
     *
     * 成功判据只认两件事：binder 返回 true，或 CLI 退出码为 0。**不看 stdout 里的成功字样**
     * —— `am` 被系统拦截时退出码同样是 0，真实形态由调用方用 dumpsys 校验。
     */
    suspend fun apply(
        context: Context,
        taskId: String,
        bounds: WindowRect,
    ): Report {
        // 优先走 binder：MagicOS 上三条 CLI 必然全败，能生效就不再白跑
        if (TaskBinderBridge.resize(context, taskId, bounds)) {
            return Report(binderOk = true, attempts = emptyList())
        }
        val attempts = ArrayList<String>(RESIZE_TEMPLATES.size + 1)
        // 放开可调整：声明 resizeableActivity=false 的应用，不先标记则 resize 无效
        attempts += runCli("am task resizeable", "am task resizeable $taskId 2")
        for (template in RESIZE_TEMPLATES) {
            val cmd = template(taskId, bounds)
            val entry = runCli(cmd.substringBefore(" $taskId"), cmd)
            attempts += entry
            if (entry.endsWith("=ok")) break
        }
        return Report(binderOk = false, attempts = attempts)
    }

    /**
     * 只打「可调整」标记，不改几何。
     *
     * 用在启动前的预检：`resizeableActivity=false` 的应用若等启动后再改，
     * 已经按全屏铺开，之后的 resize 对它的 task 无效。
     */
    suspend fun markResizeable(taskId: String): Boolean =
        runCli("am task resizeable", "am task resizeable $taskId 2") == "am task resizeable=ok"

    /** 单条 CLI 的执行与压缩记录：`名字=ok` / `名字=错误摘要` / `名字=shell-dead` */
    private suspend fun runCli(name: String, cmd: String): String {
        val r = AppLauncher.exec(cmd, CLI_TIMEOUT_MS) ?: return "$name=shell-dead"
        if (r.exitCode == 0) return "$name=ok"
        return "$name=${r.stderr.take(80).ifBlank { "exit=${r.exitCode}" }}"
    }
}
