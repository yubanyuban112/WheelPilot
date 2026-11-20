package com.pilot.wheel

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// 小窗页：服务状态 / 轮盘现状（含真实比例预览）/ 用法演示。
// 3.19.0 起内容铺满整屏（此前轮盘卡下面只有一张演示卡，1080p 机型底部会空出约三分之一屏）；
// 3.19.1 移除「快捷动作」「最近使用」两张卡（需求），页面因此缩短约两张卡的高度。
// 极小屏或大字体下允许滚动，正常机型一屏到底。

@Composable
internal fun HomeTab(
    accessible: Boolean,
    selectedCount: Int,
    onOpenPrivilege: () -> Unit,
    onOpenWheelSettings: () -> Unit,
) {
    val context = LocalContext.current
    // 37 号改动（S5/2-1）：serverState 是 StateFlow，此前一次性读值不订阅，
    // 特权服务连接成功后「特权服务」状态行不刷新（需切页重建）。改为 collectAsState 订阅。
    val serverInfo by priv.kit.core.Privilege.serverState.collectAsState()
    val privilegeReady = serverInfo != null
    val radius = remember { Prefs.radiusDp(context) }
    val icon = remember { Prefs.iconDp(context) }
    val angle = remember { Prefs.angle(context) }
    val hotW = remember { Prefs.hotWidth(context) }
    val hotH = remember { Prefs.hotHeight(context) }
    val left = remember { Prefs.leftEnabled(context) }
    val right = remember { Prefs.rightEnabled(context) }
    // 主页轮盘预览：可关闭（关闭状态持久化）
    var showPreview by remember { mutableStateOf(Prefs.showHomePreview(context)) }
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrDefault("")
    }
    val launchMode = remember {
        when (Prefs.launchMode(context)) {
            Prefs.LAUNCH_AUTO -> "自动"
            Prefs.LAUNCH_VENDOR -> "厂商"
            Prefs.LAUNCH_FREEFORM -> "自由窗"
            else -> "全屏"
        }
    }

    // 内容按一屏设计；极小屏才会滚动，正常机型不会出现滚动条
    val homeScroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(homeScroll)
            .padding(horizontal = UiSpace.l),
    ) {
        Spacer(Modifier.height(UiSpace.s))

        // 标题 + 轮盘占用：右上角直接给出「已放几个」
        // 63 号：标题随滚动做反向微位移（视差），系数 0.10、上限 20dp，只动绘制层
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.scrollParallax({ homeScroll.value }, factor = 0.10f, maxShift = 20.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "小窗",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = UiTokens.Ink,
                )
                Text(
                    "v$versionName · 角落轮盘启动器",
                    style = MaterialTheme.typography.bodySmall,
                    color = UiTokens.InkSub,
                    modifier = Modifier.padding(top = UiSpace.xxs),
                )
            }
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(UiTokens.RadiusPill))
                    .background(UiTokens.PrimarySoft)
                    .padding(horizontal = UiSpace.m, vertical = UiSpace.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "$selectedCount",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = UiTokens.Primary,
                )
                Text(
                    " / ${Prefs.slots(context)}",
                    style = MaterialTheme.typography.labelMedium,
                    // UI UX Pro Max · color-contrast：12sp 小字用 Primary.copy(0.72) 对比约 2.4:1，
                    // 改 InkSub（对比达标，仍保持次级层级）。主数字是大文本，Primary 保留。
                    color = UiTokens.InkSub,
                )
            }
        }

        Spacer(Modifier.height(UiSpace.s))
        // 服务：两行说清「能不能用」，没开的那一行直接给按钮
        Panel {
            StatusRow(
                title = "无障碍服务",
                ok = accessible,
                onAction = if (accessible) null else {
                    {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS),
                            )
                        }
                        Unit
                    }
                },
                actionText = "去开启",
            )
            SoftDivider()
            StatusRow(
                title = "特权服务",
                ok = privilegeReady,
                onAction = if (privilegeReady) null else onOpenPrivilege,
                actionText = "去连接",
                offText = "未连接",
            )
            if (!accessible) {
                Spacer(Modifier.height(UiSpace.xxs))
                Text(
                    "无障碍开关是灰的？应用信息 → 右上角 ⋮ → 允许受限制的设置",
                    style = MaterialTheme.typography.labelSmall,
                    color = UiTokens.InkSub,
                )
            }
        }

        // 轮盘现状：四个数值一眼看完；点整卡直接跳转设置页「轮盘外观」
        Panel(onClick = onOpenWheelSettings, contentDescription = "轮盘设置，点按调整轮盘参数") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "轮盘",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = UiTokens.Ink,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (left && right) "左下 + 右下" else if (left) "仅左下" else if (right) "仅右下" else "双侧关闭",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (left || right) UiTokens.Primary else UiTokens.InkSub,
                )
            }
            Spacer(Modifier.height(UiSpace.xs))
            Row(modifier = Modifier.fillMaxWidth()) {
                Stat("半径", "${radius.toInt()}", "dp", Modifier.weight(1f))
                Stat("图标", "${icon.toInt()}", "dp", Modifier.weight(1f))
                Stat("张角", "${angle.toInt()}", "°", Modifier.weight(1f))
                Stat("启动", launchMode, "", Modifier.weight(1f))
            }
            // 3.19.0：数值下面直接铺一张真实比例的轮盘预览。
            // 之前这一页只有四个数字，参数对不对要自己去屏幕上点「预览轮盘」才知道；
            // 预览与轮盘同源（圆心贴角、真实张角、真实图标），改完设置回来一眼能看出变化。
            Spacer(Modifier.height(UiSpace.xs))
            if (showPreview) {
                WheelAppearancePreview(
                    radiusDp = radius,
                    iconDp = icon,
                    angle = angle,
                    slots = Prefs.slots(context),
                    // 3.19.2：与真实角落保持一致 —— 只开一边时放大居中演示
                    leftEnabled = left,
                    rightEnabled = right,
                    title = "轮盘预览",
                    note = null,
                    horizontalPadding = 0.dp,
                    canvasHeight = 120.dp,
                )
                // 主页轮盘预览：加一个关闭选项，关闭后只留「显示预览」入口
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Text(
                        "关闭预览",
                        style = MaterialTheme.typography.labelSmall,
                        color = UiTokens.InkSub,
                        modifier = Modifier
                            .clickable {
                                showPreview = false
                                Prefs.setShowHomePreview(context, false)
                            }
                            .padding(vertical = UiSpace.xxs),
                    )
                }
            } else {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Text(
                        "显示预览",
                        style = MaterialTheme.typography.labelSmall,
                        color = UiTokens.Primary,
                        modifier = Modifier
                            .clickable {
                                showPreview = true
                                Prefs.setShowHomePreview(context, true)
                            }
                            .padding(vertical = UiSpace.xxs),
                    )
                }
            }
            Spacer(Modifier.height(UiSpace.xxs))
            Text(
                "触发区 ${hotW.toInt()} × ${hotH.toInt()} dp    ·    点此调整轮盘 ›",
                style = MaterialTheme.typography.labelSmall,
                color = UiTokens.Primary,
            )
        }

        // 3.19.1：小窗页的「快捷动作」「最近使用」两张卡已按需求移除。
        // 能力本身保留：截屏/手电筒/锁屏/扫码走「•••」更多面板，最近使用由更多面板维护，
        // 快捷工具仍在「应用」页的「快捷工具」子页。此处不再重复展示。
        // 用法：循环动态演示（Compose 原生逐帧，不依赖 GIF 资源）
        UsageDemo()

        Spacer(Modifier.height(UiSpace.s))
        Row(horizontalArrangement = Arrangement.spacedBy(UiSpace.s)) {
            NeuButton("预览轮盘", modifier = Modifier.weight(1f), primary = true) {
                PilotService.instance?.previewWheel()
            }
            NeuButton("显示热区", modifier = Modifier.weight(1f)) {
                PilotService.instance?.highlightHotspots()
            }
        }
        Spacer(Modifier.height(UiSpace.l))
    }
}

/** 统一卡片外壳：外凸，内部平铺；onClick 非空时整卡可点（如轮盘卡跳设置） */
@Composable
private fun Panel(
    onClick: (() -> Unit)? = null,
    contentDescription: String? = null,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = UiSpace.s)
            .neuRaised(RoundedCornerShape(UiTokens.RadiusCard), blur = 16f, dist = 7f)
            .then(
                if (onClick != null) {
                    Modifier
                        .semantics(mergeDescendants = true) {
                            role = Role.Button
                            if (contentDescription != null) this.contentDescription = contentDescription
                        }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onClick,
                        )
                } else Modifier,
            ),
    ) {
        Column(modifier = Modifier.padding(horizontal = UiSpace.l, vertical = UiSpace.s)) { content() }
    }
}

/**
 * 服务状态行：一行说清「名字 + 结果」，未就绪时右侧直接给一个可点的动作，
 * 不用再往下找按钮。
 */@Composable
private fun StatusRow(
    title: String,
    ok: Boolean,
    onAction: (() -> Unit)?,
    actionText: String,
    offText: String = "未开启",
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = UiSpace.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (ok) UiTokens.Primary else UiTokens.FieldHint),
        )
        Spacer(Modifier.width(UiSpace.ms))
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = UiTokens.Ink,
            modifier = Modifier.weight(1f),
        )
        if (ok) {
            Text(
                "已连接",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = UiTokens.Primary,
            )
        } else if (onAction != null) {
            Text(
                actionText,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = UiTokens.Primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(UiTokens.RadiusPill))
                    .background(UiTokens.PrimarySoft)
                    // UI UX Pro Max · Accessibility：动作 pill 补 role=Button，TalkBack 识别为按钮
                    .semantics(mergeDescendants = true) { role = Role.Button }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onAction,
                    )
                    // 触控目标 ≥44dp：动作 pill 加足垂直内距（DESIGN.md 触控纪律）
                    .padding(horizontal = UiSpace.xl, vertical = UiSpace.m),
            )
        } else {
            Text(
                offText,
                style = MaterialTheme.typography.labelMedium,
                color = UiTokens.InkSub,
            )
        }
    }
}

/** 单个数值：大数字 + 单位 + 下方小标签，四等分排开 */
@Composable
private fun Stat(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = UiTokens.Ink,
            )
            if (unit.isNotEmpty()) {
                Text(
                    unit,
                    style = MaterialTheme.typography.labelSmall,
                    color = UiTokens.InkSub,
                    modifier = Modifier.padding(start = UiSpace.xxs, bottom = UiSpace.xs),
                )
            }
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = UiTokens.InkSub)
    }
}

/** 单行步骤：编号 + 文案，三行加起来只占一张卡 */
@Composable
private fun StepLine(index: String, text: String, active: Boolean = false) {
    Row(
        modifier = Modifier.padding(vertical = UiSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(UiTokens.IconSm)
                .clip(CircleShape)
                .background(if (active) UiTokens.Primary else UiTokens.FieldHint),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                index,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
        }
        Spacer(Modifier.width(UiSpace.ms))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (active) UiTokens.Ink else UiTokens.InkSub,
        )
    }
}

/**
 * 「怎么用」动态演示（3.18.1 重写）。
 *
 * 3.18.0 那版画的是一张抽象示意图：一段弧 + 三个圆点，和真正的轮盘没有对应关系，
 * 用户看不出「点哪、滑多远、图标怎么出来」。这一版把**真实轮盘的实现逻辑**搬进
 * 一个 112×132dp 的机身里（对应 WheelOverlay 的几何与时间曲线）：
 *  - 圆心贴左下角，与 layoutSlots 一致（cy 距底 = 图标半径 + 8dp 的同一套关系）；
 *  - 图标半径按 eased = 0.55 + 0.45 × 过冲进度 从角落「绽放」，与 onDraw 的 eased 同式；
 *  - 张角 / 半径 / 图标尺寸直接取当前 Prefs：改完设置回到这一页，演示立即变成新参数；
 *  - 弧线串珠、「更多」圆钮、逐项高亮与真实轮盘一一对应。
 * 时间轴 5.6s 一循环：涟漪起手 → 图标逐个绽放 → 高亮扫过 → 淡出；
 * 系统开启「减弱动效」时定格在完成态，不做循环。
 */
@Composable
private fun UsageDemo() {
    val context = LocalContext.current
    val motionOn = rememberMotionEnabled()
    val angle = remember { Prefs.angle(context) }
    val radiusDp = remember { Prefs.radiusDp(context) }
    val iconDp = remember { Prefs.iconDp(context) }
    // 演示几个图标：跟随真实槽位数量（1 个也演示 4 个，否则弧线看不出形）
    val count = remember { Prefs.load(context).size.coerceIn(4, 6) }
    val steps = listOf("从角落上滑", "轮盘沿弧线展开", "滑到图标松手即启动")

    val measurer = rememberTextMeasurer()
    val glyphs = remember {
        DEMO_APPS.map {
            measurer.measure(
                it.first,
                // 37 号改动（T1/m6）：10sp 字面量收进 UiType.badgeGlyph
                UiType.badgeGlyph.copy(color = Color.White),
            )
        }
    }

    val t = if (motionOn) {
        val transition = rememberInfiniteTransition(label = "usage-demo")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(5600, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "usage-t",
        ).value
    } else {
        // 减弱动效：定格在「展开完成 + 第二项高亮」的完整状态
        0.70f
    }

    val activeStep = when {
        t < 0.16f -> 0
        t < 0.64f -> 1
        else -> 2
    }

    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "怎么用",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = UiTokens.Ink,
                modifier = Modifier.weight(1f),
            )
            Text(
                "按当前轮盘参数实时演示",
                style = MaterialTheme.typography.labelSmall,
                color = UiTokens.InkSub,
            )
        }
        Spacer(Modifier.height(UiSpace.s))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(modifier = Modifier.width(112.dp).height(132.dp)) {
                drawWheelDemo(
                    t = t,
                    motionOn = motionOn,
                    angle = angle,
                    radiusDp = radiusDp,
                    iconDp = iconDp,
                    count = count,
                    glyphs = glyphs,
                )
            }
            Spacer(Modifier.width(UiSpace.l))
            Column(modifier = Modifier.weight(1f)) {
                steps.forEachIndexed { index, text ->
                    StepLine("${index + 1}", text, active = index == activeStep)
                }
                Spacer(Modifier.height(UiSpace.xs))
                Text(
                    "点「•••」看全部应用 · 长按图标可移除",
                    style = MaterialTheme.typography.labelSmall,
                    color = UiTokens.InkSub,
                )
            }
        }
    }
}

/**
 * 演示用应用样本：字形 + 品牌色（不加载真实图标，演示不需要 IO）。
 * 37 号改动（T6/m1）：品牌色属演示专用、非主题 token，统一收在本常量表并注明豁免口径；
 * 色值整体加深 8–12%，白字对比从 2.30–2.63 提到 ≥4.5（AA），品牌意象不变。
 */
private val DEMO_APPS = listOf(
    "微" to Color(0xFF0BAA56),
    "付" to Color(0xFF1266DD),
    "抖" to Color(0xFF17171F),
    "B" to Color(0xFFE4608A),
    "Q" to Color(0xFF0C9BD8),
    "书" to Color(0xFFE86E00),
)

/**
 * 演示画布。几何与 WheelOverlay 同源：
 *  - 圆心在左下角内侧（真实轮盘圆心就在角落，cy = 高度 - 图标半径 - 8dp）；
 *  - 起点角 -90°（竖直向上），扫过 angle（与 Prefs.angle 一致）；
 *  - 槽位分布 0.5（•••）/ 1.5 … n+0.5（图标），即「更多」占弧线起点端；
 *  - eased = 0.55 + 0.45 × progress：先在半途出现，再外扩到半径并带过冲。
 */
private fun DrawScope.drawWheelDemo(
    t: Float,
    motionOn: Boolean,
    angle: Float,
    radiusDp: Float,
    iconDp: Float,
    count: Int,
    glyphs: List<TextLayoutResult>,
) {
    val w = size.width
    val h = size.height
    val frameR = 20.dp.toPx()

    // 机身：屏幕底色 + 一圈发丝线
    drawRoundRect(color = UiTokens.SurfaceElevated, cornerRadius = CornerRadius(frameR, frameR))
    drawRoundRect(
        color = UiTokens.Divider,
        cornerRadius = CornerRadius(frameR, frameR),
        style = Stroke(1.dp.toPx()),
    )

    // 半径映射：把 120–300dp 的真实半径压进机身，图标/半径的比例保持不变。
    // 张角 >90° 后末格会向画布下方扫出（与 WheelPreview 单角分支同源），旧版只按
    // min(w,h)*0.60 取半径、圆心贴底，150° 时末格图标下沿越过 132dp 画布约 50dp，
    // 直接压到下方「预览轮盘 / 显示热区」按钮。这里把下沉量计入半径与圆心。
    val pad = 10.dp.toPx()
    val totalSlots = count + 1
    val maxT = (count + 0.5f) / totalSlots
    val drop = sin(Math.toRadians((angle * maxT - 90f).toDouble())).toFloat().coerceAtLeast(0f)
    // easedOf 的 easeOutBack 峰值约 1.05，留 1% 余量
    val factorMax = 1.06f
    val rawTarget = min(w, h) * 0.60f
    val maxIconPx = 26.dp.toPx()
    val byHeight = (h - pad * 2f - maxIconPx * 0.5f) / (1f + drop * factorMax)
    val rTarget = min(rawTarget, byHeight).coerceAtLeast(24.dp.toPx())
    val scale = rTarget / (radiusDp * density).coerceAtLeast(1f)
    val iconSize = (iconDp * density * scale).coerceIn(14.dp.toPx(), maxIconPx)
    val pivot = Offset(pad + iconSize * 0.3f, h - pad - iconSize * 0.5f - rTarget * drop)

    val fade = if (t > 0.90f) (1f - (t - 0.90f) / 0.10f).coerceIn(0f, 1f) else 1f

    // 1) 起手涟漪：从角落散开两圈，示意「上滑」
    if (t < 0.18f) {
        repeat(2) { ring ->
            val p = ((t / 0.18f) - ring * 0.28f).coerceIn(0f, 1f)
            if (p > 0f) {
                drawCircle(
                    color = UiTokens.Primary.copy(alpha = 0.5f * (1f - p) * fade),
                    radius = iconSize * 0.55f + p * rTarget * 0.42f,
                    center = pivot,
                    style = Stroke(2.dp.toPx()),
                )
            }
        }
    }

    val n = count
    val total = n + 1
    // 绽放进度：带轻微错峰，避免 6 个图标同一帧齐刷刷出现（真实轮盘是一起绽放，
    // 演示里错峰 20ms 只是为了让「一个个出来」看得清）
    fun easedOf(i: Int): Pair<Float, Float> {
        if (!motionOn) return 1f to 1f
        val start = 0.10f + i * 0.022f
        val raw = ((t - start) / 0.30f).coerceIn(0f, 1f)
        val x = raw - 1f
        // easeOutBack（c1 = 1.15）：与 OvershootInterpolator(1.15f) 同量级
        val over = 1f + 2.15f * x * x * x + 1.15f * x * x
        return (0.55f + 0.45f * over) to raw
    }

    fun degAt(k: Float): Double = 270.0 + angle * k

    // 2) 弧线串珠：从「更多」位置铺到最后一个图标
    val arc = if (motionOn) ((t - 0.10f) / 0.34f).coerceIn(0f, 1f) else 1f
    if (arc > 0f) {
        val r = rTarget * (0.55f + 0.45f * arc)
        drawArc(
            color = UiTokens.Primary.copy(alpha = 0.30f * fade),
            startAngle = 270f + angle * (0.5f / total),
            sweepAngle = angle * (1f - 1f / total) * arc,
            useCenter = false,
            topLeft = Offset(pivot.x - r, pivot.y - r),
            size = Size(r * 2f, r * 2f),
            style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round),
        )
    }

    // 3)「更多」圆钮：弧线起点端，用强调色 •••
    run {
        val (factor, alpha) = easedOf(-1)
        val rad = Math.toRadians(degAt(0.5f / total))
        val cx = pivot.x + rTarget * factor * cos(rad).toFloat()
        val cy = pivot.y + rTarget * factor * sin(rad).toFloat()
        val d = iconSize * 0.9f
        drawCircle(
            color = UiTokens.Surface.copy(alpha = 0.92f * alpha * fade),
            radius = d / 2f,
            center = Offset(cx, cy),
        )
        drawCircle(
            color = UiTokens.Primary.copy(alpha = 0.55f * alpha * fade),
            radius = d / 2f,
            center = Offset(cx, cy),
            style = Stroke(1.dp.toPx()),
        )
        drawCircle(UiTokens.Primary.copy(alpha = alpha * fade), radius = 1.4.dp.toPx(), center = Offset(cx - d * 0.22f, cy))
        drawCircle(UiTokens.Primary.copy(alpha = alpha * fade), radius = 1.4.dp.toPx(), center = Offset(cx, cy))
        drawCircle(UiTokens.Primary.copy(alpha = alpha * fade), radius = 1.4.dp.toPx(), center = Offset(cx + d * 0.22f, cy))
    }

    // 4) 高亮扫过：0.64 之后逐项点亮，模拟手指滑过
    val highlight = if (motionOn && t >= 0.64f && t < 0.86f) {
        (((t - 0.64f) / 0.18f) * n).toInt().coerceIn(0, n - 1)
    } else {
        -1
    }

    // 5) 图标本体：位置 = 圆心 + 半径 × 角度，半径随 eased 从角落外扩
    for (i in 0 until n) {
        val (factor, alpha) = easedOf(i)
        if (alpha <= 0f) continue
        val k = (i + 1.5f) / total
        val rad = Math.toRadians(degAt(k))
        val cx = pivot.x + rTarget * factor * cos(rad).toFloat()
        val cy = pivot.y + rTarget * factor * sin(rad).toFloat()
        val on = i == highlight
        val d = if (on) iconSize * 1.18f else iconSize
        val color = DEMO_APPS[i % DEMO_APPS.size].second

        drawRoundRect(
            color = color.copy(alpha = alpha * fade),
            topLeft = Offset(cx - d / 2f, cy - d / 2f),
            size = Size(d, d),
            cornerRadius = CornerRadius(d * 0.28f, d * 0.28f),
        )
        // 顶部高光：和真实图标底座一样「有厚度」
        drawRoundRect(
            brush = Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.28f * alpha * fade),
                0.5f to Color.Transparent,
                startY = cy - d / 2f,
                endY = cy,
            ),
            topLeft = Offset(cx - d / 2f, cy - d / 2f),
            size = Size(d, d / 2f),
            cornerRadius = CornerRadius(d * 0.28f, d * 0.28f),
        )
        glyphs.getOrNull(i)?.let { glyph ->
            drawText(
                glyph,
                topLeft = Offset(
                    cx - glyph.size.width / 2f,
                    cy - glyph.size.height / 2f,
                ),
                alpha = alpha * fade,
            )
        }
        // 选中环：白色细环，与真实轮盘 highlight 同语言
        if (on) {
            drawCircle(
                color = Color.White.copy(alpha = 0.92f * fade),
                radius = d * 0.72f,
                center = Offset(cx, cy),
                // 37 号改动（m2）：描边非整数档 1.5/1.6 并存，snap 到 1.5
                style = Stroke(1.5.dp.toPx()),
            )
        }
    }
}

@Composable
private fun SoftDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = UiSpace.sm)
            .height(1.dp)
            .background(UiTokens.Divider),
    )
}
