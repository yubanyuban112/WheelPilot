package com.pilot.wheel

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * 桌面图标跟随界面主题（3.18.1）。
 *
 * 做法：Manifest 里为主界面声明 4 个 activity-alias，每个 alias 带一套自适应图标，
 * 同一时刻只启用与当前主题匹配的那一个。切换主题时先启用目标 alias、再关闭其余三个，
 * 顺序不能反 —— 反过来会出现「四个都关闭」的窗口期，桌面图标会短暂消失。
 *
 * 约束：
 *  - 只能改「哪个组件对外可见」，改不了已安装的桌面快捷方式缓存；部分桌面包
 *    （MIUI/HyperOS 启动器）需要重启桌面才刷新图标，这属系统行为，代码层无法绕过。
 *  - alias 的启用状态由 PackageManager 持久化，所以 [App.onCreate] 每次启动都会再对齐一次，
 *    防止升级/清数据后出现无入口或多入口。
 */
object ThemeIcon {

    private const val PKG = "com.pilot.wheel"

    private val ALIAS = mapOf(
        UiTheme.CLASSIC to "$PKG.LauncherClassic",
        UiTheme.LIQUID_GLASS to "$PKG.LauncherGlass",
        UiTheme.APPLE to "$PKG.LauncherApple",
        // 63 号：Apple 暗色与浅色共用同一张桌面图标（同一套 Apple 图形，只是应用内表面不同），
        // 不新增 alias，避免多出一份图标资源与一处 setComponentEnabledSetting 的冷启开销
        UiTheme.APPLE_DARK to "$PKG.LauncherApple",
        // 64 号：AUTO 亮色态是经典拟物（主），桌面图标固定跟经典拟物走；
        // 系统明暗切换时应用内会自动换表面，图标不必跟着变（避免监听系统主题切 alias 的冷启开销）
        UiTheme.AUTO to "$PKG.LauncherClassic",
    )

    fun sync(context: Context, theme: UiTheme) {
        val target = ALIAS[theme] ?: return
        val pm = context.packageManager

        // 已经在位就不要再设一遍：每次 setComponentEnabledSetting 都会让桌面重新解析一次
        // 组件状态，四次无谓的写入会让冷启动后桌面图标闪一下
        val targetState = runCatching {
            pm.getComponentEnabledSetting(ComponentName(PKG, target))
        }.getOrDefault(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)

        if (targetState != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
            val enabled = runCatching {
                pm.setComponentEnabledSetting(
                    ComponentName(PKG, target),
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP,
                )
                true
            }.onFailure { Log.w("ThemeIcon", "启用 $target 失败", it) }.getOrDefault(false)

            // 目标没能启用就保持现状：宁可图标不跟随，也不能让桌面没有入口
            if (!enabled) return
        }

        ALIAS.values.filter { it != target }.forEach { alias ->
            val state = runCatching {
                pm.getComponentEnabledSetting(ComponentName(PKG, alias))
            }.getOrDefault(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
            if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) return@forEach
            runCatching {
                pm.setComponentEnabledSetting(
                    ComponentName(PKG, alias),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }.onFailure { Log.w("ThemeIcon", "停用 $alias 失败", it) }
        }
    }

    /** 当前主题对应的 alias 组件名（诊断/日志用） */
    fun aliasOf(theme: UiTheme): String? = ALIAS[theme]
}
