package com.pilot.wheel.core

/**
 * `am stack list` 输出解析（纯函数，可单测）。
 *
 * 不同 Android 版本的 task 行格式不一致，两种都认：
 * - Android 10+：`* Task{8ba7f5a #123 type=standard A=10123:com.tencent.mm U=0 ... mode=fullscreen}`
 * - 旧版 / 部分 ROM：`* Task id=123`
 *
 * taskId 三种写法（`Task id=` / `taskId=` / `#N`）、窗口模式两种写法
 * （`mode=` / `mWindowingMode=`）、用户号两种写法（`U=` / `userId=`）全部兼容；
 * 任何一项解析不出就留空，由上层降级，不抛异常、不误判。
 */
object StackListParser {

    /**
     * 一个 task 的关键信息；[mode] / [userId] / [visible] 解析不出时为 null。
     *
     * [visible] 是 A5（残留窗口清理）的唯一判据：`dumpsys activity activities` 的
     * Task 头行里有 `visible=true/false`，false 表示这个 task 当前没有被渲染 ——
     * 小窗转窗失败留下的「隐形窗口」正是这种。
     */
    data class TaskEntry(
        val taskId: String,
        val mode: String?,
        val userId: Int?,
        val visible: Boolean? = null,
    )

    private val TASK_LINE = Regex("^\\s*\\*\\s*(?:Root)?Task")
    private val TASK_ID_EQ = Regex("(?:Task id|taskId)=(\\d+)")
    private val TASK_HASH = Regex("#(\\d+)")
    // 先匹配长名，避免 mWindowingMode 被 mode= 分支漏掉（mode= 对 Mode= 大小写不匹配）
    private val MODE = Regex("(?:mWindowingMode|windowingMode|mode)=([\\w-]+)")
    private val USER = Regex("(?:userId|U)=(\\d+)")
    private val VISIBLE = Regex("visible=(true|false)")

    /** 包名整词匹配，避免 com.tencent.mm 命中 com.tencent.mmp */
    private fun pkgRegex(pkg: String): Regex = WindowStateParser.pkgMatcher(pkg)

    /**
     * 把输出按 task 块切开；第一个 task 之前的内容（Display 头）丢弃。
     * 块边界看行首 `* Task`，不用 `taskId=` 判断 —— 后者在 configuration 行也会出现。
     */
    fun taskBlocks(stdout: String): List<String> {
        val out = ArrayList<String>()
        val buf = StringBuilder()
        stdout.lineSequence().forEach { line ->
            val start = TASK_LINE.containsMatchIn(line)
            if (start) {
                if (buf.isNotEmpty()) out.add(buf.toString())
                buf.setLength(0)
            }
            if (start || buf.isNotEmpty()) buf.append(line).append('\n')
        }
        if (buf.isNotEmpty()) out.add(buf.toString())
        return out
    }

    /**
     * 目标包名所属 task。
     *
     * [userId] 给负数表示不区分用户；给了具体值时优先匹配同用户号的 task。
     * 分身与原体包名相同，只能靠用户号区分，否则会 resize 到另一个分身。
     */
    fun findTask(stdout: String, pkg: String, userId: Int = -1): TaskEntry? {
        val re = pkgRegex(pkg)
        val hits = taskBlocks(stdout)
            .filter { WindowStateParser.belongsTo(it, re) }
            .mapNotNull { parse(it) }
        if (hits.isEmpty()) return null
        if (userId < 0) return hits.first()
        // 解析不出用户号时保留该块（宁可 resize 到同包名，也不因字段缺失放弃）
        return hits.firstOrNull { it.userId == userId } ?: hits.firstOrNull { it.userId == null }
    }

    /** 兼容旧调用：只要 task id */
    fun findTaskId(stdout: String, pkg: String): String? =
        findTask(stdout, pkg)?.taskId

    private fun parse(block: String): TaskEntry? {
        val first = block.lineSequence().firstOrNull { it.isNotBlank() } ?: return null
        val id = TASK_ID_EQ.find(first)?.groupValues?.get(1)
            ?: TASK_HASH.find(first)?.groupValues?.get(1)
            ?: return null
        val mode = MODE.find(first)?.groupValues?.get(1)
            ?: MODE.find(block)?.groupValues?.get(1)
        val userId = USER.find(first)?.groupValues?.get(1)?.toIntOrNull()
        val visible = VISIBLE.find(first)?.groupValues?.get(1)?.let { it == "true" }
            ?: VISIBLE.find(block)?.groupValues?.get(1)?.let { it == "true" }
        return TaskEntry(id, mode, userId, visible)
    }

    /**
     * 该包名下「不可见的残留 task」（A5 清理用）。
     *
     * 判据只有一条：Task 头行 `visible=false`。**不看窗口模式** ——
     * 全屏 task 不可见是正常的后台栈，清掉会丢用户的页面状态；
     * 只有「本应用刚转窗留下的隐形浮窗」才该清，而它们在 dump 里同时满足
     * 「同包 + visible=false + 不止一个 task」。
     *
     * [keepVisible] 是安全阀：可见 task 数量不超过它时一律不动手。
     * 解析不出 visible 字段的 ROM 直接返回空列表 —— 宁可留残留，也不误杀。
     */
    fun orphanTaskIds(
        stdout: String,
        pkg: String,
        userId: Int = -1,
        keepVisible: Int = 1,
    ): List<String> {
        val re = pkgRegex(pkg)
        val hits = taskBlocks(stdout)
            .filter { WindowStateParser.belongsTo(it, re) }
            .mapNotNull { parse(it) }
        if (hits.isEmpty()) return emptyList()
        val visible = hits.filter { it.visible == true }
        // 一个可见 task 都没有：说明用户已经不在这个应用里，动手只会关掉他的后台栈
        if (visible.size < keepVisible) return emptyList()
        return hits.filter { it.visible == false }
            .filter { userId < 0 || it.userId == null || it.userId == userId }
            .map { it.taskId }
    }
}
