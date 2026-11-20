package com.pilot.wheel

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * 竖列快捷菜单（C2）：与轮盘同源的另一种排布。
 *
 * 轮盘沿弧线铺开，适合「拇指划一圈」；竖列从角落往上排，适合「单手上滑再点」，
 * 图标大、间距宽，误触率更低。两者共用同一份条目、同一套回调，
 * 所以切换样式不会改变轮盘里排了什么。
 *
 * 双排（[doubleRow]）：两条竖列并排，前半条目在贴近角落的一列、后半条目在另一列，
 * 一屏装更多图标（配合双排槽位上限 15）。
 *
 * 动画：整列从角落向上滑入（220ms 减速），配合淡入。选中项放大并高亮。
 */
@SuppressLint("ViewConstructor")
class VerticalMenuView(
    context: Context,
    private val items: List<PilotApp>,
    private val fromLeft: Boolean,
    iconDp: Float,
    private val doubleRow: Boolean,
    private val hapticEnabled: Boolean,
    private val dismissOnOutside: Boolean,
    private val onPick: (PilotApp) -> Unit,
    private val onDismiss: () -> Unit,
    private val onMore: () -> Unit,
) : View(context) {

    private val density = resources.displayMetrics.density
    private val iconSize = iconDp * density
    private val rowHeight = iconSize + 18f * density
    private val margin = 20f * density
    /** 双排两列之间的横向净空 */
    private val columnGap = 10f * density

    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 255, 255, 255)
        textSize = 11f * density
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(5f, 0f, 1f, Color.argb(160, 0, 0, 0))
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(52, 255, 255, 255)
    }
    private val morePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 240, 243, 250)
        textSize = 16f * density
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private var progress = 0f
    private var selected = -1

    /** 导航条内边距：窗口 NO_LIMITS，y=0 从屏幕顶起算，列底须避开三键导航/手势条（与通知面板同口径） */
    private val insetBottomPx: Float
        get() = runCatching {
            rootWindowInsets?.getInsets(android.view.WindowInsets.Type.navigationBars())?.bottom ?: 0
        }.getOrDefault(0).toFloat()

    /** 列底（屏幕底部上方留 margin，另扣导航条）的 y */
    private fun columnBottom(): Float = height - insetBottomPx - margin - 24f * density

    private fun columnCount(): Int = if (doubleRow) 2 else 1

    /** 前后两列的分界下标：前半进第 0 列、后半进第 1 列 */
    private fun splitAt(): Int = (items.size + 1) / 2

    private fun colItems(col: Int): List<PilotApp> =
        if (col == 0) items.subList(0, splitAt()) else items.subList(splitAt(), items.size)

    private fun visibleCount(col: Int): Int =
        colItems(col).size.coerceAtMost(if (doubleRow) MAX_VISIBLE_DOUBLE else MAX_VISIBLE)

    /** 第 [col] 列的 x 中心；第 1 列在第 0 列外侧偏移一个图标宽 + 净空 */
    private fun columnX(col: Int): Float {
        val base = if (fromLeft) margin + iconSize / 2f else width - margin - iconSize / 2f
        if (col == 0) return base
        val dir = if (fromLeft) 1f else -1f
        return base + dir * (iconSize + columnGap)
    }

    private fun rowCenter(col: Int, index: Int): Float =
        columnBottom() - (visibleCount(col) - 1 - index) * rowHeight - iconSize / 2f

    /** 第 [col] 列第 [index] 行 → 全局条目下标（对齐 [items]） */
    private fun globalIndex(col: Int, index: Int): Int =
        if (col == 0) index else splitAt() + index

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 220L
        interpolator = DecelerateInterpolator()
        addUpdateListener {
            progress = it.animatedValue as Float
            invalidate()
        }
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (progress <= 0f) onDismiss()
            }
        })
    }

    init {
        animator.start()
    }

    fun dismiss() {
        animator.cancel()
        animator.setFloatValues(progress, 0f)
        animator.start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (progress <= 0f) return
        val slide = (1f - progress) * 60f * density

        for (col in 0 until columnCount()) {
            val cx = columnX(col)
            val colItems = colItems(col)

            // 「更多」入口：只在第 0 列列顶放一个小圆点
            if (col == 0) {
                val moreY = rowCenter(col, 0) - rowHeight
                canvas.drawText("⋯", cx, moreY + slide, morePaint)
            }

            for (index in 0 until visibleCount(col)) {
                val item = colItems[index]
                val cy = rowCenter(col, index) + slide
                val half = iconSize / 2f
                if (globalIndex(col, index) == selected) {
                    canvas.drawRoundRect(
                        RectF(
                            cx - half - 8f * density, cy - half - 8f * density,
                            cx + half + 8f * density, cy + half + 8f * density,
                        ),
                        14f * density, 14f * density, highlightPaint,
                    )
                }
                val bitmap: Bitmap? = item.icon
                if (bitmap != null) {
                    canvas.drawBitmap(
                        bitmap, null,
                        RectF(cx - half, cy - half, cx + half, cy + half),
                        iconPaint,
                    )
                }
                val label = item.label.take(6)
                canvas.drawText(label, cx, cy + half + 13f * density, labelPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                selected = hitTest(event.x, event.y)
                if (selected == HIT_MORE && hapticEnabled) Haptics.select(context)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val index = hitTest(event.x, event.y)
                if (index != selected) {
                    selected = index
                    if (index >= 0 && hapticEnabled) Haptics.select(context)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                // 37 号改动（S13/2-17）：系统打断手势时复位高亮，此前 selected 残留
                selected = -1
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                when (selected) {
                    HIT_MORE -> {
                        dismiss()
                        onMore()
                    }
                    -1 -> if (dismissOnOutside) dismiss()
                    else -> {
                        val item = items.getOrNull(selected) ?: return dismiss().let { true }
                        dismiss()
                        onPick(item)
                    }
                }
                selected = -1
                return true
            }
        }
        return true
    }

    private fun hitTest(x: Float, y: Float): Int {
        val reach = iconSize * 0.85f
        // 就近判定：相邻热区带各 0.6×rowHeight，中线两侧 0.2×rowHeight 相互重叠，
        // first-match 会把行间隙下半段判给上一位（ACTION_DOWN 即错位震动/高亮），
        // 与轮盘 findSlot 同源问题，同样改成取距离最近的目标
        val threshold = rowHeight * 0.6f
        var best = -1
        var bestDistance = Float.MAX_VALUE
        for (col in 0 until columnCount()) {
            val cx = columnX(col)
            if (kotlin.math.abs(x - cx) > reach) continue
            if (col == 0) {
                val moreY = rowCenter(col, 0) - rowHeight
                val dMore = kotlin.math.abs(y - moreY)
                if (dMore < threshold && dMore < bestDistance) {
                    bestDistance = dMore
                    best = HIT_MORE
                }
            }
            for (index in 0 until visibleCount(col)) {
                val d = kotlin.math.abs(y - rowCenter(col, index))
                if (d < threshold && d < bestDistance) {
                    bestDistance = d
                    best = globalIndex(col, index)
                }
            }
        }
        return best
    }

    companion object {
        private const val MAX_VISIBLE = 6
        private const val MAX_VISIBLE_DOUBLE = 8
        private const val HIT_MORE = -2
    }
}
