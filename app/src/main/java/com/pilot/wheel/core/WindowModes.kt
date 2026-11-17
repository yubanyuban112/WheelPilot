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
    /** 全屏 */
    const val FULLSCREEN = 0

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
