package com.pilot.wheel

import android.graphics.BlurMaskFilter
import android.graphics.Paint as AndroidPaint
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.Spring
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import android.provider.Settings as AndroidSettings
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset

/**
 * 新拟物派（Neumorphism）设计 token —— 来源：RedSkill stylekit-generator / StyleKit「新拟物派」。
 * 核心纪律：
 *  - 同色系：元素与背景同用 #E0E5EC，靠双重阴影区分层级
 *  - 双重阴影：左上白高光 #FFFFFF（-X/-Y），右下暗影 #B8BCC2（+X/+Y），光源固定左上
 *  - 凸起=可交互，凹陷=输入区/已激活；按下时凸→凹（Extrude to Intrude），禁止位移
 *  - 过渡 300ms ease-in-out（Smooth Molding）；圆角 12-24dp
 *  - 禁：纯黑/纯白背景、渐变、粗边框、硬边缘阴影、直角
 */
object UiTokens {
    // 强调紫（StyleKit accent[0]）：全应用唯一交互色
    val Primary = Color(0xFF2F6BFF)
    val PrimaryOn = Color.White
    val PrimarySoft = Color(0xFFD8E1FF)

    // 取自参考图的色彩参考：基底 #E6E9EF / 暗影 #C3C7D0 / 亮影 #FFFFFF / 正文 #253047
    val Canvas = Color(0xFFE6E9EF)
    val Surface = Color(0xFFE6E9EF)      // 同色系：表面 = 背景
    val DialogSurface = Color(0xFFE6E9EF)
    val SectionBg = Color(0xFFE6E9EF)
    /** 浮层：比画布略亮一档，配合柔影从底板「浮起来」 */
    val SurfaceElevated = Color(0xFFEEF1F6)
    val Field = Color(0xFFE6E9EF)        // 输入区同色，靠凹陷阴影区分
    val FieldHint = Color(0xFF8A93A3)
    val Ink = Color(0xFF253047)
    val InkSub = Color(0xFF566072)
    val Divider = Color(0xFFD3D8E2)

    // 双色阴影
    val ShadowDark = Color(0xFFC3C7D0)
    val ShadowLight = Color.White

    val RadiusPanel = 24.dp
    val RadiusCard = 18.dp
    val RadiusRow = 14.dp
    val RadiusField = 12.dp
    val RadiusIcon = 10.dp

    val SearchHeight = 44.dp
    val GridIcon = 44.dp
}

/**
 * 动效 token（单源）：新拟物「Smooth Molding」要求全部过渡 300ms ease-in-out；
 * 弹簧保留用于面板开合等位移场景（Apple sheet 0.8/0.3s）。
 */
object GlassMotion {
    /** Apple sheet/抽屉官方弹簧：damping 0.8，response 0.3s */
    fun sheetOpen() = spring<Float>(dampingRatio = 0.8f, stiffness = 438f)

    /** 开合带 5-10% 过冲的弹簧 */
    fun overshoot() = spring<Float>(dampingRatio = 0.72f, stiffness = 380f)

    /** 页面/指示器滑动：接近临界，轻微跟随感 */
    fun slide() = spring<Float>(dampingRatio = 0.9f, stiffness = Spring.StiffnessMedium)

    /** 软塑料柔韧过渡：300ms ease-in-out（新拟物统一节奏） */
    fun molding() = tween<Float>(300, easing = EaseInOut)

    /** 颜色版柔韧过渡（图标/文字选中态） */
    fun moldingColor() = tween<Color>(300, easing = EaseInOut)

    /** 出场时长：进慢出快 */
    const val EXIT_MS = 130

    /** 级联进场：每项错峰延迟 */
    const val STAGGER_MS = 40
}

// ---------------------------------------------------------------------------
// 新拟物绘制核心：同色本体 + 双重阴影（外凸/内凹），全部经 nativeCanvas + BlurMaskFilter
// ---------------------------------------------------------------------------

/** 本体填充（凸/凹共用）；浮层传更亮的 SurfaceElevated */
private fun DrawScope.neuBody(outline: Outline, body: Color) {
    when (outline) {
        is Outline.Rounded -> drawRoundRect(body, cornerRadius = outline.roundRect.topLeftCornerRadius)
        is Outline.Generic -> drawPath(outline.path, body)
        else -> drawRect(body)
    }
}

private fun blurredPaint(color: Color, blur: Float): AndroidPaint =
    AndroidPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color.toArgb()
        if (blur > 0.5f) maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
    }

/**
 * 新拟物阴影绘制。
 * @param morph 0=全外凸，1=全内凹；中间值用于按下过渡（凸起阴影缩小、内凹渐显）
 * @param insetScale 内凹深度缩放（输入框 focus 时从 1 降到 ~0.35，通道开放感）
 */
private fun DrawScope.drawNeu(
    shape: Shape,
    morph: Float,
    blur: Float,
    dist: Float,
    insetScale: Float = 1f,
    body: Color = UiTokens.Surface,
) {
    val outline = shape.createOutline(size, layoutDirection, this)
    val radius = (outline as? Outline.Rounded)?.roundRect?.topLeftCornerRadius?.x ?: 12f

    // 1) 外凸阴影：右下暗 + 左上亮；按下时阴影先缩小（Hover Shadowing）再消退
    val raised = 1f - morph
    if (raised > 0.02f) {
        val b = blur * (1f - 0.45f * morph)
        val d = dist * (1f - 0.45f * morph)
        drawIntoCanvas { c ->
            val n = c.nativeCanvas
            val dark = blurredPaint(UiTokens.ShadowDark, b)
            val light = blurredPaint(UiTokens.ShadowLight, b)
            // 不打满：全强度会显得脏，新拟态要的是「一层薄薄的光」
            dark.alpha = (232 * raised).toInt()
            light.alpha = (246 * raised).toInt()
            n.drawRoundRect(d, d, size.width + d, size.height + d, radius, radius, dark)
            n.drawRoundRect(-d, -d, size.width - d, size.height - d, radius, radius, light)
        }
    }

    // 2) 本体同色填充
    neuBody(outline, body)

    // 3) 内凹阴影：clip 进本体，暗影偏左上、亮影偏右下
    if (morph > 0.02f && insetScale > 0.02f) {
        val b = blur * 0.6f * insetScale
        val d = dist * 0.6f * insetScale
        val strokeW = b * 1.6f
        drawIntoCanvas { c ->
            val n = c.nativeCanvas
            val bodyPath = when (outline) {
                is Outline.Rounded -> Path().apply {
                    addRoundRect(
                        androidx.compose.ui.geometry.RoundRect(
                            0f, 0f, size.width, size.height,
                            outline.roundRect.topLeftCornerRadius,
                        ),
                    )
                }
                is Outline.Generic -> Path().apply { addPath(outline.path) }
                else -> Path().apply { addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height)) }
            }
            n.save()
            n.clipPath(bodyPath.asAndroidPath())
            val dark = blurredPaint(UiTokens.ShadowDark, b).apply {
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = strokeW
            }
            val light = blurredPaint(UiTokens.ShadowLight, b).apply {
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = strokeW
            }
            // 暗影往左上挤入
            n.translate(-d, -d)
            n.drawRoundRect(strokeW / 2, strokeW / 2, size.width - strokeW / 2, size.height - strokeW / 2, radius, radius, dark)
            n.translate(2 * d, 2 * d)
            // 亮影往右下挤入
            n.drawRoundRect(strokeW / 2, strokeW / 2, size.width - strokeW / 2, size.height - strokeW / 2, radius, radius, light)
            n.restore()
        }
    }
}

/** 静态外凸表面（卡片/面板） */
fun Modifier.neuRaised(
    shape: Shape = RoundedCornerShape(UiTokens.RadiusCard),
    blur: Float = 16f,
    dist: Float = 8f,
    body: Color = UiTokens.Surface,
): Modifier = drawBehind { drawNeu(shape, morph = 0f, blur = blur, dist = dist, body = body) }

/**
 * 焦点描边：参考图第 6 页 —— 聚焦时在控件外圈加一道清晰轮廓 + 柔光，
 * 让「当前位置」被看见。drawBehind 默认不裁剪，所以往外画的这圈不会被切掉。
 */
fun Modifier.focusRing(
    visible: Boolean,
    shape: Shape = RoundedCornerShape(UiTokens.RadiusField),
    width: Float = 2f,
): Modifier = drawBehind {
    if (!visible) return@drawBehind
    val outline = shape.createOutline(size, layoutDirection, this)
    val radius = (outline as? Outline.Rounded)?.roundRect?.topLeftCornerRadius?.x ?: 20f
    val out = width.dp.toPx() + 2f * density
    drawIntoCanvas { c ->
        val n = c.nativeCanvas
        val glow = AndroidPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = UiTokens.Primary.copy(alpha = 0.35f).toArgb()
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 6f * density
            maskFilter = BlurMaskFilter(4f * density, BlurMaskFilter.Blur.NORMAL)
        }
        n.drawRoundRect(-out, -out, size.width + out, size.height + out, radius + out, radius + out, glow)
        val line = AndroidPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = UiTokens.Primary.toArgb()
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = width.dp.toPx()
        }
        n.drawRoundRect(-out, -out, size.width + out, size.height + out, radius + out, radius + out, line)
    }
}

/** 静态内凹表面（输入区/已激活） */
fun Modifier.neuInset(
    shape: Shape = RoundedCornerShape(UiTokens.RadiusField),
    blur: Float = 12f,
    dist: Float = 6f,
    scale: Float = 1f,
    body: Color = UiTokens.Surface,
): Modifier = drawBehind {
    drawNeu(shape, morph = 1f, blur = blur, dist = dist, insetScale = scale, body = body)
}

/**
 * 可按压新拟物表面：默认外凸，按下 300ms ease-in-out 变内凹（Extrude to Intrude），
 * 禁止任何位移/缩放 —— 元素是从背景材质中生长的。
 */
fun Modifier.neuPressable(
    shape: Shape = RoundedCornerShape(UiTokens.RadiusRow),
    enabled: Boolean = true,
    blur: Float = 12f,
    dist: Float = 6f,
    onClick: () -> Unit,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val morph by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = GlassMotion.molding(),
        label = "neu-morph",
    )
    this
        .drawBehind { drawNeu(shape, morph = morph, blur = blur, dist = dist) }
        .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
}

/**
 * 级联进场：首次组合时淡入 + 上滑弹入，index 越大错峰越晚。
 * 无障碍：系统动画时长缩放为 0（减弱动效）时直接显示。
 */
fun Modifier.cascadeEnter(index: Int): Modifier = composed {
    val context = LocalContext.current
    val motionOn = remember {
        runCatching {
            AndroidSettings.Global.getFloat(
                context.contentResolver,
                AndroidSettings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) > 0f
        }.getOrDefault(true)
    }
    var shown by remember { mutableStateOf(!motionOn) }
    LaunchedEffect(Unit) {
        if (!shown) {
            kotlinx.coroutines.delay(index.toLong() * GlassMotion.STAGGER_MS)
            shown = true
        }
    }
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = GlassMotion.sheetOpen(),
        label = "cascade-enter",
    )
    this.graphicsLayer {
        alpha = progress
        translationY = (1f - progress) * 40f
    }
}

// ---------------------------------------------------------------------------
// 兼容旧调用点的表面组件（内部全部新拟物化）
// ---------------------------------------------------------------------------

/** 渐变画布 → 新拟物统一浅灰画布（禁止渐变，禁光斑） */
@Composable
fun GlassCanvas(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier.background(UiTokens.Canvas),
        content = content,
    )
}

/** 玻璃面板 → 新拟物外凸卡片 */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(UiTokens.RadiusCard),
    elevation: androidx.compose.ui.unit.Dp = 10.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier = modifier.neuRaised(shape = shape), content = content)
}

/** 玻璃行 → 静态外凸行（不可按压的场景） */
fun Modifier.glassRow(shape: Shape = RoundedCornerShape(UiTokens.RadiusRow)): Modifier =
    neuRaised(shape = shape, blur = 10f, dist = 5f)

/** 统一主题：新拟物配色 + 药丸按钮 + 负字距标题。 */
@Composable
fun WheelTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = UiTokens.Primary,
            onPrimary = UiTokens.PrimaryOn,
            primaryContainer = UiTokens.PrimarySoft,
            onPrimaryContainer = UiTokens.Primary,
            surface = UiTokens.Surface,
            onSurface = UiTokens.Ink,
            surfaceVariant = UiTokens.Field,
            onSurfaceVariant = UiTokens.InkSub,
            background = UiTokens.Canvas,
            onBackground = UiTokens.Ink,
            outline = UiTokens.Divider,
        ),
        shapes = androidx.compose.material3.Shapes(
            extraSmall = RoundedCornerShape(UiTokens.RadiusIcon),
            small = RoundedCornerShape(50),
            medium = RoundedCornerShape(UiTokens.RadiusField),
            large = RoundedCornerShape(UiTokens.RadiusCard),
            extraLarge = RoundedCornerShape(UiTokens.RadiusPanel),
        ),
        typography = MaterialTheme.typography.copy(
            headlineLarge = MaterialTheme.typography.headlineLarge.copy(letterSpacing = (-0.28).sp),
            headlineSmall = MaterialTheme.typography.headlineSmall.copy(letterSpacing = (-0.22).sp),
            titleLarge = MaterialTheme.typography.titleLarge.copy(letterSpacing = (-0.2).sp),
            titleMedium = MaterialTheme.typography.titleMedium.copy(letterSpacing = (-0.12).sp),
        ),
        content = content,
    )
}

/**
 * 共享搜索框：新拟物凹陷输入区，focus 时内阴影收缩（通道开放感）。
 */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String = "搜索应用",
) {
    var focused by remember { mutableStateOf(false) }
    val depth by animateFloatAsState(
        targetValue = if (focused) 0.35f else 1f,
        animationSpec = GlassMotion.molding(),
        label = "neu-input",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(UiTokens.SearchHeight)
            .neuInset(RoundedCornerShape(50), blur = 12f, dist = 6f, scale = depth)
            .focusRing(focused, RoundedCornerShape(50))
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = UiTokens.FieldHint,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text(
                        hint,
                        style = MaterialTheme.typography.bodyMedium,
                        color = UiTokens.FieldHint,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focused = it.hasFocus },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = UiTokens.Ink),
                )
            }
        }
    }
}

/** 分组小标题 */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = UiTokens.InkSub,
    fontWeight: FontWeight = FontWeight.SemiBold,
) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall.copy(fontSize = 13.sp),
        fontWeight = fontWeight,
        color = color,
        modifier = modifier,
    )
}

/**
 * 兜底轮廓（27% 圆角）。真实形状由 [detectIconContour] 从图标像素量出来，
 * 取不到时用这个值，保证底座与图标始终是同一个形状。
 */
val IconShape = RoundedCornerShape(percent = 27)

/**
 * 新拟物图标底座：凸起圆形软垫，图标浮在其上。
 * 图标本身不直接贴在画布上，而是从材质里「长」出来的 —— 这是拟物图标的核心。
 * 底座外径由图标尺寸自适应推导（iconSize + pad×2），保证任何图标都能被均匀包裹，
 * 不同场景的底座只改图标大小、不再各写一套固定外径。
 * 形状由调用方传入（通常来自 [iconShape]），底座与图标轮廓严格一致。
 * pressable=true 时整个底座可按压并凹陷（Extrude to Intrude）。
 */
@Composable
fun NeuIconBadge(
    iconSize: Dp,
    modifier: Modifier = Modifier,
    pad: Dp = 5.dp,
    shape: Shape = IconShape,
    /** 传入图标实测轮廓时，底座轮廓按「图标圆角 + 内缩」算，边框等宽；为空则用 shape */
    contour: Float? = null,
    pressable: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val plate = if (contour != null) iconPlateShape(contour, iconSize, pad) else shape
    val base = if (pressable && onClick != null) {
        Modifier.neuPressable(shape = plate, blur = 10f, dist = 5f, onClick = onClick)
    } else {
        Modifier.neuRaised(shape = plate, blur = 10f, dist = 5f)
    }
    Box(
        modifier = modifier
            .size(iconSize + pad * 2)
            .then(base),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/**
 * 新拟物开关：关=外凸灰色轨道，开=内凹紫色轨道（凹陷=已激活），旋钮始终凸起。
 * 旋钮用弹簧滑过去，替代 M3 Switch 的平面样式。
 */
@Composable
fun NeuSwitch(
    checked: Boolean,
    onCheckedChange: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val knobX by animateDpAsState(
        targetValue = if (checked) 26.dp else 2.dp,
        animationSpec = spring<Dp>(dampingRatio = 0.9f, stiffness = Spring.StiffnessMedium),
        label = "neu-switch-knob",
    )
    Box(
        modifier = modifier
            .width(52.dp)
            .height(30.dp)
            .clip(RoundedCornerShape(50))
            .then(
                if (checked) {
                    // 开启：填充强调色 + 顶部一层微亮、底部压暗，做出「厚」的立体感
                    Modifier
                        .background(UiTokens.Primary)
                        .background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                0f to Color.White.copy(alpha = 0.18f),
                                0.55f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.14f),
                            ),
                        )
                } else {
                    // 关闭：做成浅凹槽（不是凸起），开关的「槽」感才对
                    Modifier.neuInset(
                        shape = RoundedCornerShape(50),
                        blur = 8f,
                        dist = 3f,
                        body = Color(0xFFDDE3EC),
                    )
                },
            )
            .then(
                if (onCheckedChange != null) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onCheckedChange() }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        // 参考图：勾在轨道左侧（白色），旋钮只负责位置
        if (checked) {
            Text(
                "✓",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 9.dp),
            )
        }
        Box(
            modifier = Modifier
                .offset(x = knobX)
                .size(24.dp)
                // 旋钮：纯白 + 明确投影，对比感落在旋钮与槽之间
                .shadow(
                    elevation = 4.dp,
                    shape = CircleShape,
                    ambientColor = Color(0x33000000),
                    spotColor = Color(0x40000000),
                )
                .background(Color.White, CircleShape),
        )
    }
}

/**
 * 新拟物按钮：与背景同一块材料，靠凸起/凹陷表达可交互，不用填充色块。
 * 主操作只把文字染成强调紫，避免整块彩色破坏材质关系。
 */
@Composable
fun NeuButton(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    if (primary && enabled) {
        // 主要动作：实心强调色 + 同色柔光（参考图第 4 步）
        Box(
            modifier = modifier
                .height(44.dp)
                .shadow(
                    elevation = 10.dp,
                    shape = shape,
                    ambientColor = UiTokens.Primary.copy(alpha = 0.45f),
                    spotColor = UiTokens.Primary.copy(alpha = 0.45f),
                )
                .background(UiTokens.Primary, shape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                )
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text,
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    } else {
        Box(
            modifier = modifier
                .height(44.dp)
                .neuPressable(
                    shape = shape,
                    enabled = enabled,
                    blur = 9f,
                    dist = 4f,
                    onClick = onClick,
                )
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text,
                color = if (enabled) UiTokens.Ink else UiTokens.FieldHint,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** 新拟物分段选择：选中=凹陷（已激活），未选=凸起 */
@Composable
fun NeuChip(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = modifier
            .height(32.dp)
            .then(
                if (selected) {
                    Modifier.neuInset(shape, blur = 8f, dist = 4f)
                } else {
                    Modifier.neuRaised(shape, blur = 8f, dist = 4f)
                },
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) UiTokens.Primary else UiTokens.InkSub,
        )
    }
}

/**
 * 纯新拟态滑块：凹槽轨道 + 蓝色已选段 + 白色浮起旋钮。
 * 不用 Material Slider，避免出现「细线轨道 + 圆片」的平面感。
 */
@Composable
fun NeuSlider(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    onValueChange: (Float) -> Unit,
) {
    var trackW by remember { mutableFloatStateOf(0f) }
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    val knobR = 12.dp
    val knobRpx = with(androidx.compose.ui.platform.LocalDensity.current) { knobR.toPx() }

    fun valueAt(x: Float): Float {
        val usable = (trackW - knobRpx * 2).coerceAtLeast(1f)
        val f = ((x - knobRpx) / usable).coerceIn(0f, 1f)
        return valueRange.start + f * span
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .onSizeChanged { trackW = it.width.toFloat() }
            .pointerInput(valueRange) {
                detectTapGestures { onValueChange(valueAt(it.x)) }
            }
            .pointerInput(valueRange) {
                detectDragGestures(
                    onDragStart = { onValueChange(valueAt(it.x)) },
                    onDrag = { change, _ ->
                        change.consume()
                        onValueChange(valueAt(change.position.x))
                    },
                )
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        // 槽
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
                .neuInset(RoundedCornerShape(50), blur = 8f, dist = 4f),
        )
        // 已选段（比槽略窄，视觉上「流在槽里」）
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(10.dp)
                .clip(RoundedCornerShape(50))
                .background(UiTokens.Primary.copy(alpha = 0.9f)),
        )
        // 旋钮
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        (knobRpx + fraction * (trackW - knobRpx * 2) - knobRpx).toInt(),
                        0,
                    )
                }
                .size(knobR * 2)
                .shadow(
                    elevation = 4.dp,
                    shape = CircleShape,
                    ambientColor = Color(0x33000000),
                    spotColor = Color(0x40000000),
                )
                .background(Color.White, CircleShape),
        )
    }
}
