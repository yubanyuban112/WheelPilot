package com.pilot.wheel

import android.app.Notification
import android.app.PendingIntent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.pilot.wheel.notify.DanmakuManager

/**
 * 通知总线：一个 NotificationListenerService 喂两条下游。
 *
 * 1. 微信消息入口（原有能力）：轮盘里置顶一项带红点的微信图标，点开回到对应聊天；
 * 2. 弹幕（B4）：只读标题正文，不落盘。
 *
 * 64 号：删除通知收件箱（B5）、亮屏统计（B3）、通知面板（B2）三条下游。
 * 只在本机内存里保留最近一条微信通知的 PendingIntent，不上传、不落盘。
 * 需要用户在系统「通知使用权」里手动授权。
 */
class PilotNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.d(TAG, "notification listener connected")
    }

    override fun onListenerDisconnected() {
        instance = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn ?: return
        // 轻量打开：记录通知到达（横幅另记标记），不区分应用，先于微信判断
        LightOpenWatcher.onNotification(notification.packageName, isHeadsUp(notification))

        // 自己的通知不弹幕：否则自我循环
        if (notification.packageName == packageName) return

        val extras = notification.notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = textOf(extras)
        if (title.isNotBlank() || text.isNotBlank()) {
            DanmakuManager.onNotification(this, notification.packageName, title, text)
        }

        if (notification.packageName != WECHAT_PKG) return
        if (title.isBlank()) return
        synchronized(lock) {
            lastTitle = title
            lastText = text
            lastIntent = notification.notification.contentIntent
            lastAt = System.currentTimeMillis()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
    }

    /** 正文取值顺序：TEXT → BIG_TEXT → SUMMARY_TEXT（长文通知正文常在 bigText 里） */
    private fun textOf(extras: android.os.Bundle): String {
        val candidates = listOf(
            Notification.EXTRA_TEXT,
            Notification.EXTRA_BIG_TEXT,
            Notification.EXTRA_SUMMARY_TEXT,
        )
        for (key in candidates) {
            val value = extras.getCharSequence(key)?.toString()
            if (!value.isNullOrBlank()) return value
        }
        return ""
    }

    /**
     * 是否横幅（heads-up）。
     * ranking 的 isAmbient 由系统按通知重要度与渠道给出，比读 extras 稳定；
     * 拿不到时返回 false —— 宁可少认一次横幅，也不要领把普通通知当横幅。
     */
    private fun isHeadsUp(sbn: StatusBarNotification): Boolean = runCatching {
        // RankingMap 的公开 API 是「传入 out 参数」的形式，没有单参返回值重载
        val ranking = NotificationListenerService.Ranking()
        currentRanking?.getRanking(sbn.key, ranking) == true && ranking.isAmbient
    }.getOrDefault(false)

    companion object {
        const val WECHAT_PKG = "com.tencent.mm"
        /** 轮盘消息项的保留 pkg，不走普通应用启动 */
        const val MSG_PKG = "pilot://wechat-message"
        private const val TAG = "WheelPilot"
        private const val MESSAGE_TTL_MS = 24L * 60 * 60 * 1000

        private val lock = Any()
        private var lastTitle: String? = null
        @Suppress("unused")
        private var lastText: String = ""
        private var lastIntent: PendingIntent? = null
        private var lastAt = 0L

        @Volatile
        var instance: PilotNotificationListener? = null
            private set

        @Suppress("unused")
        fun isAuthorized(): Boolean = instance != null

        fun hasMessage(): Boolean = synchronized(lock) {
            lastIntent != null && System.currentTimeMillis() - lastAt <= MESSAGE_TTL_MS
        }

        fun summary(): String = synchronized(lock) { lastTitle ?: "微信消息" }

        /** 把通知携带的 PendingIntent 发出去，直接打开对应聊天 */
        fun open(): Boolean = synchronized(lock) {
            val intent = lastIntent
            if (intent == null) false else runCatching { intent.send(); true }.getOrDefault(false)
        }

        /**
         * 用户已从轮盘点进聊天：清掉消息入口，轮盘不再显示，下次来通知再自动回流。
         * 只清内存里的消息状态，不动定时器/系统通知栏。
         */
        fun clearMessage() = synchronized(lock) {
            lastTitle = null
            lastText = ""
            lastIntent = null
            lastAt = 0L
        }
    }
}
