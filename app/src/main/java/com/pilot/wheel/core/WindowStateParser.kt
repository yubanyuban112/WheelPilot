package com.pilot.wheel.core

/**
 * `dumpsys activity activities` 解析（纯函数，可单测）。
 *
 * 需要三件事：目标包名的 taskId、该 task 栈顶的 Activity、当前窗口模式。
 * 不同 Android 版本的字段名不一致（`mode=` 在 Task 行、`windowingMode=` 在
 * ActivityRecord 行），所以两种都认，解析不出来一律返回 null 由上层降级。
 *
 * 重构要点：原来 [findTaskId] / [findTaskIds] / [windowingModes] / [isAnyFreeform] /
 * [isAnyWindowed] / [findTopActivity] 各自重新 `taskBlocks()` 一遍 —— 同一次判定链里
 * 同一份几十 KB 的 dump 会被切块 3–4 次。现在统一走 [tasks]，一次切块、一次过滤，
 * 其余 API 只是它的投影，输出与旧实现逐项等价。
 */
object WindowStateParser {

    private val TASK_ID = Regex("#(\\d+)")

    /** 只认 Task 头行里的 id：`* Task{8b1a2c3 #456 ...}`，避免正文里的 #N 抢答 */
    private val TASK_HEADER_ID = Regex("Task\\{[0-9a-fA-F]+\\s+#(\\d+)")

    private val REAL_ACTIVITY = Regex("realActivity=([^\\s}]+)")
    private val ACTIVITY_RECORD = Regex("ActivityRecord\\{[0-9a-f]+ u\\d+ ([^\\s}]+)")
    private val MODE_FIELD = Regex("\\bmode=(\\w+)")
    private val WINDOWING_FIELD = Regex("windowingMode=(\\w+)")

    /** 已脱离全屏的窗口模式：AOSP freeform(5)、厂商私有(100)、荣耀轻窗(102) */
    private val WINDOWED_MODES = setOf(5, 100, 102)

    /** 厂商私有模式的数值，用于把 [isFreeform] 与 [isWindowed] 区分开 */
    private const val MODE_VENDOR = 100

    /** 一个目标包的 task 块及其解析结果；[id] / [mode] 解析不出为 null */
    data class TaskBlock(
        val id: String?,
        val mode: String?,
        val text: String,
    )

    /** 包名整词匹配，避免 com.tencent.mm 命中 com.tencent.mmp */
    internal fun pkgMatcher(pkg: String): Regex =
        Regex("(?<![\\w.])" + Regex.escape(pkg) + "(?![\\w.])")

    /** 把 dump 按 Task 块切开；第一个 Task 之前的内容（Display 头）丢弃 */
    fun taskBlocks(dump: String): List<String> {
        val out = ArrayList<String>()
        val buf = StringBuilder()
        dump.lineSequence().forEach { line ->
            val start = line.contains("* Task{") || line.contains("* RootTask{")
            if (start) {
                if (buf.isNotEmpty()) out.add(buf.toString())
                buf.setLength(0)
            }
            if (start || buf.isNotEmpty()) buf.append(line).append('\n')
        }
        if (buf.isNotEmpty()) out.add(buf.toString())
        return out
    }

    private fun headerLine(block: String): String = block.lineSequence().firstOrNull().orEmpty()

    /**
     * 块是否属于目标包。
     *
     * 只看两处：Task 头行（`A=10345:com.tencent.mm`），以及该块内的
     * `realActivity=` / `packageName=` 行。
     *
     * 不能整块 `contains`：最后一个 Task 块的缓冲区会把 dumpsys 尾部的非 task 段落
     * （mFocusedApp、最近任务、历史栈等）一起吞进来，那些段落里提到别的包名，
     * 于是「查 A 包，返回最后一个 task 的 id」—— 真机实测同一份 dump 里 gkd 与微信
     * 都解析成 task 7，resize 打到别人的窗口上，正是这个原因。
     *
     * [StackListParser] 走的是同一份 dump，这里把它一起收编：同类判据只留这一份实现。
     */
    internal fun belongsTo(block: String, re: Regex): Boolean {
        if (re.containsMatchIn(headerLine(block))) return true
        return block.lineSequence().any { line ->
            (line.contains("realActivity=") || line.contains("packageName=")) &&
                re.containsMatchIn(line)
        }
    }

    /** Task 头行里的 task id；头行没有 id 字段时退回第一个 `#N` */
    private fun blockId(block: String): String? {
        val header = headerLine(block)
        return TASK_HEADER_ID.find(header)?.groupValues?.get(1)
            ?: TASK_ID.find(header)?.groupValues?.get(1)
    }

    private fun blockMode(block: String): String? =
        MODE_FIELD.find(block)?.groupValues?.get(1)
            ?: WINDOWING_FIELD.find(block)?.groupValues?.get(1)

    /** 目标包的全部 task（按 dump 顺序，最上层在前）。所有查询 API 的唯一入口。 */
    fun tasks(dump: String, pkg: String): List<TaskBlock> {
        val re = pkgMatcher(pkg)
        return taskBlocks(dump)
            .filter { belongsTo(it, re) }
            .map { TaskBlock(blockId(it), blockMode(it), it) }
    }

    /**
     * 目标包名所在的 Task 块；多个 task 时取第一个（前台侧通常排在前）。
     *
     * 微信分享时会同时存在「选择聊天」和「聊天页」两个 task，需要全部时用 [tasks]。
     */
    fun findBlock(dump: String, pkg: String): String? = tasks(dump, pkg).firstOrNull()?.text

    fun findTaskId(dump: String, pkg: String): String? = tasks(dump, pkg).firstOrNull()?.id

    fun findTaskIds(dump: String, pkg: String): List<String> = tasks(dump, pkg).mapNotNull { it.id }

    /** 每个匹配 task 的窗口模式（与 [findTaskIds] 同序） */
    fun windowingModes(dump: String, pkg: String): List<String?> = tasks(dump, pkg).map { it.mode }

    /**
     * 该应用是否已有任意一个浮窗 task。
     *
     * 为什么不是「第一个 task 的模式」：微信分享时同包两个 task，浮起来的那个可能
     * 排在后面；只看第一个会把「已经转好了」判成失败，于是再转一次叠出第二个窗口。
     */
    fun isAnyFreeform(dump: String, pkg: String): Boolean = tasks(dump, pkg).any { isFreeform(it.mode) }

    /** 该应用是否已有任意一个非全屏 task（判据见 [isWindowed]） */
    fun isAnyWindowed(dump: String, pkg: String): Boolean = tasks(dump, pkg).any { isWindowed(it.mode) }

    /**
     * 栈顶 Activity 全名（`pkg/.Act` 形式原样返回）。
     *
     * dump 的 Task 块里 **栈顶在前、栈底在后**，所以必须取第一条 `realActivity=`。
     * 取最后一条会拿到栈底的入口 Activity —— 那样转窗会 relaunch 到应用首页，
     * 用户从通知点进来的具体页面会丢。
     */
    fun findTopActivity(dump: String, pkg: String): String? {
        val block = findBlock(dump, pkg) ?: return null
        return REAL_ACTIVITY.find(block)?.groupValues?.get(1)
            ?: ACTIVITY_RECORD.find(block)?.groupValues?.get(1)
    }

    /** 当前窗口模式，如 fullscreen / freeform / multi-window；拿不到返回 null */
    fun windowingMode(dump: String, pkg: String): String? = tasks(dump, pkg).firstOrNull()?.mode

    /**
     * 是否已是标准浮窗形态。
     *
     * dumpsys 的窗口模式有两种写法：AOSP/部分 ROM 输出字符串 `freeform`，
     * 另一些（含 MagicOS）输出数字 windowingMode：5 = AOSP freeform，
     * 102 = 荣耀私有轻窗。只认字符串会把已是浮窗的 task 判成全屏，
     * 于是重复触发转窗、oneStep 再 NEW_TASK 一个 —— 全屏上浮两个窗口的成因之一。
     *
     * 100（厂商私有）不算 freeform：它在部分 ROM 上是「贴顶分屏」一类形态，
     * 只满足 [isWindowed]（已脱离全屏），不满足本判据。
     */
    fun isFreeform(mode: String?): Boolean {
        if (!isWindowed(mode)) return false
        return mode?.toIntOrNull() != MODE_VENDOR
    }

    /**
     * 是否已脱离全屏 —— 比 [isFreeform] 多认厂商私有模式 100。
     *
     * 单独存在的原因：binder 直调 `setTaskWindowingMode` 时，厂商可能把结果落在
     * 自己的模式值上（MagicOS 轻窗即 100/102 一类），沿用只认 5/102 的老判据会
     * 把已经转好的窗口判成失败，接着再转一次。
     */
    fun isWindowed(mode: String?): Boolean {
        if (mode == null) return false
        val numeric = mode.toIntOrNull()
        return if (numeric != null) numeric in WINDOWED_MODES else mode.contains("freeform", ignoreCase = true)
    }
}
