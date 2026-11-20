package com.pilot.wheel.notify

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.random.Random

/**
 * 弹幕层（B4）：一条条消息从右往左飘过，可点击。
 *
 * 64 号改动：弹幕与「通知横幅」合并 —— 不再只飘微信/QQ，任何应用的通知都飘；
 * 不再只限横屏。每条弹幕记住来源包名，点击后回调 [onPick] 由宿主以小窗打开对应应用。
 *
 * 交互边界：窗口改 FLAG_NOT_TOUCH_MODAL（去 FLAG_NOT_TOUCHABLE），
 * 命中弹幕时消费并回调，命中空白返回 false 让触摸落到下层。
 */
@SuppressLint("ViewConstructor")
class DanmakuOverlay(context: Context) : View(context) {

    private data class Line(
        val text: String,
        val pkg: String,
        var x: Float,
        val y: Float,
        val width: Float,
        val speed: Float,
    )

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 15f * density
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(6f, 0f, 2f, Color.argb(180, 0, 0, 0))
    }

    private val lines = ArrayList<Line>()
    private var running = false

    /** 点击某条弹幕：回传来源包名，宿主负责小窗打开 */
    var onPick: ((String) -> Unit)? = null

    /** 帧回调：推进位置、回收出屏项、触发重绘 */
    private val frame = object : Choreographer.FrameCallback {
        private var lastNs = 0L

        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val dt = if (lastNs == 0L) 0f else (frameTimeNanos - lastNs) / 1_000_000_000f
            lastNs = frameTimeNanos
            var changed = false
            val iterator = lines.iterator()
            while (iterator.hasNext()) {
                val line = iterator.next()
                line.x -= line.speed * dt
                if (line.x + line.width < 0f) {
                    iterator.remove()
                    changed = true
                } else {
                    changed = true
                }
            }
            if (changed) invalidate()
            if (lines.isEmpty()) {
                running = false
                lastNs = 0L
                onIdle?.invoke()
                return
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** 全部飘完之后通知 Manager 摘窗口 */
    var onIdle: (() -> Unit)? = null

    fun push(text: String, pkg: String) {
        val content = text.trim().take(60)
        if (content.isEmpty()) return
        val width = paint.measureText(content)
        val h = height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        // 纵向分布在中段，避开状态栏与底部手势条
        val y = h * (0.18f + Random.nextFloat() * 0.42f) + paint.textSize
        lines += Line(
            text = content,
            pkg = pkg,
            x = (width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels).toFloat(),
            y = y,
            width = width,
            // 速度按屏宽算：约 8 秒穿屏，长短屏观感一致
            speed = max(resources.displayMetrics.widthPixels / 8f, 180f * density),
        )
        if (!running) {
            running = true
            Choreographer.getInstance().postFrameCallback(frame)
        }
        invalidate()
    }

    /** 命中检测：触点落在某条弹幕文字的矩形内。从后往前（后 push 的在上层） */
    private fun hit(x: Float, y: Float): Line? {
        for (i in lines.indices.reversed()) {
            val line = lines[i]
            if (x >= line.x && x <= line.x + line.width &&
                y >= line.y - paint.textSize && y <= line.y
            ) {
                return line
            }
        }
        return null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val line = hit(event.x, event.y)
            if (line != null) {
                onPick?.invoke(line.pkg)
                return true
            }
        }
        // 命中返回 true 消费；空白返回 false，触摸交给下层
        return false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (line in lines) {
            canvas.drawText(line.text, line.x, line.y, paint)
        }
    }

    override fun onDetachedFromWindow() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frame)
        super.onDetachedFromWindow()
    }
}
