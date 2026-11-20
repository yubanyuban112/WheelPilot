package com.pilot.wheel

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Context
import androidx.core.graphics.drawable.toBitmap
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 轮盘外观实时预览（3.18.1，3.19.2 起按角落分模式 + 入场动画）。
 *
 * 几何与 [WheelOverlay] 同源：
 *  - 圆心贴角落，起点角 270°（竖直向上）；左下向 +sweep、右下向 −sweep（镜像）；
 *  - 槽位 0.5 是「•••」，图标从 1.5 起（与 layoutSlots 的 total 分配一致）；
 *  - 半径按当前 radiusDp 与可用画布等比缩放，图标 / 半径的比例保持真实。
 *
 * 三种角落模式（3.19.2 需求）：
 *  - 左右都开：画布左右各一张角落视图，中间发丝线分隔，两角同时反向展开；
 *  - 只开一边：该角独占整行画布、放大并居中，画布纵向拉高 1.45 倍；
 *  - 都关：居中提示「轮盘不会出现」。
 *
 * 动画：2.6s 一轮的入场演示（1.5s 展开、0.9s 停留、随后从头再来），
 * 图标按槽位错峰从圆心就位。系统关闭动画（开发者选项/无障碍）时直接落定。
 */
@Composable
internal fun WheelAppearancePreview(
    radiusDp: Float,
    iconDp: Float,
    angle: Float,
    slots: Int,
    modifier: Modifier = Modifier,
    leftEnabled: Boolean = true,
    rightEnabled: Boolean = true,
    title: String = "当前轮盘",
    note: String? = "拖动下面的滑杆，这张预览即时变化；图标会不会挤在一起，这里就能看出来。",
    canvasWidth: Dp = 152.dp,
    canvasHeight: Dp = 126.dp,
    horizontalPadding: Dp = UiSpace.Content,
) {
    val context = LocalContext.current
    // 只解析轮盘里那几个包（≤8），不调 launchableApps 全量加载 —— 设置页不该为了
    // 一张预览图去解析几百个图标，那会把进「轮盘外观」的响应拖到几百毫秒
    val items: List<PreviewItem> = remember {
        Prefs.load(context).map { key -> previewItemOf(context, key) }
    }

    val measurer = rememberTextMeasurer()
    val emptyHint = remember {
        measurer.measure(
            "轮盘还是空的，去「应用」页放几个",
            TextStyle(fontSize = 12.sp, color = UiTokens.InkSub),
        )
    }
    val offHint = remember {
        measurer.measure(
            "左右角落呼出都已关闭，轮盘不会出现",
            TextStyle(fontSize = 12.sp, color = UiTokens.InkSub),
        )
    }
    val moreLabel = remember {
        measurer.measure("•••", UiType.badgeGlyph.copy(color = UiTokens.Primary))
    }

    val bothCorners = leftEnabled && rightEnabled
    val singleCorner = leftEnabled != rightEnabled
    val noCorner = !leftEnabled && !rightEnabled
    // 都在关掉、或系统关了动画：直接给完整轮盘，不做空转。
    // 动效开关走项目单源 rememberMotionEnabled()，与 neuPressable / cascadeEnter 同一判定
    val motionOn = rememberMotionEnabled()
    val transition = rememberInfiniteTransition(label = "wheelPreview")
    val animated by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            // 1.5s 展开 → 0.9s 停留 → 回到起点（Restart 直接跳到 0，不留收拢动画）
            animation = keyframes {
                durationMillis = 2600
                0f at 0 using FastOutSlowInEasing
                1f at 1500
                1f at 2400
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "wheelPreviewProgress",
    )
    val progress = if (!motionOn || noCorner) 1f else animated

    Column(modifier = modifier.fillMaxWidth().padding(horizontal = horizontalPadding)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = UiTokens.Ink,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${items.size} / $slots",
                style = MaterialTheme.typography.labelMedium,
                color = UiTokens.Primary,
            )
        }
        Spacer(Modifier.height(UiSpace.xs))

        if (singleCorner) {
            // 单角：整行画布 + 加高，轮盘放大居中（不再是角落里的一小团）
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(canvasHeight * 1.45f),
            ) {
                drawWheelPreview(
                    items = items,
                    radiusDp = radiusDp,
                    iconDp = iconDp,
                    angle = angle,
                    progress = progress,
                    leftEnabled = leftEnabled,
                    rightEnabled = rightEnabled,
                    emptyHint = emptyHint,
                    offHint = offHint,
                    moreLabel = moreLabel,
                )
            }
            Spacer(Modifier.height(UiSpace.xs))
            Text(
                "真实比例 · 仅${if (leftEnabled) "左下" else "右下"}角，已放大居中演示",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = UiTokens.Ink,
            )
            if (note != null) {
                Spacer(Modifier.height(UiSpace.xxs))
                Text(note, style = MaterialTheme.typography.labelSmall, color = UiTokens.InkSub)
            }
        } else if (bothCorners) {
            // 双角：整行画布，两角各占半幅。
            // 3.19.2 之前这里和「无角」共用 152dp 窄画布，每角只有 76dp：8 个应用时
            // 图标外沿越过中线被硬裁，左右两团压在一起，看着就是「两边重叠」。
            Canvas(modifier = Modifier.fillMaxWidth().height(canvasHeight)) {
                drawWheelPreview(
                    items = items,
                    radiusDp = radiusDp,
                    iconDp = iconDp,
                    angle = angle,
                    progress = progress,
                    leftEnabled = leftEnabled,
                    rightEnabled = rightEnabled,
                    emptyHint = emptyHint,
                    offHint = offHint,
                    moreLabel = moreLabel,
                )
            }
            Spacer(Modifier.height(UiSpace.xs))
            Text(
                "真实比例 · 左右各一角，两角之间互不越界",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = UiTokens.Ink,
            )
            if (note != null) {
                Spacer(Modifier.height(UiSpace.xxs))
                Text(note, style = MaterialTheme.typography.labelSmall, color = UiTokens.InkSub)
            }
        } else {
            // 无角：保持「角落视窗 + 右侧说明」的横排，画布宽度仍是 canvasWidth
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(modifier = Modifier.width(canvasWidth).height(canvasHeight)) {
                    drawWheelPreview(
                        items = items,
                        radiusDp = radiusDp,
                        iconDp = iconDp,
                        angle = angle,
                        progress = progress,
                        leftEnabled = leftEnabled,
                        rightEnabled = rightEnabled,
                        emptyHint = emptyHint,
                        offHint = offHint,
                        moreLabel = moreLabel,
                    )
                }
                Spacer(Modifier.width(UiSpace.m))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "真实比例",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = UiTokens.Ink,
                    )
                    if (note != null) {
                        Spacer(Modifier.height(UiSpace.xxs))
                        Text(
                            note,
                            style = MaterialTheme.typography.labelSmall,
                            color = UiTokens.InkSub,
                        )
                    }
                }
            }
        }
    }
}

/** 单个包/工具 → 预览条目；取不到图标时给 null，绘制侧回退成色块 */
private fun previewItemOf(context: Context, key: String): PreviewItem {
    if (QuickTools.isToolKey(key)) {
        // 与「更多」面板工具卡、应用页工具格共用 QuickTools.visual()，三处形状一致
        val tool = QuickTools.tool(QuickTools.toolIdOf(key))
            ?: return PreviewItem(null, "工具", QuickTools.ICON_CONTOUR)
        val visual = QuickTools.visual(context, tool)
        return PreviewItem(visual.icon.asImageBitmap(), tool.short, visual.contour)
    }
    val pm = context.packageManager
    return runCatching {
        val bitmap = pm.getApplicationIcon(key).toBitmap(72, 72)
        val label = runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(key, 0)).toString()
        }.getOrDefault(key)
        PreviewItem(bitmap.asImageBitmap(), label, detectIconContour(bitmap))
    }.getOrElse { PreviewItem(null, key, DEFAULT_ICON_CONTOUR) }
}

private class PreviewItem(
    val icon: androidx.compose.ui.graphics.ImageBitmap?,
    val label: String,
    val contour: Float,
)

private val previewBitmapPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
    isFilterBitmap = true
}

private fun DrawScope.drawWheelPreview(
    items: List<PreviewItem>,
    radiusDp: Float,
    iconDp: Float,
    angle: Float,
    progress: Float,
    leftEnabled: Boolean,
    rightEnabled: Boolean,
    emptyHint: TextLayoutResult,
    offHint: TextLayoutResult,
    moreLabel: TextLayoutResult,
) {
    val w = size.width
    val h = size.height
    val radiusPx = CornerRadius(16.dp.toPx(), 16.dp.toPx())

    // 画布本体：与面板同一块材质，只加一圈发丝线，避免预览区域「飘」在页面里
    drawRoundRect(color = UiTokens.Surface, cornerRadius = radiusPx)
    drawRoundRect(color = UiTokens.Divider, cornerRadius = radiusPx, style = Stroke(1.dp.toPx()))

    if (items.isEmpty()) {
        drawText(
            emptyHint,
            topLeft = Offset((w - emptyHint.size.width) / 2f, (h - emptyHint.size.height) / 2f),
        )
        return
    }
    if (!leftEnabled && !rightEnabled) {
        drawText(
            offHint,
            topLeft = Offset((w - offHint.size.width) / 2f, (h - offHint.size.height) / 2f),
        )
        return
    }

    val pad = 16.dp.toPx()
    // 图标 / 半径的真实比例：预览只做等比缩放，不改两者的相对大小
    val iconRatio = (iconDp / radiusDp.coerceAtLeast(1f)).coerceIn(0.08f, 0.8f)
    // 槽位数（含「•••」）与张角对应的弧长。图标沿弧等分，槽位多时必须压缩图标，
    // 否则相邻图标相互压叠 —— 与 WheelOverlay 的 iconDiameter 是同一套约束
    val total = items.size + 1
    val arcLen = { r: Float -> r * Math.toRadians(angle.toDouble()).toFloat() }
    val fitIcon = { r: Float ->
        (arcLen(r) / total * 0.92f).coerceAtLeast(5.dp.toPx())
    }

    if (leftEnabled && rightEnabled) {
        // 左右两角各占半幅。半径必须让「弧上最靠中线的那个图标」的外沿也停在中线以内：
        // 水平最大投影 = r·sin(张角)（超过 90° 的部分不再增加水平投影），
        // 加上图标半径 r·iconRatio/2 后不得超过半幅，两角才不会在中线处互相挤压。
        val half = w / 2f
        val gap = 6.dp.toPx()
        val sinA = sin(Math.toRadians(min(angle, 90f).toDouble())).toFloat().coerceAtLeast(0.35f)
        val byWidth = (half - gap - pad) / (sinA + iconRatio / 2f)
        val byHeight = (h - pad * 2f) / (1f + iconRatio / 2f)
        // 上限还要卡真实半径：预览不该把轮盘放大成与真机手感不符的样子
        val rMax = min(min(byWidth, byHeight), radiusDp * density) * 0.98f
        val iconPx = min(rMax * iconRatio, fitIcon(rMax))
            .coerceIn(5.dp.toPx(), 26.dp.toPx())

        clipRect(left = 0f, top = 0f, right = half, bottom = h) {
            drawWheelAt(
                items = items,
                angle = angle,
                pivot = Offset(pad, h - pad),
                radiusPx = rMax,
                iconPx = iconPx,
                fromLeft = true,
                progress = progress,
                moreLabel = moreLabel,
            )
        }
        clipRect(left = half, top = 0f, right = w, bottom = h) {
            drawWheelAt(
                items = items,
                angle = angle,
                pivot = Offset(w - pad, h - pad),
                radiusPx = rMax,
                iconPx = iconPx,
                fromLeft = false,
                progress = progress,
                moreLabel = moreLabel,
            )
        }
        drawLine(
            color = UiTokens.DividerSoft,
            start = Offset(half, pad * 0.6f),
            end = Offset(half, h - pad * 0.6f),
            strokeWidth = 1.dp.toPx(),
        )
    } else {
        // 单角：圆心居中贴底、半径取画布能容纳的最大值 —— 放大居中演示
        // 张角 >90° 后弧线末段朝画布下方扫出，最大下沉量 = sin(末格角度 − 90°)。
        // 旧算法只按 h*0.62 取半径，没算这段下沉：默认 90° 看不出问题，
        // 张角调到 150° 时末格图标画到画布外约 72dp，直接压住下方说明文字与下一个滑杆行。
        val maxT = (items.size + 0.5f) / total
        val drop = sin(Math.toRadians((angle * maxT - 90f).toDouble())).toFloat().coerceAtLeast(0f)
        val byHeight = (h - pad * 2f) / (1f + drop)
        val rMax = min(min(w * 0.36f, byHeight), radiusDp * density) * 0.98f
        val iconPx = min(rMax * iconRatio, fitIcon(rMax))
            .coerceIn(6.dp.toPx(), 34.dp.toPx())
        drawWheelAt(
            items = items,
            angle = angle,
            // 圆心同步上抬「下沉量」，弧线末段才落在画布内
            pivot = Offset(w / 2f, h - pad - iconPx * 0.35f - rMax * drop),
            radiusPx = rMax,
            iconPx = iconPx,
            fromLeft = leftEnabled,
            progress = progress,
            moreLabel = moreLabel,
        )
    }
}

/**
 * 单角轮盘：圆心、半径、镜像方向由调用方给，内部只负责按张角摆放并播放入场进度。
 * 角度与 [WheelOverlay.layoutSlots] 一致：起点 270°，左下 +sweep / 右下 −sweep。
 */
private fun DrawScope.drawWheelAt(
    items: List<PreviewItem>,
    angle: Float,
    pivot: Offset,
    radiusPx: Float,
    iconPx: Float,
    fromLeft: Boolean,
    progress: Float,
    moreLabel: TextLayoutResult,
) {
    val total = items.size + 1   // +1 = 「•••」槽位
    val startDeg = 270f
    val dir = if (fromLeft) 1f else -1f
    val endDeg = startDeg + dir * angle
    val ease = progress.coerceIn(0f, 1f)

    // 弧线：从「•••」到最后一个图标，随进度顺势画出
    drawArc(
        color = UiTokens.Primary.copy(alpha = 0.32f * ease),
        startAngle = startDeg + dir * angle * (0.5f / total),
        sweepAngle = dir * angle * (1f - 1f / total) * ease,
        useCenter = false,
        topLeft = Offset(pivot.x - radiusPx, pivot.y - radiusPx),
        size = Size(radiusPx * 2f, radiusPx * 2f),
        style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round),
    )

    /** 第 [index] 个槽位的出现进度：按槽位错峰，最后一格在 ease 中段就已就位 */
    fun slotAppear(index: Int): Float =
        (ease * (total + 1.2f) - index.toFloat()).coerceIn(0f, 1f)

    // 「•••」圆钮
    run {
        val appear = slotAppear(0)
        if (appear <= 0f) return@run
        val rad = Math.toRadians((startDeg + dir * angle * (0.5f / total)).toDouble())
        val dist = radiusPx * (0.55f + 0.45f * appear)
        val cx = pivot.x + dist * cos(rad).toFloat()
        val cy = pivot.y + dist * sin(rad).toFloat()
        val d = iconPx * 0.92f
        drawCircle(
            color = UiTokens.SurfaceElevated.copy(alpha = appear),
            radius = d / 2f,
            center = Offset(cx, cy),
        )
        drawCircle(
            color = UiTokens.Primary.copy(alpha = 0.45f * appear),
            radius = d / 2f,
            center = Offset(cx, cy),
            style = Stroke(1.dp.toPx()),
        )
        if (appear > 0.5f) {
            drawText(
                moreLabel,
                topLeft = Offset(cx - moreLabel.size.width / 2f, cy - moreLabel.size.height / 2f),
            )
        }
    }

    // 图标：按真实等分角度摆放，圆角取图标自身轮廓
    items.forEachIndexed { index, item ->
        val appear = slotAppear(index + 1)
        if (appear <= 0f) return@forEachIndexed
        val t = (index + 1.5f) / total
        val deg = startDeg + dir * angle * t
        val rad = Math.toRadians(deg.toDouble())
        // 从圆心（55% 半径）向外就位，配合透明度形成「绽放」
        val dist = radiusPx * (0.55f + 0.45f * appear)
        val cx = pivot.x + dist * cos(rad).toFloat()
        val cy = pivot.y + dist * sin(rad).toFloat()
        val d = iconPx
        val left = cx - d / 2f
        val top = cy - d / 2f
        val radiusRatio = item.contour.coerceIn(0f, 0.5f)

        // 底座柔影：让图标像「放」在画布上，而不是印上去
        drawCircle(
            color = UiTokens.ShadowDark.copy(alpha = 0.28f * appear),
            radius = d * 0.54f,
            center = Offset(cx, cy + d * 0.06f),
        )

        val bitmap = item.icon?.asAndroidBitmap()
        if (bitmap != null) {
            drawIntoCanvas { canvas ->
                val n = canvas.nativeCanvas
                val path = Path().apply {
                    addRoundRect(
                        androidx.compose.ui.geometry.RoundRect(
                            left,
                            top,
                            left + d,
                            top + d,
                            CornerRadius(d * radiusRatio, d * radiusRatio),
                        ),
                    )
                }
                n.save()
                n.clipPath(path.asAndroidPath())
                previewBitmapPaint.alpha = (255 * appear).toInt().coerceIn(0, 255)
                n.drawBitmap(
                    bitmap,
                    null,
                    android.graphics.RectF(left, top, left + d, top + d),
                    previewBitmapPaint,
                )
                n.restore()
                previewBitmapPaint.alpha = 255
            }
        } else {
            drawRoundRect(
                color = UiTokens.PrimarySoft.copy(alpha = appear),
                topLeft = Offset(left, top),
                size = Size(d, d),
                cornerRadius = CornerRadius(d * radiusRatio, d * radiusRatio),
            )
            drawText(
                moreLabel,
                topLeft = Offset(cx - moreLabel.size.width / 2f, cy - moreLabel.size.height / 2f),
            )
        }
    }
}
