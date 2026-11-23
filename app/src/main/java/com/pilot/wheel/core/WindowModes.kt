package com.pilot.wheel.core

import com.pilot.wheel.Prefs

/**
 * 小窗启动候选 windowingMode（纯函数，可单测）。
 *
 * - 荣耀 MagicOS：windowingMode 100 会被渲染成无标题栏的坏兼容形态（或贴顶分屏），
 *   正常小窗是 AOSP freeform(5)，故荣耀上 5 优先、100 兜底；
 * - 非荣耀（如 ColorOS）：私有小窗是 100，5 只是兼容兜底；
 * - shell 通道比 bundle 通道多一个 0（全屏）兜底，由 [withFullscreen] 控制。
 */
object WindowModes {

    /** AOSP 标准自由窗口（freeform）窗口模式值 */
    const val FREEFORM = 5
    /** 荣耀/ColorOS 私有悬浮小窗的窗口模式值 */
    const val VENDOR = 100
    /**
     * 75 号：MagicOS 私有「轻窗」层（102）。
     *
     * AOSP freeform(5) 与 vendor(100) 都只对**冷启动**有效；对已经存在的全屏 task，
     * 机型实测（荣耀 ASUJ6R6415001222 / Android 17）只有 102 能被消费：
     * `am start -n <cmp> --windowingMode 102` 会把当前 task 原地切成浮动小窗
     * （同一个 taskId，不新建窗口，底下页面仍然可见）。
     * 这正是「平行小窗」与「每次都是小窗」两个需求的唯一可用口子。
     */
    const val MAGIC = 102
    /** 全屏 */
    const val FULLSCREEN = 0

    /**
     * 冷启动候选顺序。
     *
     * 75 号没把 [MAGIC] 放进这里，是刻意的：102 只在「task 已存在」时才被消费，
     * 冷启动传 102 会被系统忽略、直接落成全屏，而 `am start` 的退出码仍是 0 ——
     * 放进来会变成「假成功」，把真正的 5 兜底挡掉。已存在 task 的转窗走
     * AppLauncher.promoteExistingTask() 那条独立路径。
     */
    fun candidates(mode: String, honor: Boolean, withFullscreen: Boolean = false): IntArray {
        val base = when (mode) {
            Prefs.LAUNCH_VENDOR ->
                if (honor) intArrayOf(FREEFORM, VENDOR) else intArrayOf(VENDOR, FREEFORM)
            Prefs.LAUNCH_FREEFORM -> intArrayOf(FREEFORM)
            else -> intArrayOf(FREEFORM, VENDOR)
        }
        if (!withFullscreen) return base
        return base + FULLSCREEN
    }
}
