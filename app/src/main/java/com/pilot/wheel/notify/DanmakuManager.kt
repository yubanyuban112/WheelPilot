package com.pilot.wheel.notify

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import com.pilot.wheel.Prefs

/**
 * 弹幕管理器（B4）：决定「哪条消息值得飘」，并管理那条全屏叠加层的生灭。
 *
 * 64 号改动：弹幕与「通知横幅」合并 ——
 * - 不再只认微信/QQ，任何应用的通知都飘（通知横幅的语义并入弹幕）；
 * - 不再只限横屏，竖屏同样飘；
 * - 每条弹幕记住来源包名，点击后由 [onPick] 以小窗打开对应应用。
 *
 * 叠加层用 TYPE_ACCESSIBILITY_OVERLAY（无障碍服务才有），因此不需要
 * SYSTEM_ALERT_WINDOW 权限；窗口改 FLAG_NOT_TOUCH_MODAL，命中弹幕才拦截，
 * 命中空白由 DanmakuOverlay 返回 false 落回下层。
 */
object DanmakuManager {

    private const val TAG = "WheelPilot"
    private const val MAX_QUEUE = 20

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var wm: WindowManager? = null

    @Volatile
    private var overlay: DanmakuOverlay? = null

    /** 点击弹幕回调：pkg -> 小窗打开（由宿主 PilotService 注入） */
    @Volatile
    private var onPick: ((String) -> Unit)? = null

    /** 队列里待展示的条数（含已经 push 进去的） */
    private var queued = 0

    /** PilotService 连上时调用：拿到窗口管理器和宿主 Context，并注入点击回调 */
    fun attach(context: Context, pick: (String) -> Unit) {
        wm = context.getSystemService(WindowManager::class.java)
        onPick = pick
    }

    fun detach() {
        main.post { removeOverlay() }
        wm = null
        onPick = null
    }

    /** 通知到达：外部只需把四样东西给进来，是否展示由这里判断 */
    fun onNotification(context: Context, pkg: String, title: String, text: String) {
        if (!Prefs.danmaku(context)) return
        if (pkg == context.packageName) return
        val message = when {
            title.isBlank() -> text
            text.isBlank() -> title
            else -> "$title：$text"
        }
        if (message.isBlank()) return
        main.post { enqueue(context.applicationContext, message, pkg) }
    }

    private fun enqueue(context: Context, message: String, pkg: String) {
        if (queued >= MAX_QUEUE) {
            Log.d(TAG, "danmaku queue full, drop: $message")
            return
        }
        val view = overlay ?: createOverlay(context)?.also { overlay = it } ?: return
        view.onPick = { clickedPkg -> onPick?.invoke(clickedPkg) }
        queued++
        view.push(message, pkg)
        view.onIdle = {
            queued = 0
            // 全部飘完再摘窗口：留着空窗口没有意义，还会让系统一直合成这一层
            removeOverlay()
        }
    }

    private fun createOverlay(context: Context): DanmakuOverlay? {
        val manager = wm ?: return null
        val view = DanmakuOverlay(context)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        return runCatching {
            manager.addView(view, params)
            view
        }.onFailure { Log.d(TAG, "danmaku addView failed: ${it.javaClass.simpleName}") }
            .getOrNull()
    }

    private fun removeOverlay() {
        val view = overlay ?: return
        overlay = null
        queued = 0
        runCatching { wm?.removeView(view) }
    }
}
