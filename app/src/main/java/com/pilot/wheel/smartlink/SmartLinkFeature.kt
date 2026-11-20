package com.pilot.wheel.smartlink

import android.content.Context

/**
 * SmartLink「智能链接悬浮」总开关（单一事实源，默认关）。
 *
 * 关闭即回滚：关闭后不处理无障碍事件、不创建 overlay、不发起 shell 调用、不保留常驻对象。
 * 独立 SharedPreferences，不触碰工程公共 [com.pilot.wheel.Prefs]，避免与主线配置互相污染。
 */
object SmartLinkFeature {

    private const val PREFS = "smartlink"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
        // 关闭时立即清理常驻状态（胶囊），保证回滚即时生效
        if (!enabled) SmartLinkCoordinator.shutdown()
    }
}
