package com.pilot.wheel.core

/**
 * 系统界面包名 / 类名判定的**单一来源**。
 *
 * 重构前这些字面量散在三处：
 * - `com.android.systemui` 在 [LightOpenRules] 与 `LightOpenWatcher` 各定义一份 private 常量；
 * - 分享面板包名在 `LightOpenWatcher` 出现 4 次（排除表 2 次 + 判定 2 次），关键字判定又一次；
 * - 电话组、系统弹窗组只散落在 `excludedPackages()` 内部，无法被纯函数测试覆盖。
 *
 * 这里只做「名字 → 判定」，不持有 Context、不读 Prefs，因此可单测。
 */
object SystemPackages {

    const val SYSTEMUI = "com.android.systemui"
    const val SETTINGS = "com.android.settings"

    private const val SHARE_RESOLVER_AOSP = "com.android.intentresolver"
    private const val SHARE_RESOLVER_GOOGLE = "com.google.android.intentresolver"

    /** 电话 / 紧急相关：任何情况下都不该被转成浮窗 */
    val TELEPHONY: List<String> = listOf(
        "com.android.dialer",
        "com.android.incallui",
        "com.android.server.telecom",
    )

    /** 系统级弹窗：不是「应用」，且通常盖住输入框所在页面 */
    val SYSTEM_DIALOGS: List<String> = listOf(
        "android",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.documentsui",
    )

    /** 分享面板本身：它是系统选择器，不是被打开的应用 */
    val SHARE_RESOLVERS: List<String> = listOf(SHARE_RESOLVER_AOSP, SHARE_RESOLVER_GOOGLE)

    fun isShareResolver(pkg: String?): Boolean =
        pkg == SHARE_RESOLVER_AOSP || pkg == SHARE_RESOLVER_GOOGLE
}

/** 通知栏 / 控制中心的窗口归类结果 */
enum class SystemUiWindow { NONE, SHADE, QS }

/**
 * 系统界面窗口与分享面板的类名判定（纯函数，可单测）。
 *
 * 原来这两段逻辑是 `LightOpenWatcher` 的 private 方法，属于「整个判据链的入口」却
 * 无法单测 —— 各 ROM 类名差异只能靠真机日志反复试。下沉到 core 后可直接喂类名断言。
 */
object SystemUiWindows {

    /**
     * 各 ROM 类名不统一，这里只认关键片段：
     * - 控制中心：QS / QuickSettings / NssPanel
     * - 通知栏：NotificationShade / NotificationPanel / StatusBar / shade
     * QS 先判，因为部分 ROM 的 QS 面板类名里也带 "shade"。
     */
    fun classify(className: String?): SystemUiWindow {
        val name = className?.lowercase() ?: return SystemUiWindow.NONE
        if (name.contains("quicksettings") ||
            name.contains(".qs") ||
            name.contains("nsspanel") ||
            name.contains("qspanel") ||
            // 括号是必需的：原写法把 && 与 || 平铺，读者必须自己回忆优先级
            (name.contains("miui.systemui") && name.contains("panel"))
        ) {
            return SystemUiWindow.QS
        }
        // 小米 / HyperOS 的通知面板常报 NotificationStackScrollLayout、MiuiNotificationPanel
        // 一类类名；只认 notificationShade / statusBar 会让「下拉通知栏」这一步永远记不到。
        if (name.contains("notificationshade") ||
            name.contains("notificationpanel") ||
            name.contains("notificationstack") ||
            name.contains("statusbar") ||
            name.contains("shade") ||
            name.contains("expandableNotificationRow") ||
            name.contains("headsup") ||
            name.contains("heads_up")
        ) {
            return SystemUiWindow.SHADE
        }
        return SystemUiWindow.NONE
    }

    /**
     * 分享面板判定。各版本类名不同（AOSP 12+ 用 IntentResolver，旧版 ChooserActivity，
     * 小米有 MiuiResolverActivity），按类名关键字认，包名只作兜底。
     */
    fun isChooser(pkg: String?, className: String?): Boolean {
        val name = className?.lowercase().orEmpty()
        if (name.contains("chooser") ||
            name.contains("resolveractivity") ||
            name.contains("intentresolver") ||
            name.contains("miuiresolver")
        ) {
            return true
        }
        return SystemPackages.isShareResolver(pkg)
    }
}
