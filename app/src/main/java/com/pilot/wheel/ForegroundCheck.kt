package com.pilot.wheel

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

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
}
