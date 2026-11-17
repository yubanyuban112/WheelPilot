package com.pilot.wheel

import android.app.Notification
import android.app.PendingIntent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * 微信消息入口（参考同类小窗应用的消息监听）。
 *
 * 只在本机内存里保留最近一条微信通知的标题和 contentIntent，不上传、不落盘。
 * 轮盘里会多出一个「微信消息」项，点它直接回到对应聊天。
 * 需要用户在系统「通知使用权」里手动授权。
 */
class PilotNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
    }

    override fun onListenerDisconnected() {
        instance = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn ?: return
        if (notification.packageName != WECHAT_PKG) return
        val extras = notification.notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        if (title.isNullOrBlank()) return
        synchronized(lock) {
            lastTitle = title
            lastText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            lastIntent = notification.notification.contentIntent
            lastAt = System.currentTimeMillis()
        }
    }

    companion object {
        const val WECHAT_PKG = "com.tencent.mm"
        /** 轮盘消息项的保留 pkg，不走普通应用启动 */
        const val MSG_PKG = "pilot://wechat-message"
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
    }
}
