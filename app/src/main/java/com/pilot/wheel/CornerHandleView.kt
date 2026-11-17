package com.pilot.wheel

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * 角落把手：一个独立的小可点击圆点。
 *
 * 热区穿透模式下，热区窗口全穿透不消费触摸，呼出只靠这个把手点击；
 * 把手窗口不带 NOT_TOUCHABLE，是唯一会响应触摸的入口。
 */
@SuppressLint("ViewConstructor")
class CornerHandleView(
    context: Context,
    private val onTrigger: () -> Unit,
) : View(context) {

    private val density = resources.displayMetrics.density
    private var downX = 0f
    private var downY = 0f

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#40000000")
        style = Paint.Style.FILL
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C0FFFFFF")
        style = Paint.Style.FILL
    }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF2F6BFF")
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawCircle(cx, cy, 11f * density, ringPaint)
        canvas.drawCircle(cx, cy, 5.5f * density, dotPaint)
        canvas.drawCircle(cx, cy, 9f * density, accentPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (hypot(event.rawX - downX, event.rawY - downY) < 16f * density) {
                    performClick()
                    // 只要有点击动作就震动反馈（跟随「触感反馈」开关）
                    if (Prefs.haptic(context)) Haptics.select(context)
                    onTrigger()
                }
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
