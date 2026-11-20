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
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
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
    private val doubleRow: Boolean,
    private val hapticEnabled: Boolean,
    private val showHint: Boolean,
    private val dismissOnOutside: Boolean,
    private val onPick: (PilotApp) -> Unit,
    private val onDismiss: () -> Unit,
    private val onMore: () -> Unit,
    private val onRemove: (String) -> Unit,
) : View(context) {

    private companion object {
        /**
         * 62 号：可交互门槛（展开进度）。
         *
         * 入场动画 / 跟手阶段图标是从锚点「向外绽放」的：`eased = 0.55 + 0.45 × rawProgress`，
         * 进度 0.2 时全部槽位都挤在离角落不到半个半径的范围内。此时手指只要抬起，
         * 就会被 `findSlot` 判成命中了最近的那个槽位——而离角落最近、且正好落在
         * 「从角落竖直上滑」这条轨迹上的，就是第 0 个槽位。真机表现即
         * 「滑出轮盘时有时直接打开轮盘第一个 app」。
         *
         * 因此展开到 95% 之前一律不判定槽位/「更多」：这一段时间只做动画，不做选择。
         */
        const val PICK_MIN_PROGRESS = 0.95f
    }

    private val density = resources.displayMetrics.density

    private val ringRadius = radiusDp * density
    private val maxIconDiameter = iconDp * density
    /** 张角，可调。90° 是默认 */
    private val sweepDeg = angleDeg.toDouble()
    private val sweepRad = Math.toRadians(sweepDeg).toFloat()

    /** 双排时内环半径：外环与内环之间留一个图标直径 + 6dp 的净空 */
    private val innerRingRadius: Float =
        (ringRadius - maxIconDiameter - 6f * density).coerceAtLeast(ringRadius * 0.5f)

    /**
     * 弧长 = R × 实际张角（弧度）；旧版写死 R × π/2，只在 90° 成立。
     * 150° 时图标直径仍按 90° 的弧长分配，槽位只是被拉得更开，图标相对槽距偏小；
     * 45° 时槽距缩为原来一半，8 槽下图标压叠。两者都必须按真实 sweep 重算。
     * 双排：每条环只装约一半条目，但内环半径更小、弧更短，故以内环弧长为准，保证两条环都不压叠。
     */
    private val iconDiameter: Float = run {
        val count = items.size.coerceAtLeast(1)
        val perRow = if (doubleRow) (count + 1) / 2 else count
        val baseR = if (doubleRow) innerRingRadius else ringRadius
        val arcLength = baseR * sweepRad
        min(maxIconDiameter, arcLength / perRow.coerceAtLeast(1) * 0.74f)
    }
    private val iconRadius = iconDiameter / 2f

    /**
     * 相邻槽位的弧长间距（"更多"圆钮也占一个槽位）。
     * 命中半径必须收在它的一半以内：否则相邻图标的热区互相覆盖，
     * 而 findSlot 又按索引顺序取第一个命中的，点第 N 个图标会落到第 N-1 个上。
     * 实测最坏组合（半径 120dp + 8 个图标）槽距只有约 21dp，旧热区却固定 ≥40dp。
     * 双排以内环（弧更短、更密）为准。
     */
    private val slotSpacing: Float = if (doubleRow) {
        val innerCount = (items.size - (items.size + 1) / 2).coerceAtLeast(1)
        innerRingRadius * sweepRad / innerCount
    } else {
        ringRadius * sweepRad / (items.size + 1).coerceAtLeast(1)
    }

    /** 热区：优先给足（图标 1.6 倍、不小于 24dp），但不越过相邻槽位中线，且不小于图标自身半径 */
    private val hitRadius: Float = minOf(
        maxOf(iconRadius * 1.6f, 24f * density),
        slotSpacing * 0.46f,
    ).coerceAtLeast(iconRadius)

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
    /** 微信消息入口的未读红点：白色描边 + 系统红，压在图标右上角 */
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF3B30")
    }
    private val badgeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
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

    /**
     * 40 号：跟手模式。唤出阈值一达到轮盘就 addView，入场进度不再由固定 420ms 动画驱动，
     * 而是由 CornerHotspot 把手指位移实时喂进来（updateFollow）；松手时按手指位置
     * 判定启动（endFollow）或原地展开（settleEntry）。
     */
    private var followMode = false

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
        // attach 之后再 post，确保已经挂上窗口、动画能正常推进；
        // 40 号：跟手模式 progress 由手指驱动，不启动固定时长动画。
        // 注意时序：beginFollow 在 addView 返回后立即调用，而这里的 post 尚未执行，
        // 必须在 post 块内重查 followMode，否则入场动画会覆盖跟手进度。
        post {
            if (followMode) return@post
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
            // 37 号改动（S11/1-5）：兑底 runnable 存字段，detach 时可撤销
            entryFallback = Runnable {
                if (progress < 1f) {
                    rawProgress = 1f
                    progress = 1f
                    invalidate()
                }
            }
            postDelayed(entryFallback, 520L)
        }
    }

    override fun onDetachedFromWindow() {
        entryAnimator?.cancel()
        entryAnimator = null
        // 37 号改动（S11/1-3/1-5）：detach 时一并撤销长按 runnable 与 520ms 兑底 ——
        // 此前宿主移窗后 600ms runnable 照常执行，onRemove 会在窗口移除后触发
        removeCallbacks(longPressRemove)
        entryFallback?.let { removeCallbacks(it) }
        entryFallback = null
        super.onDetachedFromWindow()
    }

    /** 520ms 兑底落定 runnable（37 号改动：存字段以便 detach 撤销） */
    private var entryFallback: Runnable? = null

    private var hovered: Slot? = null
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L

    /**
     * 62 号：本次手势是否见过 ACTION_DOWN。
     *
     * 轮盘是**在手指已经按下之后**才 addView 的全屏窗口（呼出瞬间建窗）。部分 ROM 会把
     * 后续 MOVE/UP 派发给这个新窗口（无 DOWN），此时 `downX/downY` 仍是 0，
     * 而 `onActionUp` 会拿「抬起点」直接判槽位 —— 又一次「松手就启动第一个 app」。
     * 没有 DOWN 的手势一律不判定启动/收起。
     */
    private var hasDown = false

    /** 展开到可交互门槛之前不做任何槽位判定（见 [PICK_MIN_PROGRESS]） */
    private fun interactive(): Boolean = rawProgress >= PICK_MIN_PROGRESS

    /** 进入跟手模式：接管入场进度，取消任何已在跑的入场动画 */
    fun beginFollow(initialProgress: Float) {
        followMode = true
        entryAnimator?.cancel()
        entryAnimator = null
        entryFallback?.let { removeCallbacks(it) }
        entryFallback = null
        rawProgress = initialProgress
        progress = initialProgress.coerceIn(0f, 1f)
        invalidate()
    }

    /** 跟手中每次手指移动：驱动展开进度，同时高亮手指下的图标（含触感反馈） */
    fun updateFollow(progressValue: Float, x: Float, y: Float) {
        if (!followMode) return
        rawProgress = progressValue
        progress = progressValue.coerceIn(0f, 1f)
        val hit = findSlot(x, y)
        if (hit !== hovered) {
            hovered = hit
            if (hit != null && hapticEnabled) {
                Haptics.tick(context)
            }
        }
        invalidate()
    }

    /**
     * 跟手松手：一律原地展开成完整轮盘，**不判定启动/「更多」**。
     *
     * 防误触（用户反馈「滑出轮盘时频繁误触」）：呼出是一段从角落向上的连续滑动，
     * 手指路径恰好压在第 0 个槽位（离角落最近、贴竖直上滑轨迹）与「更多」钮上。
     * 若松手即按落点判定，用户只想把轮盘滑出来，却常被误判成「启动第一个 app」。
     * 因此这里只做落定展开，后续由正常 tap（onActionDown→onActionUp）再判定启动/收起。
     * 返回 false 表示未处理启动，服务方照旧排自动收起。
     */
    fun endFollow(x: Float, y: Float): Boolean {
        if (!followMode) return false
        followMode = false
        removeCallbacks(longPressRemove)
        hovered = null
        invalidate()
        settleEntry()
        return false
    }

    /** 跟手被系统打断 / 多指取消：静默退出跟手模式，由服务方移窗 */
    fun cancelFollow() {
        followMode = false
        hovered = null
        invalidate()
    }

    /** 原地展开：从当前进度短动画落到全开（系统关动画时直接落定） */
    private fun settleEntry() {
        val motionOff = runCatching {
            android.provider.Settings.Global.getFloat(
                context.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        }.getOrDefault(false)
        if (motionOff || rawProgress >= 1f) {
            rawProgress = 1f
            progress = 1f
            invalidate()
            return
        }
        entryAnimator?.cancel()
        entryAnimator = ValueAnimator.ofFloat(rawProgress, 1f).apply {
            duration = 200L
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { anim ->
                val v = anim.animatedValue as Float
                rawProgress = v
                progress = v.coerceIn(0f, 1f)
                invalidate()
            }
            start()
        }
    }

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

    /** 状态栏 / 导航条内边距（px）：全屏 NO_LIMITS 窗口坐标从屏幕 y=0 起算，绘制须自行扣 inset（与通知面板/侧边栏同一套口径） */
    private val insetTopPx: Float
        get() = runCatching {
            rootWindowInsets?.getInsets(android.view.WindowInsets.Type.statusBars())?.top ?: 0
        }.getOrDefault(0).toFloat()
    private val insetBottomPx: Float
        get() = runCatching {
            rootWindowInsets?.getInsets(android.view.WindowInsets.Type.navigationBars())?.bottom ?: 0
        }.getOrDefault(0).toFloat()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutSlots(w.toFloat(), h.toFloat())
    }

    // 37 号改动（S5/2-12）：记录布局时的导航条 inset；onDraw 里若运行时变化则重排槽位，
    // 此前槽位用布局时刻 inset、圆心用实时 inset，导航条 inset 变化时槽位与圆心错位
    private var laidOutInsetBottomPx = Float.NaN

    private fun layoutSlots(w: Float, h: Float) {
        laidOutInsetBottomPx = insetBottomPx
        val cx = if (fromLeft) 0f else w
        // 圆心抬到图标半径之上，否则 0° 位置那个图标会被屏幕底边切掉一半；
        // 再抬 navigationBars：不扣 inset 时最低图标整圆压进三键导航区（48dp 内）
        val cy = h - insetBottomPx - iconRadius - 8f * density
        val count = items.size.coerceAtLeast(1)
        val startDeg = -90.0
        val endDeg = if (fromLeft) startDeg + sweepDeg else startDeg - sweepDeg

        if (!doubleRow) {
            // "更多"圆钮占第一个槽位（弧线起点端），应用图标从第二个槽位开始
            val total = count + 1
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
        } else {
            // 双排：前半条目走外环（含起点端「更多」钮），后半条目走内环。
            // 内环半径更小、弧更短，各自按本环条目数等分，两条环互不压叠。
            val outerCount = (count + 1) / 2
            val outerTotal = outerCount + 1
            val moreRad = Math.toRadians(startDeg + (endDeg - startDeg) * (0.5f / outerTotal))
            moreCx = cx + (ringRadius * cos(moreRad)).toFloat()
            moreCy = cy + (ringRadius * sin(moreRad)).toFloat()
            val innerCount = (count - outerCount).coerceAtLeast(1)
            slots = items.mapIndexed { index, item ->
                if (index < outerCount) {
                    val t = (index + 1.5f) / outerTotal
                    val deg = startDeg + (endDeg - startDeg) * t
                    val rad = Math.toRadians(deg)
                    Slot(
                        item = item,
                        cx = cx + (ringRadius * cos(rad)).toFloat(),
                        cy = cy + (ringRadius * sin(rad)).toFloat(),
                    )
                } else {
                    val t = (index - outerCount + 0.5f) / innerCount
                    val deg = startDeg + (endDeg - startDeg) * t
                    val rad = Math.toRadians(deg)
                    Slot(
                        item = item,
                        cx = cx + (innerRingRadius * cos(rad)).toFloat(),
                        cy = cy + (innerRingRadius * sin(rad)).toFloat(),
                    )
                }
            }
        }
    }

    /**
     * 就近判定：热区在图标密集时仍可能相邻相切，按距离取最近的一个，
     * 不再用 firstOrNull 按索引顺序取首个命中（那样会让后位图标永远抢不过前一位）。
     * 40 号：按「当前绘制位置」判定而非最终槽位——入场/跟手阶段图标从圆心向外过渡，
     * 手指指着的视觉位置就是命中位置，否则跟手阶段滑到图标上松手会判空。
     */
    private fun findSlot(x: Float, y: Float): Slot? {
        if (slots.isEmpty() || width == 0) return null
        // 62 号：未展开到门槛前不判定——否则「压缩在锚点附近的槽位」会被沿途的手指吃掉
        if (!interactive()) return null
        val eased = 0.55f + 0.45f * rawProgress
        val centerX = if (fromLeft) 0f else width.toFloat()
        val centerY = height - insetBottomPx - iconRadius - 8f * density
        var best: Slot? = null
        var bestDistance = Float.MAX_VALUE
        for (slot in slots) {
            val cx = centerX + (slot.cx - centerX) * eased
            val cy = centerY + (slot.cy - centerY) * eased
            val d = hypot(x - cx, y - cy)
            if (d <= hitRadius && d < bestDistance) {
                bestDistance = d
                best = slot
            }
        }
        return best
    }

    override fun onDraw(canvas: Canvas) {
        // 37 号改动（S5/2-12）：导航条 inset 运行时变化时重排槽位，保证槽位与圆心同一 inset 口径
        if (laidOutInsetBottomPx != insetBottomPx) {
            layoutSlots(width.toFloat(), height.toFloat())
        }
        // 按要求不再压遮罩：整屏保持背景原样（配合窗口模糊），
        // 顺带把状态栏那条的明暗差也一并消掉了
        scrimPaint.alpha = 0
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)
        if (showHint) drawHint(canvas)
        // 过冲阶段 rawProgress > 1：位置随 raw 外扩再回弹，透明度只随 progress（≤1）渐显
        val eased = 0.55f + 0.45f * rawProgress
        val alpha = (255 * progress).toInt()
        val centerX = if (fromLeft) 0f else width.toFloat()
        // 与 layoutSlots 里的圆心保持一致（含 navigationBars inset）
        val centerY = height - insetBottomPx - iconRadius - 8f * density
        drawArcLink(canvas, eased, centerX, centerY)
        drawSlots(canvas, eased, alpha, centerX, centerY)
        drawMoreButton(canvas, eased, alpha, centerX, centerY)
    }

    /** 顶部操作提示 */
    private fun drawHint(canvas: Canvas) {
        val hint = "滑到图标松手 · 也可以直接点 · 点空白收起"
        val hintWidth = hintPaint.measureText(hint)
        val padH = 16f * density
        // 下移避开状态栏：挖孔/高屏机型状态栏 32–42dp，固定 46dp 时胶囊上沿压进状态栏文字带
        val hintCy = insetTopPx + 46f * density
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
        linkPaint.alpha = (255 * progress).toInt()
        drawLinkArc(canvas, eased, centerX, centerY, ringRadius, startDeg, endDeg)
        if (doubleRow) {
            drawLinkArc(canvas, eased, centerX, centerY, innerRingRadius, startDeg, endDeg)
        }
    }

    private fun drawLinkArc(
        canvas: Canvas,
        eased: Float,
        centerX: Float,
        centerY: Float,
        radius: Float,
        startDeg: Double,
        endDeg: Double,
    ) {
        val path = android.graphics.Path()
        val steps = 24
        for (i in 0..steps) {
            val t = 0.14f + 0.80f * (i.toFloat() / steps)
            val deg = startDeg + (endDeg - startDeg) * t
            val rad = Math.toRadians(deg)
            val px = centerX + (radius * cos(rad)).toFloat() * eased
            val py = centerY + (radius * sin(rad)).toFloat() * eased
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
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

            // 未读红点：微信消息入口专用，压在图标右上角，和普通项明确区分
            if (slot.item.badge) {
                val br = r * 0.28f
                val bx = cx + r * 0.66f
                val by = cy - r * 0.66f
                badgeBorderPaint.alpha = alpha
                badgePaint.alpha = alpha
                canvas.drawCircle(bx, by, br + 2f * density, badgeBorderPaint)
                canvas.drawCircle(bx, by, br, badgePaint)
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

    /** 「更多」钮视觉半径（37 号改动 S8/3-1：命中半径由此单一来源推导，不再两套常量） */
    private fun moreButtonRadius(): Float = iconRadius * 0.9f

    /** 40 号：「更多」钮命中判定抽出来，onActionUp 与 endFollow 共用同一套口径 */
    private fun hitMore(x: Float, y: Float): Boolean {
        // 62 号：与槽位同一门槛——「更多」钮同样贴着锚点端，展开前不许命中
        if (!interactive()) return false
        // 命中半径 = 视觉半径 + 固定延伸量；「更多」钮优先判定，
        // 但只在它确实是最近目标时生效（与首个槽位只隔一个槽距）。
        // 注意跟手阶段钮也在从圆心外扩的途中，命中要按当前绘制位置算。
        val eased = 0.55f + 0.45f * rawProgress
        val centerX = if (fromLeft) 0f else width.toFloat()
        val centerY = height - insetBottomPx - iconRadius - 8f * density
        val mcx = centerX + (moreCx - centerX) * eased
        val mcy = centerY + (moreCy - centerY) * eased
        val moreR = moreButtonRadius() + 12f * density
        val dMore = hypot(x - mcx, y - mcy)
        if (dMore > moreR) return false
        val underFinger = findSlot(x, y) ?: return true
        // 与图标比距离时同样按当前绘制位置（eased），跟手阶段口径才一致
        val scx = centerX + (underFinger.cx - centerX) * eased
        val scy = centerY + (underFinger.cy - centerY) * eased
        return dMore <= hypot(x - scx, y - scy)
    }

    /** 弧线末端的"更多"圆钮：纯白拟物软垫（同色白 + 双重阴影弧），••• 主题紫 */
    private fun drawMoreButton(canvas: Canvas, eased: Float, alpha: Int, centerX: Float, centerY: Float) {
        val moreR = moreButtonRadius()
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
            MotionEvent.ACTION_DOWN -> {
                hasDown = true
                onActionDown(event)
            }
            MotionEvent.ACTION_MOVE -> if (hasDown) onActionMove(event)
            MotionEvent.ACTION_UP -> {
                // 62 号：没有 DOWN 的孤立 UP 是「建窗时继承的手势尾巴」，不判启动也不判收起
                val hadDown = hasDown
                hasDown = false
                if (hadDown) onActionUp(event)
            }
            MotionEvent.ACTION_CANCEL -> {
                // 37 号改动（S11/1-4）：CANCEL 也要撤长按 runnable，否则被系统打断的手势
                // 仍会在 600ms 后触发删减
                hasDown = false
                removeCallbacks(longPressRemove)
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
        hovered = findSlot(event.x, event.y)
        longPressFired = false
        // 按住图标 600ms 不动 = 从轮盘删减
        if (hovered != null) {
            postDelayed(longPressRemove, 600L)
        }
        invalidate()
    }

    private fun onActionMove(event: MotionEvent) {
        updateHover(event)
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
        // 37 号改动（S8/3-1）：命中半径 = 视觉半径 + 固定延伸量，单一来源推导；
        // 此前 0.78f+12dp 与 0.9f 两套独立常量，视觉区与热区不一致。
        // 「更多」钮优先判定，但只在它确实是最近目标时生效（与首个槽位只隔一个槽距）
        if (hitMore(event.x, event.y)) {
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
