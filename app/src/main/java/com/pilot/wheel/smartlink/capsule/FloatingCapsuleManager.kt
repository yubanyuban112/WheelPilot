package com.pilot.wheel.smartlink.capsule

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.pilot.wheel.core.WindowRect

/**
 * 左下角悬浮胶囊，同一时间只显示一个。
 *
 * 复用无障碍 overlay（TYPE_ACCESSIBILITY_OVERLAY），不新增悬浮窗权限、不新增前台服务。
 * 该类型窗口必须由无障碍服务上下文 addView，故持有 [AccessibilityService] 引用。
 */
class FloatingCapsuleManager(private val service: AccessibilityService) {

    private val wm: WindowManager = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private var currentView: View? = null
    private var hideRunnable: Runnable? = null

    @Volatile var timeoutMs: Long = 6_000L
    @Volatile var xOffset: Int = dp(16)
    @Volatile var yOffset: Int = dp(16)

    fun show(targetPkg: String, label: String, anchor: WindowRect? = null, onTap: () -> Unit) {
        hide()
        val view = buildView(targetPkg, label) {
            hide()
            onTap()
        }
        runCatching { wm.addView(view, buildLayoutParams(anchor)) }
            .onFailure { /* 降级：胶囊弹不出不影响检测主链路 */ }
            .onSuccess {
                currentView = view
                scheduleAutoHide()
            }
    }

    fun hide() {
        hideRunnable?.let { handler.removeCallbacks(it) }
        hideRunnable = null
        currentView?.let { runCatching { wm.removeView(it) } }
        currentView = null
    }

    fun detach() = hide()

    /**
     * 胶囊位置。
     *
     * [anchor] 非空（57 号整页扫描命中链接）时贴住该链接：左缘对齐链接左边、底边抬到链接上方 8dp；
     * 链接太靠下或越界时退回左下角常位，避免胶囊盖住正文或跑出屏幕。
     */
    private fun buildLayoutParams(anchor: WindowRect?): WindowManager.LayoutParams {
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.BOTTOM or Gravity.START
        val dm = service.resources.displayMetrics
        if (anchor == null || anchor.width <= 0 || anchor.height <= 0) {
            lp.x = xOffset
            lp.y = yOffset + navBarHeight()
            return lp
        }
        val gap = dp(8)
        val maxX = (dm.widthPixels - dp(180)).coerceAtLeast(0)
        lp.x = anchor.left.coerceIn(0, maxX)
        // BOTTOM 重力下 y 是「距屏幕底边的偏移」，故用 屏高 − 链接顶 换算
        val above = dm.heightPixels - anchor.top + gap
        lp.y = if (above > dm.heightPixels - navBarHeight() - dp(48)) {
            // 链接靠近底部：退到常位，否则会贴到导航栏甚至屏外
            yOffset + navBarHeight()
        } else {
            above
        }
        return lp
    }

    private fun buildView(targetPkg: String, label: String, onTap: () -> Unit): View {
        val root = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(14), dp(6))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(22).toFloat()
                setColor(Color.argb(0xE6, 0xFF, 0xFF, 0xFF))
            }
            elevation = dp(6).toFloat()
        }
        val icon = ImageView(service).apply {
            setImageDrawable(loadIcon(targetPkg))
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
        }
        val tv = TextView(service).apply {
            text = label
            textSize = 12f
            setTextColor(Color.parseColor("#253047"))
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(8) }
        }
        root.addView(icon)
        root.addView(tv)
        root.setOnClickListener { onTap() }
        return root
    }

    private fun loadIcon(pkg: String): android.graphics.drawable.Drawable? = runCatching {
        service.packageManager.getApplicationIcon(pkg)
    }.getOrNull()

    private fun scheduleAutoHide() {
        hideRunnable?.let { handler.removeCallbacks(it) }
        hideRunnable = Runnable { hide() }
        hideRunnable?.let { handler.postDelayed(it, timeoutMs) }
    }

    private fun navBarHeight(): Int {
        val id = service.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) service.resources.getDimensionPixelSize(id) else 0
    }

    private fun dp(v: Int): Int = (v * service.resources.displayMetrics.density).toInt()
}
