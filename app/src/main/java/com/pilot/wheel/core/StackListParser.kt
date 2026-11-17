package com.pilot.wheel.core

/**
 * `am stack list` 输出解析（纯函数，可单测）。
 *
 * 输出里 task 行为 `*Task id=N ...`，目标包名出现在相邻的 realActivity 行；
 * 逐行扫描时记住最近一次出现的 task id，遇到包含目标包名的行即返回。
 */
object StackListParser {

    private val TASK_ID = Regex("Task id=(\\d+)")

    /** 返回目标包名所属 task id；找不到返回 null */
    fun findTaskId(stdout: String, pkg: String): String? {
        var taskId: String? = null
        for (line in stdout.lineSequence()) {
            TASK_ID.find(line)?.let { taskId = it.groupValues[1] }
            if (taskId != null && line.contains(pkg)) break
        }
        return taskId
    }
}
