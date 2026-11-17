package com.pilot.wheel

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * 轮盘本体。全屏窗口，在角落画一圈图标。
 *
 * 交互对齐同类小窗应用：
 *  - 从角落滑出后，手指可以一路滑到某个图标上松手启动；
 *  - 也可以直接点；
 *  - 点空白收起（可关）。
 */
@SuppressLint("ViewConstructor")
class WheelOverlay(
    context: Context,
    private val items: List<PilotApp>,
    private val fromLeft: Boolean,
    radiusDp: Float,
    iconDp: Float,
    angleDeg: Float,
    private val hapticEnabled: Boolean,
    private val showHint: Boolean,
    private val dismissOnOutside: Boolean,
    private val closeMode: Int,
    private val closeX: Float,
    private val closeDistance: Float,
    private val closeDuration: Long,
    private val closeFromBottom: Float,
    private val onPick: (PilotApp) -> Unit,
    private val onDismiss: () -> Unit,
    private val onMore: () -> Unit,
    private val onRemove: (String) -> Unit,
) : View(context) {

    private val density = resources.displayMetrics.density

    private val ringRadius = radiusDp * density
    private val maxIconDiameter = iconDp * density
    /** 张角，可调。90° 是默认 */
    private val sweepDeg = angleDeg.toDouble()

    /** 弧长 = R * 90°；图标数多时自动缩小，留出白边和间隙，避免沿弧线压在一起 */
    private val iconDiameter: Float = run {
        val count = items.size.coerceAtLeast(1)
        val arcLength = ringRadius * (PI / 2).toFloat()
        min(maxIconDiameter, arcLength / count * 0.74f)
    }
    private val iconRadius = iconDiameter / 2f
    private val hitRadius = maxOf(iconDiameter * 0.70f, 40f * density)

    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B8000000")
    }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E6E9EF")
    }
    /** 图标投在底座上的柔影：让图标像放上去的，而不是印上去的 */
    private val iconShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B9BFC9")
        maskFilter = android.graphics.BlurMaskFilter(4.5f * density, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }
    /** 新拟物气泡：右下暗影弧（光源固定左上，固定光照） */
    private val shadowArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f * density
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#B8BCC2")
        maskFilter = android.graphics.BlurMaskFilter(6f * density, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }
    /** 新拟物气泡：左上亮影弧 */
    private val lightArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
        maskFilter = android.graphics.BlurMaskFilter(4f * density, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * density
        color = Color.parseColor("#FF2F6BFF")
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
    }
    /** 图标之间那根把珠子串起来的细线 */
    private val linkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#66FFFFFF")
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12f * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val hintBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC000000")
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12.5f * density
        textAlign = Paint.Align.CENTER
    }

    private class Slot(val item: PilotApp, val cx: Float, val cy: Float)

    /** 弧线末端的"更多"圆钮位置（纯白拟物软垫，••• 用主题紫） */
    private val moreTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF2F6BFF")
        textSize = 16f * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private var moreCx = 0f
    private var moreCy = 0f

    private var slots: List<Slot> = emptyList()

    /**
     * 入场动画：0 → 1 带轻微过冲（Overshoot），图标从角落「绽放」出来。
     * rawProgress 允许 >1（过冲外扩再回弹），progress 截到 [0,1] 只喂给透明度。
     * 之前在 init 里启动 ValueAnimator，View 还没 attach 时动画不推进，
     * progress 卡在 0 会表现为"滑出来一片空白"——现在改在 onAttachedToWindow 里 post 启动。
     */
    private var rawProgress = 0f
    private var progress = 0f
    private var entryAnimator: ValueAnimator? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (entryAnimator != null) return
        // 系统关了动画（无障碍/省电）就别等了，直接显示完整轮盘
        val motionOff = runCatching {
            android.provider.Settings.Global.getFloat(
                context.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        }.getOrDefault(false)
        if (motionOff) {
            rawProgress = 1f
            progress = 1f
            return
        }
        // attach 之后再 post，确保已经挂上窗口、动画能正常推进
        post {
            entryAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 420L
                interpolator = OvershootInterpolator(1.15f)
                addUpdateListener { anim ->
                    val v = anim.animatedValue as Float
                    rawProgress = v
                    progress = v.coerceIn(0f, 1f)
                    invalidate()
                }
                start()
            }
            // 兜底：无障碍悬浮窗上 ValueAnimator 偶发不推进，420ms 后若还没到 1 就强制落定。
            // 否则 eased 一直小于 1，图标会全部挤在圆心附近（真机出现过「散乱一团」）。
            postDelayed({
                if (progress < 1f) {
                    rawProgress = 1f
                    progress = 1f
                    invalidate()
                }
            }, 520L)
        }
    }

    override fun onDetachedFromWindow() {
        entryAnimator?.cancel()
        entryAnimator = null
        super.onDetachedFromWindow()
    }

    private var hovered: Slot? = null
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var closeSwipeFired = false

    /** 长按图标 = 从轮盘删减 */
    private var longPressFired = false
    private val longPressRemove = Runnable {
        hovered?.let { slot ->
            longPressFired = true
            hovered = null
            invalidate()
            if (hapticEnabled) {
                Haptics.pick(context)
            }
            onRemove(slot.item.pkg)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutSlots(w.toFloat(), h.toFloat())
    }

    private fun layoutSlots(w: Float, h: Float) {
        val cx = if (fromLeft) 0f else w
        // 圆心抬到图标半径之上，否则 0° 位置那个图标会被屏幕底边切掉一半
        val cy = h - iconRadius - 8f * density
        val count = items.size.coerceAtLeast(1)
        // "更多"圆钮占第一个槽位（弧线起点端），应用图标从第二个槽位开始
        val total = count + 1
        val startDeg = -90.0
        val endDeg = if (fromLeft) startDeg + sweepDeg else startDeg - sweepDeg
        val moreRad = Math.toRadians(startDeg + (endDeg - startDeg) * (0.5f / total))
        moreCx = cx + (ringRadius * cos(moreRad)).toFloat()
        moreCy = cy + (ringRadius * sin(moreRad)).toFloat()
        slots = items.mapIndexed { index, item ->
            val t = (index + 1.5f) / total
            val deg = startDeg + (endDeg - startDeg) * t
            val rad = Math.toRadians(deg)
            Slot(
                item = item,
                cx = cx + (ringRadius * cos(rad)).toFloat(),
                cy = cy + (ringRadius * sin(rad)).toFloat(),
            )
        }
    }

    private fun findSlot(x: Float, y: Float): Slot? =
        slots.firstOrNull { hypot(x - it.cx, y - it.cy) <= hitRadius }

    override fun onDraw(canvas: Canvas) {
        // 按要求不再压遮罩：整屏保持背景原样（配合窗口模糊），
        // 顺带把状态栏那条的明暗差也一并消掉了
        scrimPaint.alpha = 0
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)
        if (showHint) drawHint(canvas)
        // 过冲阶段 rawProgress > 1：位置随 raw 外扩再回弹，透明度只随 progress（≤1）渐显
        val eased = 0.55f + 0.45f * rawProgress
        val alpha = (255 * progress).toInt()
        val centerX = if (fromLeft) 0f else width.toFloat()
        // 与 layoutSlots 里的圆心保持一致
        val centerY = height - iconRadius - 8f * density
        drawArcLink(canvas, eased, centerX, centerY)
        drawSlots(canvas, eased, alpha, centerX, centerY)
        drawMoreButton(canvas, eased, alpha, centerX, centerY)
    }

    /** 顶部操作提示 */
    private fun drawHint(canvas: Canvas) {
        val hint = "滑到图标松手 · 也可以直接点 · 点空白收起"
        val hintWidth = hintPaint.measureText(hint)
        val padH = 16f * density
        val hintCy = 46f * density
        hintBgPaint.alpha = (204 * progress).toInt()
        canvas.drawRoundRect(
            (width - hintWidth) / 2f - padH,
            hintCy - 14f * density,
            (width + hintWidth) / 2f + padH,
            hintCy + 14f * density,
            28f * density,
            28f * density,
            hintBgPaint,
        )
        hintPaint.alpha = (255 * progress).toInt()
        canvas.drawText(hint, width / 2f, hintCy + 4.5f * density, hintPaint)
    }

    /** 先画串珠的线，让图标压在上面 */
    private fun drawArcLink(canvas: Canvas, eased: Float, centerX: Float, centerY: Float) {
        if (slots.size <= 1) return
        val startDeg = -90.0
        val endDeg = if (fromLeft) startDeg + sweepDeg else startDeg - sweepDeg
        val path = android.graphics.Path()
        val steps = 24
        for (i in 0..steps) {
            val t = 0.14f + 0.80f * (i.toFloat() / steps)
            val deg = startDeg + (endDeg - startDeg) * t
            val rad = Math.toRadians(deg)
            val px = centerX + (ringRadius * cos(rad)).toFloat() * eased
            val py = centerY + (ringRadius * sin(rad)).toFloat() * eased
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        linkPaint.alpha = (255 * progress).toInt()
        canvas.drawPath(path, linkPaint)
    }

    private fun drawSlots(canvas: Canvas, eased: Float, alpha: Int, centerX: Float, centerY: Float) {
        for (slot in slots) {
            val cx = centerX + (slot.cx - centerX) * eased
            val cy = centerY + (slot.cy - centerY) * eased

            val isHover = slot === hovered
            val scale = if (isHover) 1.16f else 1f
            val r = iconRadius * scale
            val bubbleR = r + 3f * density

            // 新拟物气泡：同色本体 #E0E5EC + 双重阴影弧（左上白 / 右下 #B8BCC2，光源固定左上）
            bubblePaint.alpha = (250 * progress).toInt()
            canvas.drawCircle(cx, cy, bubbleR, bubblePaint)
            if (progress > 0.15f) {
                shadowArcPaint.alpha = (170 * progress).toInt()
                canvas.drawArc(
                    cx - bubbleR + 2f, cy - bubbleR + 2f, cx + bubbleR - 2f, cy + bubbleR - 2f,
                    -35f, 95f, false, shadowArcPaint,
                )
                lightArcPaint.alpha = (200 * progress).toInt()
                canvas.drawArc(
                    cx - bubbleR + 2f, cy - bubbleR + 2f, cx + bubbleR - 2f, cy + bubbleR - 2f,
                    145f, 95f, false, lightArcPaint,
                )
            }
            if (isHover) {
                ringPaint.alpha = (255 * progress).toInt()
                canvas.drawCircle(cx, cy, bubbleR - 1.5f * density, ringPaint)
            }

            // 底座内凹环：与外圈外凸弧反向，图标看起来是嵌进凹槽里的
            if (progress > 0.15f) {
                val innerR = bubbleR * 0.86f
                shadowArcPaint.alpha = (140 * progress).toInt()
                canvas.drawArc(
                    cx - innerR, cy - innerR, cx + innerR, cy + innerR,
                    192f, 78f, false, shadowArcPaint,
                )
                lightArcPaint.alpha = (168 * progress).toInt()
                canvas.drawArc(
                    cx - innerR, cy - innerR, cx + innerR, cy + innerR,
                    12f, 78f, false, lightArcPaint,
                )
            }

            val icon = slot.item.icon
            if (icon != null) {
                val half = r * 0.78f
                // 先落一层柔影（偏下 3dp），图标才有厚度
                iconShadowPaint.alpha = (alpha * 0.55f).toInt()
                canvas.drawCircle(cx, cy + 3f * density, half * 0.98f, iconShadowPaint)
                val dst = RectF(cx - half, cy - half, cx + half, cy + half)
                bitmapPaint.alpha = alpha
                canvas.drawBitmap(icon, null, dst, bitmapPaint)
            }

            // 平时不显示名字（和参考图一致），只有滑到某个图标上才浮出
            if (isHover) {
                labelPaint.alpha = (255 * progress).toInt()
                canvas.drawText(
                    slot.item.label,
                    cx,
                    cy - bubbleR - 8f * density,
                    labelPaint,
                )
            }
        }
    }

    /** 弧线末端的"更多"圆钮：纯白拟物软垫（同色白 + 双重阴影弧），••• 主题紫 */
    private fun drawMoreButton(canvas: Canvas, eased: Float, alpha: Int, centerX: Float, centerY: Float) {
        val moreR = iconRadius * 0.9f
        val mcx = centerX + (moreCx - centerX) * eased
        val mcy = centerY + (moreCy - centerY) * eased
        // 白底软垫（与应用图标气泡同一套光照：左上亮 / 右下暗）
        bubblePaint.alpha = (250 * progress).toInt()
        canvas.drawCircle(mcx, mcy, moreR, bubblePaint)
        if (progress > 0.15f) {
            shadowArcPaint.alpha = (170 * progress).toInt()
            canvas.drawArc(
                mcx - moreR + 2f, mcy - moreR + 2f, mcx + moreR - 2f, mcy + moreR - 2f,
                -35f, 95f, false, shadowArcPaint,
            )
            lightArcPaint.alpha = (200 * progress).toInt()
            canvas.drawArc(
                mcx - moreR + 2f, mcy - moreR + 2f, mcx + moreR - 2f, mcy + moreR - 2f,
                145f, 95f, false, lightArcPaint,
            )
        }
        // 中心小凹点收一下，看起来像按过的实体键
        moreTextPaint.alpha = alpha
        // 手绘三个等距圆点：字符「•••」的字距过大，改用圆点精确控制间距
        val dotR = 2.4f * density
        val gap = 7.5f * density
        for (i in -1..1) {
            canvas.drawCircle(mcx + i * gap, mcy, dotR, moreTextPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> onActionDown(event)
            MotionEvent.ACTION_MOVE -> onActionMove(event)
            MotionEvent.ACTION_UP -> onActionUp(event)
            MotionEvent.ACTION_CANCEL -> {
                hovered = null
                invalidate()
            }
        }
        return true
    }

    private fun onActionDown(event: MotionEvent) {
        downX = event.x
        downY = event.y
        downTime = event.eventTime
        closeSwipeFired = false
        hovered = findSlot(event.x, event.y)
        longPressFired = false
        // 按住图标 600ms 不动 = 从轮盘删减
        if (hovered != null) {
            postDelayed(longPressRemove, 600L)
        }
        invalidate()
    }

    private fun onActionMove(event: MotionEvent) {
        tryCloseSwipe(event)
        updateHover(event)
    }

    /**
     * 关闭方式：从底部落点附近向上滑收起（移植自同类小窗应用的上滑关闭）
     * 只认落点 X 附近、离底边足够近、时长足够短的上滑；从图标起手不触发。
     */
    private fun tryCloseSwipe(event: MotionEvent) {
        if (closeMode == Prefs.CLOSE_TAP || closeSwipeFired) return
        val targetX = width * closeX / 100f
        val xOk = abs(downX - targetX) <= 0.22f * width.coerceAtLeast(1)
        val yOk = downY >= height - (closeFromBottom + 56f) * density
        val startOnIcon = findSlot(downX, downY) != null
        val dy = event.y - downY
        val duration = event.eventTime - downTime
        if (xOk && yOk && !startOnIcon &&
            dy <= -closeDistance * density &&
            duration in 1L..closeDuration
        ) {
            closeSwipeFired = true
            if (hapticEnabled) {
                Haptics.tick(context)
            }
            onDismiss()
        }
    }

    private fun updateHover(event: MotionEvent) {
        val hit = findSlot(event.x, event.y)
        if (hypot(event.x - downX, event.y - downY) > 12f * density) {
            removeCallbacks(longPressRemove)
        }
        if (hit !== hovered) {
            hovered = hit
            if (hit != null && hapticEnabled) {
                Haptics.tick(context)
            }
            invalidate()
        }
    }

    private fun onActionUp(event: MotionEvent): Boolean {
        removeCallbacks(longPressRemove)
        val wasLongPress = longPressFired
        longPressFired = false
        val target = hovered ?: findSlot(event.x, event.y)
        hovered = null
        invalidate()
        if (wasLongPress) {
            // 长按已触发删减，UP 不再判定启动/收起
            return true
        }
        // "更多"钮优先判定：点它打开应用面板
        val moreR = iconRadius * 0.78f + 12f * density
        if (hypot(event.x - moreCx, event.y - moreCy) <= moreR) {
            if (hapticEnabled) {
                Haptics.select(context)
            }
            performClick()
            onMore()
            return true
        }
        if (target != null) {
            if (hapticEnabled) {
                Haptics.pick(context)
            }
            performClick()
            onPick(target.item)
            return true
        }
        if (dismissOnOutside &&
            !closeSwipeFired &&
            closeMode != Prefs.CLOSE_SWIPE &&
            hypot(event.x - downX, event.y - downY) < 24f * density
        ) {
            // 只有基本没移动的"点击空白"才收起，避免拖动途中误关
            onDismiss()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
