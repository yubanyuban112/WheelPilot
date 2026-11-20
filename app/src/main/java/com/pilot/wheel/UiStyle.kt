package com.pilot.wheel

import android.content.Context
import android.database.ContentObserver
import android.graphics.BlurMaskFilter
import android.os.Handler
import android.os.Looper
import android.graphics.Paint as AndroidPaint
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
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
 * 可切换主题。四套并行 + 一个自动档：
 *  - LIQUID_GLASS 液态玻璃：StyleKit liquid-glass（WWDC25 流动玻璃，深色渐变底 + 半透明白面板 + 彩虹描边）
 *  - CLASSIC      经典拟物：v2.8.0 以来的原有实现，原样保留（基底 #E6E9EF、蓝紫 #2F6BFF）
 *  - APPLE        Apple 流体（浅）：iOS 浅色毛玻璃材质 + awesome-design-md apple 色板
 *  - APPLE_DARK   Apple 流体（深）：63 号新增，同一套 Apple 纪律的暗色表面（surface-black 画布 +
 *                 primary-on-dark #2997FF + 材料高光反相），补齐设计文档已有的 on-dark 一列
 *  - AUTO         自动：跟随系统明暗，亮色用经典拟物、暗色用 Apple 暗色（64 号新增）
 *
 * 64 号改动：新拟物（NEUMORPHISM）与经典拟物（CLASSIC）合并，以经典拟物为主 ——
 * 两套都是「同色本体 + 双色阴影」的拟物管线，差异仅基底/强调色（#E0E5EC/#6D5DFC vs
 * #E6E9EF/#2F6BFF），对用户感知接近、徒增一个桌面图标 alias 与一套 token 的维护成本。
 */
enum class UiTheme(val label: String) {
    LIQUID_GLASS("液态玻璃"),
    CLASSIC("经典拟物"),
    APPLE("Apple 流体"),
    APPLE_DARK("Apple 暗色"),
    AUTO("自动"),
}

/** 面板渲染风格：拟物双色阴影 / 液态玻璃暗色 / Apple 浅色毛玻璃 */
enum class UiRenderStyle { NEUMORPH, LIQUID, APPLE }

/** 一套主题的全部 token；组件按 [UiTheme] 取对应套件渲染，调用点零感知 */
data class UiPalette(
    val style: UiRenderStyle,
    val primary: Color,
    val primaryOn: Color,
    val primarySoft: Color,
    val canvas: Color,
    val surface: Color,
    val dialogSurface: Color,
    val sectionBg: Color,
    val surfaceElevated: Color,
    val field: Color,
    val fieldHint: Color,
    val ink: Color,
    val inkSub: Color,
    val divider: Color,
    /** 更柔和的分隔线（apple DESIGN.md divider-soft #f0f0f0）：用于列表内的轻分隔 */
    val dividerSoft: Color,
    /** 珍珠白表面（apple DESIGN.md surface-pearl #fafafc）：危险/警告卡片底 */
    val surfacePearl: Color,
    /** 语义危险色：状态警示（非交互强调色，单一强调色纪律不受影响） */
    val danger: Color,
    /** 警告卡三色（37 号改动 T8/M1）：随主题给值，深色玻璃主题用暗底亮字，不再全局亮橙 */
    val warningBg: Color,
    val warningInk: Color,
    val warningInkSub: Color,
    val shadowDark: Color,
    val shadowLight: Color,
    /** 开关槽关闭态的底色 */
    val switchTrack: Color,
    val radiusPanel: Dp,
    val radiusCard: Dp,
    val radiusRow: Dp,
    val radiusField: Dp,
    val radiusIcon: Dp,
    /**
     * 表面明暗（63 号新增）：决定 Material 用深色还是浅色 scheme、描边高光走白还是走灰。
     * 此前只有「渲染风格 == LIQUID」一条暗色判据，Apple 暗色主题会被误判成浅色，
     * 得到白底黑字 Material scheme + 白描边压黑底。凡新增暗色主题一律置 true。
     */
    val dark: Boolean = false,
)

private val NEU_RADII = Radii()

/**
 * 圆角结构性默认值：仅 CLASSIC 经典拟物主题共用 [NEU_RADII]；
 * LIQUID_GLASS（28/24/16/14/12）与 APPLE（18/18/11/11/8）在各自 palette 里独立给值。
 * （64 号：新拟物并入经典拟物后，NEU_RADII 仅剩 CLASSIC 一处使用。）
 */
private data class Radii(
    val panel: Dp = 24.dp,
    val card: Dp = 18.dp,
    val row: Dp = 14.dp,
    val field: Dp = 12.dp,
    val icon: Dp = 10.dp,
)

private val CLASSIC_PALETTE = UiPalette(
    style = UiRenderStyle.NEUMORPH,
    primary = Color(0xFF2F6BFF),
    primaryOn = Color.White,
    primarySoft = Color(0xFFD8E1FF),
    canvas = Color(0xFFE6E9EF),
    surface = Color(0xFFE6E9EF),
    dialogSurface = Color(0xFFE6E9EF),
    sectionBg = Color(0xFFE6E9EF),
    surfaceElevated = Color(0xFFEEF1F6),
    field = Color(0xFFE6E9EF),
    // 37 号改动（T5）：#8A93A3 对比 2.54:1，WCAG AA 失败；加深到 #6B7484 ≈ 4.0:1
    fieldHint = Color(0xFF6B7484),
    ink = Color(0xFF253047),
    inkSub = Color(0xFF566072),
    divider = Color(0xFFD3D8E2),
    dividerSoft = Color(0xFFEAEDF3),
    surfacePearl = Color(0xFFF7F9FC),
    danger = Color(0xFFC0392B),
    warningBg = Color(0xFFFFF3E0),
    warningInk = Color(0xFF7A4A00),
    warningInkSub = Color(0xFF8A5A00),
    shadowDark = Color(0xFFC3C7D0),
    shadowLight = Color.White,
    switchTrack = Color(0xFFD4DAE4),
    radiusPanel = NEU_RADII.panel,
    radiusCard = NEU_RADII.card,
    radiusRow = NEU_RADII.row,
    radiusField = NEU_RADII.field,
    radiusIcon = NEU_RADII.icon,
)

/** StyleKit liquid-glass：深色渐变底 + white/10 半透明面板 + white 描边 + Apple 蓝强调 */
private val LIQUID_GLASS_PALETTE = UiPalette(
    style = UiRenderStyle.LIQUID,
    primary = Color(0xFF007AFF),
    primaryOn = Color.White,
    primarySoft = Color(0x59007AFF),
    canvas = Color(0xFF16213E),
    surface = Color(0x1AFFFFFF),
    dialogSurface = Color(0x22FFFFFF),
    sectionBg = Color(0x14FFFFFF),
    surfaceElevated = Color(0x26FFFFFF),
    field = Color(0x1AFFFFFF),
    fieldHint = Color(0x99FFFFFF),
    ink = Color.White,
    // 37 号改动（T7）：辅助文字 α 0xC7 → 0xE6，APCA Lc −74.4 → 达 12sp 辅助档要求的 90
    inkSub = Color(0xE6FFFFFF),
    divider = Color(0x2EFFFFFF),
    dividerSoft = Color(0x1FFFFFFF),
    surfacePearl = Color(0x26FFFFFF),
    danger = Color(0xFFFF6B6B),
    warningBg = Color(0x2EFFB74D),
    warningInk = Color(0xFFFFCC80),
    warningInkSub = Color(0xFFD8B47E),
    shadowDark = Color(0x2E000000),
    shadowLight = Color(0x66FFFFFF),
    switchTrack = Color(0x29FFFFFF),
    radiusPanel = 28.dp,
    radiusCard = 24.dp,
    radiusRow = 16.dp,
    radiusField = 14.dp,
    radiusIcon = 12.dp,
    dark = true,
)

/**
 * Apple 流体（apple-design 技能规范）：iOS 浅色毛玻璃。
 *  - 材质：regular material = 白 78% 半透明 + 白高光描边 + 黑柔影（浅色环境的磨砂玻璃）
 *  - 色板：systemGroupedBackground #F2F2F7 / label #1C1C1E / secondaryLabel #8E8E93 / separator #E5E5EA / tint #007AFF
 *  - 动效：GlassMotion 本就是 Apple 官方弹簧（sheet ζ0.8/0.3s 等），全部主题共用
 *  - 圆角：iOS 连续圆角（面板 22 / 卡片 16 / 行 12 / 输入 10）
 */
private val APPLE_PALETTE = UiPalette(
    style = UiRenderStyle.APPLE,
    // 对齐 awesome-design-md apple/DESIGN.md：唯一交互色 Action Blue #0066CC（非 iOS tint），
    // 墨色 #1D1D1F、次级 #7A7A7A、发丝线 #E0E0E0、羊皮纸画布 #F5F5F7、圆角 lg 18 / md 11 / sm 8。
    primary = Color(0xFF0066CC),
    primaryOn = Color.White,
    primarySoft = Color(0x1F0066CC),
    canvas = Color(0xFFF5F5F7),
    surface = Color(0xC7FFFFFF),
    dialogSurface = Color(0xE6FFFFFF),
    sectionBg = Color(0xFFF5F5F7),
    surfaceElevated = Color(0xEBFFFFFF),
    field = Color(0xFFE9E9EE),
    fieldHint = Color(0xFF7A7A7A),
    ink = Color(0xFF1D1D1F),
    // 37 号改动（T4）：#7A7A7A 对比 3.94:1，WCAG AA 失败；加深到 iOS systemGray 邻值 #6E6E73 ≈ 4.7:1
    inkSub = Color(0xFF6E6E73),
    divider = Color(0xFFE0E0E0),
    dividerSoft = Color(0xFFF0F0F0),
    surfacePearl = Color(0xFFFAFAFC),
    danger = Color(0xFFD70015),
    warningBg = Color(0xFFFFF3E0),
    warningInk = Color(0xFF7A4A00),
    warningInkSub = Color(0xFF8A5A00),
    shadowDark = Color(0x293C3C43),
    shadowLight = Color.White,
    switchTrack = Color(0xFFE9E9EB),
    radiusPanel = 18.dp,
    radiusCard = 18.dp,
    radiusRow = 11.dp,
    radiusField = 11.dp,
    radiusIcon = 8.dp,
)

/**
 * Apple 流体（深色）—— 63 号新增主题。
 *
 * 纪律来源：`design/apple-DESIGN.md` 本就给了 on-dark 一列（primary-on-dark #2997ff、
 * body-on-dark #ffffff、surface-black #000000），只是一直没有主题去用它。本主题把那一列
 * 正式落成第五套 palette，其余纪律（单一强调色 / 字重梯 300·400·600 / 药丸语法 /
 * 组件不投影 / 正文行高 ≥1.47）与浅色 Apple 完全一致，只有表面明暗与高光方向相反。
 *
 * 关键取舍：
 *  - 强调色取 #2997FF（文档指定「深色底专用」），不取浅色底的 #0066CC（在黑底上对比仅 3.0）；
 *  - 主按钮文字用 #000000 而非白：白字压 #2997FF 仅 2.9:1（AA 不合格），黑字 7.2:1 合格；
 *  - 材质高光由「白 70% 描边」反相为「白 12% 描边」，否则暗色面板会被描成发光的白框；
 *  - 画布 #000000 是 OLED 省电底，卡片 #1C1C1E、浮起 #2C2C2E，跟 iOS 深色语义层级一致。
 */
private val APPLE_DARK_PALETTE = UiPalette(
    style = UiRenderStyle.APPLE,
    primary = Color(0xFF2997FF),
    primaryOn = Color(0xFF000000),
    primarySoft = Color(0x332997FF),
    canvas = Color(0xFF000000),
    surface = Color(0xCC1C1C1E),
    dialogSurface = Color(0xF21C1C1E),
    sectionBg = Color(0xFF1C1C1E),
    surfaceElevated = Color(0xFF2C2C2E),
    field = Color(0xFF2C2C2E),
    fieldHint = Color(0xFF98989F),
    ink = Color(0xFFFFFFFF),
    inkSub = Color(0xFFA1A1A6),
    divider = Color(0xFF38383A),
    dividerSoft = Color(0xFF2C2C2E),
    surfacePearl = Color(0xFF1C1C1E),
    danger = Color(0xFFFF453A),
    warningBg = Color(0xFF33280F),
    warningInk = Color(0xFFFFD08A),
    warningInkSub = Color(0xFFD8B47E),
    shadowDark = Color(0x8C000000),
    shadowLight = Color(0x1FFFFFFF),
    switchTrack = Color(0xFF39393D),
    radiusPanel = 18.dp,
    radiusCard = 18.dp,
    radiusRow = 11.dp,
    radiusField = 11.dp,
    radiusIcon = 8.dp,
    dark = true,
)

/** 主题状态单源：入口 load 一次，设置页切换后全应用重组 */
object ThemeController {
    var current by mutableStateOf(UiTheme.CLASSIC)
        private set

    /** 系统是否深色（AUTO 主题下决定实际用哪一套） */
    var systemDark by mutableStateOf(false)
        private set

    /** AUTO 解析后的实际主题：亮色→经典拟物（主），暗色→Apple 暗色 */
    fun effective(): UiTheme = if (current == UiTheme.AUTO) {
        if (systemDark) UiTheme.APPLE_DARK else UiTheme.CLASSIC
    } else current

    val palette: UiPalette
        get() = when (effective()) {
            UiTheme.LIQUID_GLASS -> LIQUID_GLASS_PALETTE
            UiTheme.APPLE -> APPLE_PALETTE
            UiTheme.APPLE_DARK -> APPLE_DARK_PALETTE
            UiTheme.CLASSIC -> CLASSIC_PALETTE
            UiTheme.AUTO -> CLASSIC_PALETTE // effective() 已解析，不会走到这里
        }

    /** 每个 Compose 入口（MainActivity / AppSheetActivity / LightOpenActivity）创建时调用一次 */
    fun load(context: Context) {
        current = Prefs.uiTheme(context)
        systemDark = isSystemDark(context)
    }

    /** 设置页切换：立即生效、持久化，并把桌面图标换成对应主题的一套 */
    fun select(context: Context, theme: UiTheme) {
        current = theme
        systemDark = isSystemDark(context)
        Prefs.setUiTheme(context, theme)
        ThemeIcon.sync(context, theme)
    }

    /** WheelTheme 组合时回传系统深色状态（AUTO 主题下据此重组） */
    fun onSystemDarkChanged(dark: Boolean) {
        systemDark = dark
    }

    private fun isSystemDark(context: Context): Boolean =
        (context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
}

/**
 * 设计 token（三主题代理）。属性全部是 getter：读到底层 mutableStateOf，
 * 组合期内读取可跟踪，切换主题时所有调用点自动重组。
 */
object UiTokens {
    private val p: UiPalette get() = ThemeController.palette

    val isGlass: Boolean get() = p.style == UiRenderStyle.LIQUID
    val isApple: Boolean get() = p.style == UiRenderStyle.APPLE
    val style: UiRenderStyle get() = p.style
    /**
     * 当前主题是否落在暗色表面（63 号）：Material scheme 明暗、材料高光方向、发丝线亮度
     * 都由它决定，**不再用「style == LIQUID」代判**——那样 Apple 暗色会被当成浅色。
     */
    val isDark: Boolean get() = p.dark

    val Primary: Color get() = p.primary
    val PrimaryOn: Color get() = p.primaryOn
    val PrimarySoft: Color get() = p.primarySoft

    val Canvas: Color get() = p.canvas
    val Surface: Color get() = p.surface
    val DialogSurface: Color get() = p.dialogSurface
    val SectionBg: Color get() = p.sectionBg
    /** 浮层：比画布略亮一档（拟物）或 white/15（玻璃） */
    val SurfaceElevated: Color get() = p.surfaceElevated
    val Field: Color get() = p.field
    val FieldHint: Color get() = p.fieldHint
    val Ink: Color get() = p.ink
    val InkSub: Color get() = p.inkSub
    val Divider: Color get() = p.divider
    /** 轻分隔线（apple DESIGN.md divider-soft）：列表内分组之间的弱分隔 */
    val DividerSoft: Color get() = p.dividerSoft
    /** 珍珠白表面（apple DESIGN.md surface-pearl）：告警/提示卡填充 */
    val SurfacePearl: Color get() = p.surfacePearl
    /** 语义危险色：仅用于状态警示文案，不作交互强调色 */
    val Danger: Color get() = p.danger

    // 阴影：拟物=双色实体阴影；玻璃=黑柔光/白高光
    val ShadowDark: Color get() = p.shadowDark
    val ShadowLight: Color get() = p.shadowLight

    /** 开关槽关闭态底色（drawSwitchTrack 用） */
    val SwitchTrack: Color get() = p.switchTrack

    val RadiusPanel: Dp get() = p.radiusPanel
    val RadiusCard: Dp get() = p.radiusCard
    val RadiusRow: Dp get() = p.radiusRow
    val RadiusField: Dp get() = p.radiusField
    val RadiusIcon: Dp get() = p.radiusIcon
    /** 药丸/圆形：百分比圆角（按钮、搜索框、标签、Chip 统一用这一个） */
    val RadiusPill = 50

    val SearchHeight = 44.dp
    val GridIcon = 44.dp

    // ---- 37 号改动新增 token ----

    /** 小图标（chevron 等此前 18.dp 字面量档） */
    val IconSm = 18.dp
    /** 中图标（此前 22.dp 字面量档） */
    val IconMd = 22.dp
    /** 小指示条圆角（此前 2.dp 字面量散落 3 处） */
    val IndicatorRadius = 2.dp
    /** 全屏遮罩（「更多」面板，此前硬编码 0x61000000） */
    val Scrim = Color(0x61000000)

    /** 警告卡三色：随主题取值（深色玻璃 = 暗底亮字） */
    val WarningBg: Color get() = p.warningBg
    val WarningInk: Color get() = p.warningInk
    val WarningInkSub: Color get() = p.warningInkSub
}

/**
 * 间距 token（单源）：MD3 space 阶梯 2/4/6/8/10/12/14/16/20/24/32/40/48/56/64。
 * 之前间距散落在各页面的字面量里（出现过 1/3/9/13/18/22 等阶梯外值），
 * 现在全部走这里：阶梯内取最近档，阶梯外一律 snap 进来。
 */
object UiSpace {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 6.dp
    val s = 8.dp
    val ms = 10.dp
    val m = 12.dp
    val ml = 14.dp
    val l = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp
    val huge = 40.dp
    val giant = 48.dp
    val mega = 56.dp
    val ultra = 64.dp

    /** 语义别名：调用点优先用这几个，读起来就是意图 */
    val screenMargin = l // 页面左右边距 16
    val cardPadding = l // 卡片内边距 16
    val groupInner = s // 组内间距 8
    val groupOuter = l // 组间间距 16
    val iconGap = xs // 图标↔文字 4
    val touchTarget = giant // 触摸目标 48

    /**
     * 文字对齐线：卡片左右边距 20 + 卡内文字边距 8 = 28。
     * 页面里所有文字（页面大标题、分组卡片标题、展开内容、提示卡）都对到这一条线，
     * 卡片外框统一 20 —— 之前分组标题卡 12dp、标题文字 24dp、展开内容 20dp 三套值，
     * 造成正文比标题还靠左的倒挂。
     */
    val Content = xl + s // 28
}

/**
 * 字阶 token（单源）：字号 34/28/22/18/16/14/12/11，每档固定行高、字重与**字距**。
 * 之前 MaterialTheme.typography 默认档位 + 13/18/38sp 字面量混用，
 * 同一个字号会出现两种字重（例如 16sp Regular 与 16sp Medium 并存），层级读不出来。
 *
 * 63 号改动（排版层级补全）：
 *  1. 字距进 token 本体 —— 此前负字距只加在 WheelTheme 的 Material 映射里，
 *     页面直接用 `UiType.title` 渲染的标题**没有**字距，同一屏里两处 22sp 标题松紧不同；
 *  2. 行高按 apple DESIGN.md「正文行高不小于 1.47」校正：body 14/20（1.43，不合格）→ 14/21（1.5），
 *     caption 12/16（1.33）→ 12/17（1.42）。行高只增 1sp，固定高行（44/48dp）不受影响。
 */
object UiType {
    /** 大标题（页面级） */
    val display = TextStyle(
        fontSize = 34.sp,
        lineHeight = 40.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.4).sp,
    )

    /** 分区主标题 */
    val displayMd = TextStyle(
        fontSize = 28.sp,
        lineHeight = 34.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.35).sp,
    )

    /** 页面标题 / 一级菜单 */
    val title = TextStyle(
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.2).sp,
    )

    /** 一级菜单分组标题（设置页可折叠分组） */
    val groupTitle = TextStyle(
        fontSize = 18.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.15).sp,
    )

    /** 卡片标题 / 行标题（DESIGN.md：字重梯 300/400/600/700，500 缺席，中重一律 600） */
    val section = TextStyle(
        fontSize = 16.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.12).sp,
    )

    /** 正文（行高 1.5，满足 Apple「正文行高 ≥1.47」） */
    val body = TextStyle(fontSize = 14.sp, lineHeight = 21.sp, letterSpacing = (-0.28).sp)

    /** 辅助说明 */
    val caption = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, letterSpacing = (-0.22).sp)

    /** 角标 / 极次要信息 */
    val micro = TextStyle(
        fontSize = 11.sp,
        lineHeight = 13.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = (-0.08).sp,
    )

    // ---- 37 号改动（T1/C3）新增：此前的 9/10sp 字面量档收进字阶单源 ----

    /**
     * A–Z 索引条专用字形：9sp，行高与字号持平（单字母、格高 ≈9.2dp，不换行；
     * 复用到两行文案会截断——约束写在这里而不是散在调用点）。
     */
    val indexGlyph = TextStyle(fontSize = 9.sp, lineHeight = 9.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp)

    /** 小徽标字形（轮盘演示磁贴白字等 10sp 档） */
    val badgeGlyph = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold)
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

/**
 * 动效规范（63 号新增，动效单源）。命名沿用 Web 动效工程的习惯，便于与设计稿对齐：
 *
 * **四条硬约束（写在这里，而不是散在各个组件里）**
 *  1. 只动 `transform` 与 `opacity` —— 对应 Compose 的 `graphicsLayer`（scale/translation/alpha）。
 *     这些属性走 RenderNode 提交，不触发 measure/layout，帧预算内不重排。
 *  2. 进慢出快、进出曲线不同 —— 出现用指数缓出（起手快、落定稳），消失用线性加速缓入，
 *     让「离开」在感知上比「出现」快一档（220ms vs 130ms）。
 *  3. 按压先响应、后回弹 —— 按下 90ms 就走完，松手 180ms 弹回：手指要先得到反馈，
 *     回弹可以从容。用同一个 300ms 曲线做两向，按下会「黏」。
 *  4. 错峰有上限 —— 第 7 项起不再追加延迟，否则长列表末项要等半秒才出现。
 *
 * 减弱动效（系统 ANIMATOR_DURATION_SCALE = 0）时，所有入口退化为 `snap()` 或直接呈现最终值，
 * 由各调用点的 `rememberMotionEnabled()` 决定，本对象只提供参数。
 */
object Motion {
    // ---- 曲线（cubic-bezier，等价 CSS timing-function）----

    /** 进场：ease-out-expo 近似，起手快、尾部极缓，最适合「元素出现」 */
    val EaseOut = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

    /** 退场：ease-in，越走越快，让消失干净利落 */
    val EaseIn = CubicBezierEasing(0.4f, 0f, 1f, 1f)

    /** 位移/换轨：ease-in-out-quint，两端都留缓冲，适合指示器滑格 */
    val EaseInOut = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)

    // ---- 时长（ms）----

    /** 进场 220ms */
    const val ENTER_MS = 220

    /** 退场 130ms */
    const val EXIT_MS = 130

    /** 按下 90ms（必须够快，否则按压「黏手」） */
    const val PRESS_IN_MS = 90

    /** 松手回弹 180ms */
    const val PRESS_OUT_MS = 180

    /** 换轨（页签指示器、分段选择）260ms */
    const val MOVE_MS = 260

    /** 错峰步长 30ms */
    const val STAGGER_MS = 30

    /** 错峰上限：index ≥ 6 不再追加延迟 */
    const val STAGGER_LIMIT = 6

    /** 进场位移 12dp（超过 20dp 会让列表显得「飘」，Emil 的经验值在 8–16 之间） */
    val enterTravel = 12.dp

    /** 主按钮按压比例（Apple 系统级微交互 scale 0.95–0.97） */
    const val PressScale = 0.96f

    /** 可点卡片按压比例（面积大，比例要更小，否则位移感过强） */
    const val PressScaleCard = 0.985f

    // ---- 具体 spec ----

    /** 进场：位移 + 淡入 */
    fun enter(): TweenSpec<Float> = tween(ENTER_MS, easing = EaseOut)

    /** 退场 */
    fun exit(): TweenSpec<Float> = tween(EXIT_MS, easing = EaseIn)

    /** 按下（快） */
    fun pressIn(): TweenSpec<Float> = tween(PRESS_IN_MS, easing = EaseOut)

    /** 松手（慢） */
    fun pressOut(): TweenSpec<Float> = tween(PRESS_OUT_MS, easing = EaseOut)

    /** 换轨：接近临界阻尼的弹簧，落定不抖动 */
    fun move(): SpringSpec<Float> = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)

    /** 交错进场延迟（含上限） */
    fun staggerDelay(index: Int): Int = index.coerceAtMost(STAGGER_LIMIT) * STAGGER_MS

    /** 颜色过渡（选中态、图标着色）：比位移略慢，色彩变化太快会闪 */
    fun color(): TweenSpec<Color> = tween(240, easing = EaseInOut)
}

/**
 * 按压反馈（transform-only）：按下缩到 [scale]、松手弹回，走 [Motion.pressIn]/[Motion.pressOut]
 * 的非对称节奏。只写 `graphicsLayer`，不参与布局，也不会引起父容器重排。
 *
 * 用法：`clickable(interactionSource = src)` 与 `pressFeedback(src)` 必须共用同一个 source，
 * 否则收不到 pressed 事件。减弱动效时直接不缩放（不是缩得很快——是根本不缩）。
 */
fun Modifier.pressFeedback(
    interaction: MutableInteractionSource,
    enabled: Boolean = true,
    scale: Float = Motion.PressScale,
): Modifier = composed {
    val motionOn = rememberMotionEnabled()
    val pressed by interaction.collectIsPressedAsState()
    val factor by animateFloatAsState(
        targetValue = if (pressed && enabled && motionOn) scale else 1f,
        animationSpec = if (pressed) Motion.pressIn() else Motion.pressOut(),
        label = "press-feedback",
    )
    this.graphicsLayer {
        scaleX = factor
        scaleY = factor
    }
}

/**
 * 滚动视差（ScrollTrigger 的 Compose 等价物）：内容随滚动偏移做反向微位移，
 * 制造「背景比内容慢半拍」的层次。只写 `graphicsLayer.translationY`，滚动帧内零重排。
 *
 * @param scrollProvider 滚动像素快照的惰性提供者（传 `{ scrollState.value }`）。
 *   状态在 graphicsLayer 绘制块内读取，只失效「绘制」这一层，不重组整个调用点子树。
 * @param factor 视差系数，0.08–0.15 之间最稳；>0.2 会出现明显「拖影」
 * @param maxShift 位移上限，防止长列表滚到底时元素飞出容器
 */
fun Modifier.scrollParallax(
    scrollProvider: () -> Int,
    factor: Float = 0.12f,
    maxShift: Dp = 24.dp,
): Modifier = composed {
    val motionOn = rememberMotionEnabled()
    if (!motionOn) {
        this
    } else {
        val limit = maxShift.value
        this.graphicsLayer {
            val scroll = scrollProvider()
            val shift = (scroll * factor).coerceIn(-limit, limit)
            translationY = -shift.dp.toPx()
        }
    }
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

/**
 * 柔光画笔（63 号：加缓存）。
 * 原实现每次绘制都 `AndroidPaint()` + `BlurMaskFilter()` 新建对象，而 `neuPressable` 的按下
 * 变形是逐帧重绘的 —— 一次按压要新建十几个 Paint 和同样多的滤镜对象，全部是 GC 垃圾。
 * 颜色与模糊半径的组合有限（主题色 × 6 档半径），按 (argb, blur) 复用即可。
 * 画笔创建后只读，不会在帧之间被改写，跨帧复用安全。
 */
private val blurredPaintCache = HashMap<Long, AndroidPaint>()

private fun blurredPaint(color: Color, blur: Float): AndroidPaint {
    val argb = color.toArgb()
    val key = (argb.toLong() shl 32) or (blur.toRawBits().toLong() and 0xFFFFFFFFL)
    blurredPaintCache[key]?.let { return it }
    val paint = AndroidPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = argb
        if (blur > 0.5f) maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
    }
    blurredPaintCache[key] = paint
    return paint
}

/**
 * 表面绘制总入口：新拟物主题走双色阴影管线，液态玻璃走半透明面板管线。
 * @param morph 0=全外凸，1=全内凹；中间值用于按下过渡（凸起阴影缩小、内凹渐显）
 * @param insetScale 内凹深度缩放（输入框 focus 时从 1 降到 ~0.35，通道开放感）
 */
private fun DrawScope.drawSurface(
    shape: Shape,
    morph: Float,
    blur: Float,
    dist: Float,
    insetScale: Float = 1f,
    body: Color = UiTokens.Surface,
) {
    when (UiTokens.style) {
        UiRenderStyle.LIQUID -> drawGlass(shape, morph, body)
        UiRenderStyle.APPLE -> drawApple(shape, morph, body)
        else -> drawNeu(shape, morph, blur, dist, insetScale, body)
    }
}

/**
 * Apple 浅色毛玻璃面板（iOS regular material）。
 *  - 本体：白 78% 半透明，按下向白 92% 过渡（更实、更亮）
 *  - 描边：白 70% 一圈高光，模拟磨砂玻璃边缘反光；按下增亮（Specular Edges）
 *  - 投影：黑柔影微向下，iOS 卡片的标准深度表达
 */
private fun DrawScope.drawApple(shape: Shape, morph: Float, body: Color) {
    val outline = shape.createOutline(size, layoutDirection, this)
    val radius = (outline as? Outline.Rounded)?.roundRect?.topLeftCornerRadius?.x ?: 16f
    val dark = UiTokens.isDark

    drawIntoCanvas { c ->
        val n = c.nativeCanvas
        // 暗色下投影要更实一点（黑底上的黑影几乎不可见，靠 8dp 下溢把面板从纯黑里托起来）
        val glow = blurredPaint(UiTokens.ShadowDark, if (dark) 20f else 12f)
        n.drawRoundRect(0f, 5f, size.width, size.height + 8f, radius, radius, glow)
    }

    // 按下态：浅色向白 92% 提亮；暗色向 #3A3A3C 提亮（不是变白，暗色材质的「更实」= 更亮一档灰）
    val pressedFill = if (dark) Color(0xFF3A3A3C) else Color(0xEBFFFFFF)
    neuBody(outline, androidx.compose.ui.graphics.lerp(body, pressedFill, morph))

    // 描边高光反相：浅色 70%/85% 白（磨砂玻璃边缘反光）；暗色 10%/20% 白（暗色材质没有强反光，
    // 用低透明白勾边，否则面板会被描成发光的白框，与 iOS 深色完全不是一套语言）
    val w = 1.dp.toPx()
    val rim = when {
        dark && morph > 0.5f -> 0x33FFFFFF
        dark -> 0x1AFFFFFF
        morph > 0.5f -> 0xD9FFFFFF
        else -> 0xB3FFFFFF
    }
    drawRoundRect(
        color = Color(rim),
        cornerRadius = CornerRadius(radius, radius),
        style = Stroke(w),
    )
}

/**
 * 液态玻璃面板（StyleKit liquid-glass）：
 *  - 半透明本体（white/10 起，按下向 white/16 过渡）
 *  - 描边用竖向渐变：顶部高光亮、底部弱（玻璃倒角顶部反光），按下增亮（Specular Edges）
 *  - 黑柔光投影微向下偏移，把面板从背景上托起来
 * API 30 无背景模糊能力，靠画布彩色光斑透过半透明本体显色。
 */
private fun DrawScope.drawGlass(shape: Shape, morph: Float, body: Color) {
    val outline = shape.createOutline(size, layoutDirection, this)
    val radius = (outline as? Outline.Rounded)?.roundRect?.topLeftCornerRadius?.x ?: 24f

    // 1) 投影：黑色柔光，向下偏一点
    drawIntoCanvas { c ->
        val n = c.nativeCanvas
        val glow = blurredPaint(UiTokens.ShadowDark, 16f)
        n.drawRoundRect(0f, 6f, size.width, size.height + 10f, radius, radius, glow)
    }

    // 2) 半透明本体：按下时提亮（white/10 → white/16）
    val pressedFill = Color(0x2EFFFFFF)
    neuBody(outline, androidx.compose.ui.graphics.lerp(body, pressedFill, morph))

    // 3) 渐变描边：顶部高光白、底部弱白；按下整圈增亮
    val w = 1.dp.toPx()
    val rimTop = if (morph > 0.5f) 0x66FFFFFF else 0x50FFFFFF
    val rimBottom = if (morph > 0.5f) 0x40FFFFFF else 0x26FFFFFF
    drawRoundRect(
        brush = Brush.verticalGradient(0f to Color(rimTop), 1f to Color(rimBottom)),
        cornerRadius = CornerRadius(radius, radius),
        style = Stroke(w),
    )
}

/**
 * 新拟物阴影绘制（经典拟物与新拟物微调版共用管线，色值由 palette 决定）。
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
): Modifier = drawBehind { drawSurface(shape, morph = 0f, blur = blur, dist = dist, body = body) }

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
    drawSurface(shape, morph = 1f, blur = blur, dist = dist, insetScale = scale, body = body)
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
    val motionOn = rememberMotionEnabled()
    val morph by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        // 减弱动效开启时不做按压补间（UI UX Pro Max · reduced-motion）
        animationSpec = if (motionOn) GlassMotion.molding() else snap(),
        label = "neu-morph",
    )
    this
        .drawBehind { drawSurface(shape, morph = morph, blur = blur, dist = dist) }
        .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
}

/**
 * 扁平行（3.19.2）：设置页三级条目的统一形态。
 *
 * 为什么不再用 [neuPressable]：drawSurface 走 drawBehind，阴影画在自身边界**之外**
 * （−d,−d 到 w+d, h+d）。LazyColumn 里相邻条目垂直外间距为 0，上一行的阴影直接压到
 * 下一行、并与分组标题的 2dp 间隙重叠 —— 就是本次报的「界面互相重叠」。
 * 条目属于列表流式内容、不是浮起卡片，所以这里改成：不画任何阴影，按下给一层浅色底，
 * 行与行之间用一条内缩发丝线分隔。层级仍由「22sp 大类 > 18sp 分组 > 16sp 条目」表达。
 */
fun Modifier.flatRow(
    shape: Shape = RoundedCornerShape(UiTokens.RadiusRow),
    enabled: Boolean = true,
    showDivider: Boolean = true,
    onClick: () -> Unit,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val motionOn = rememberMotionEnabled()
    val tint by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        // 减弱动效时不做按压补间，直接换底
        animationSpec = if (motionOn) tween(120) else snap(),
        label = "flat-row-tint",
    )
    this
        .drawBehind {
            val radius = (shape.createOutline(size, layoutDirection, this) as? Outline.Rounded)
                ?.roundRect?.topLeftCornerRadius?.x ?: 12.dp.toPx()
            if (tint > 0f) {
                drawRoundRect(
                    color = UiTokens.Primary.copy(alpha = 0.08f * tint),
                    cornerRadius = CornerRadius(radius, radius),
                )
            }
            if (showDivider) {
                val inset = 16.dp.toPx()
                val y = size.height - 0.5.dp.toPx()
                drawLine(
                    color = UiTokens.DividerSoft,
                    start = Offset(inset, y),
                    end = Offset(size.width - inset, y),
                    strokeWidth = 1.dp.toPx(),
                )
            }
        }
        .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
}

/**
 * 系统「减弱动效」开关（UI UX Pro Max / Android 无障碍 DISPLAY_ANIMATION 纪律，全应用单源）。
 * 读取 ANIMATOR_DURATION_SCALE：用户开启"移除动画/减弱动效"后系统返回 0，
 * 此时所有位移/缩放/淡入动画应退化为「直接呈现」，不做补间。
 * 各页面动画统一走这里，避免有的组件尊重、有的不尊重（此前只有 cascadeEnter 检查）。
 */
@Composable
fun rememberMotionEnabled(): Boolean {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(readMotionScale(context) > 0f) }
    // 37 号改动（S7）：系统「减弱动效」是全局设置，此前无 key remember 只在首组合读一次，
    // 用户切换后应用感知不到。现在注册 ContentObserver 跟踪 ANIMATOR_DURATION_SCALE 变化，
    // 回到前台时再兜底刷一次（个别 ROM 改设置不广播）。
    DisposableEffect(context.contentResolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                enabled = readMotionScale(context) > 0f
            }
        }
        context.contentResolver.registerContentObserver(
            android.provider.Settings.Global.getUriFor(
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            ),
            false,
            observer,
        )
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    LifecycleResumeEffect(Unit) {
        enabled = readMotionScale(context) > 0f
        onPauseOrDispose { }
    }
    return enabled
}

/** ANIMATOR_DURATION_SCALE 读数（减弱动效 = 0）；读失败按 1 处理 */
private fun readMotionScale(context: Context): Float = runCatching {
    android.provider.Settings.Global.getFloat(
        context.contentResolver,
        android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    )
}.getOrDefault(1f)

/**
 * 级联进场：首次组合时淡入 + 上滑，index 越大错峰越晚（步长 [Motion.STAGGER_MS]，6 项封顶）。
 *
 * 63 号改动：原实现用 Apple sheet 弹簧（damping 0.8）跑进场，存在两个问题 ——
 * ① 弹簧带 5–10% 过冲，列表项会先冲过目标位置再回弹，多项同时进场时像「抖动」；
 * ② 位移 40dp 过大，短列表一眼看去是「从下方飞入」，压过了内容本身。
 * 现改为「指数缓出 + 12dp 位移 + 透明度」，无过冲、无飞入感，只做层次提示。
 * 只写 graphicsLayer（alpha / translationY），不参与布局。
 *
 * 无障碍：系统动画时长缩放为 0（减弱动效）时，无延迟、无补间，直接呈现。
 */
fun Modifier.cascadeEnter(index: Int): Modifier = composed {
    val motionOn = rememberMotionEnabled()
    var shown by remember { mutableStateOf(!motionOn) }
    LaunchedEffect(motionOn) {
        if (!motionOn) {
            shown = true
        } else if (!shown) {
            kotlinx.coroutines.delay(Motion.staggerDelay(index).toLong())
            shown = true
        }
    }
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = if (motionOn) Motion.enter() else snap(),
        label = "cascade-enter",
    )
    val travel = Motion.enterTravel.value
    this.graphicsLayer {
        alpha = progress
        translationY = (1f - progress) * travel.dp.toPx()
    }
}

// ---------------------------------------------------------------------------
// 兼容旧调用点的表面组件（内部全部新拟物化）
// ---------------------------------------------------------------------------

/**
 * 统一画布：拟物主题 = 平色基底；液态玻璃 = 深色渐变 + 大彩色光斑（透过半透明面板显色）。
 */
@Composable
fun GlassCanvas(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val backgroundModifier = if (UiTokens.isGlass) {
        modifier
            .background(
                Brush.linearGradient(
                    0f to Color(0xFF1A1A2E),
                    0.55f to Color(0xFF16213E),
                    1f to Color(0xFF0F0F23),
                ),
            )
            .drawBehind {
                // 三个大光斑：品红 / 紫 / 蓝，模拟流动玻璃下的折射光源；径向渐变自带柔边
                fun blob(fx: Float, fy: Float, r: Float, color: Color) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            0f to color,
                            1f to Color.Transparent,
                            center = Offset(size.width * fx, size.height * fy),
                            radius = size.minDimension * r,
                        ),
                        radius = size.minDimension * r,
                        center = Offset(size.width * fx, size.height * fy),
                    )
                }
                blob(0.15f, 0.05f, 0.55f, Color(0x40FF2D55))
                blob(0.90f, 0.35f, 0.60f, Color(0x38A855F7))
                blob(0.35f, 0.95f, 0.55f, Color(0x38007AFF))
            }
    } else {
        modifier.background(UiTokens.Canvas)
    }
    Box(
        modifier = backgroundModifier,
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

/**
 * 液态玻璃底栏（3.18.1 重绘）。
 *
 * 旧实现直接复用拟物的 [neuRaised]：深色渐变画布上叠一层 white/10 面板 + 1px 白边，
 * 结果是一条「灰蒙蒙的实心条」——玻璃该有的三重层次都没有：
 *  1. 底部环境影（把 dock 从内容上托起来）；
 *  2. 本体竖向渐变（顶部受光更亮、底部折射主色）；
 *  3. 顶沿倒角高光（WWDC25 liquid glass 的标志性一道亮线）+ 整圈细边。
 * 这里按这三层分开绘制，选中药丸另用 [glassPill] 走「主色玻璃 + 外发光」。
 */
fun Modifier.glassDock(
    shape: Shape = RoundedCornerShape(UiTokens.RadiusPanel),
    tint: Color = UiTokens.Primary,
): Modifier = drawBehind {
    val outline = shape.createOutline(size, layoutDirection, this)
    val radius = (outline as? Outline.Rounded)?.roundRect?.topLeftCornerRadius?.x ?: 28f

    // 1) 环境影：向下溢出，dock 压在内容之上
    drawIntoCanvas { c ->
        val n = c.nativeCanvas
        val glow = blurredPaint(Color(0x73000000), 26f)
        n.drawRoundRect(-2f, 6f, size.width + 2f, size.height + 16f, radius, radius, glow)
    }

    // 2) 玻璃本体：顶部受光、底部折射主色（主色只占很低不透明度，不改变深色调性）
    drawRoundRect(
        brush = Brush.verticalGradient(
            0f to Color(0x33FFFFFF),
            0.42f to Color(0x1CFFFFFF),
            0.82f to Color(0x14FFFFFF),
            1f to tint.copy(alpha = 0.18f),
        ),
        cornerRadius = CornerRadius(radius, radius),
    )

    // 3) 顶沿倒角高光：上沿 3dp 内一道更亮的白线，玻璃厚度的来源
    clipRect(
        left = 0f,
        top = 0f,
        right = size.width,
        bottom = 3.dp.toPx(),
    ) {
        drawRoundRect(
            brush = Brush.verticalGradient(
                0f to Color(0x99FFFFFF),
                1f to Color(0x00FFFFFF),
            ),
            cornerRadius = CornerRadius(radius, radius),
            style = Stroke(2.dp.toPx()),
        )
    }

    // 4) 整圈细边：上亮下弱，按下/未按下都保持同一条边（不闪）
    drawRoundRect(
        brush = Brush.verticalGradient(
            0f to Color(0x73FFFFFF),
            0.5f to Color(0x33FFFFFF),
            1f to Color(0x1AFFFFFF),
        ),
        cornerRadius = CornerRadius(radius, radius),
        style = Stroke(1.dp.toPx()),
    )
}

/**
 * 玻璃主题下的「选中」药丸：主色玻璃 + 白色内沿 + 外发光。
 * 拟物主题的选中态是内凹（Extrude to Intrude），玻璃没有内外之分，
 * 用亮度与光晕表达「已被选中」，语义一致、语言不同。
 */
fun Modifier.glassPill(
    shape: Shape = RoundedCornerShape(UiTokens.RadiusPill),
    tint: Color = UiTokens.Primary,
): Modifier = drawBehind {
    val outline = shape.createOutline(size, layoutDirection, this)
    val radius = (outline as? Outline.Rounded)?.roundRect?.topLeftCornerRadius?.x ?: size.height / 2f

    drawIntoCanvas { c ->
        val n = c.nativeCanvas
        val halo = blurredPaint(tint.copy(alpha = 0.55f), 14f)
        n.drawRoundRect(0f, 0f, size.width, size.height, radius, radius, halo)
    }
    drawRoundRect(
        brush = Brush.verticalGradient(
            0f to tint.copy(alpha = 0.42f),
            1f to tint.copy(alpha = 0.20f),
        ),
        cornerRadius = CornerRadius(radius, radius),
    )
    drawRoundRect(
        brush = Brush.verticalGradient(
            0f to Color(0x8CFFFFFF),
            1f to Color(0x1FFFFFFF),
        ),
        cornerRadius = CornerRadius(radius, radius),
        style = Stroke(1.dp.toPx()),
    )
}

/**
 * Apple 主题底栏（3.19.0）。
 *
 * 之前只有「玻璃」与「拟物」两条分支，Apple 主题落进拟物分支，得到一条带凸起与
 * 双向阴影的厚条 —— 与 iOS 的 tab bar 完全是两套语言。Apple 的材质规则是：
 *  1. regular material：白色 78% 半透明磨砂，不是实心，也不是凸起；
 *  2. 边界靠 1px separator 发丝线，不靠凸起阴影；
 *  3. 投影只负责把 dock 从内容里托起一点（2–8dp），不做「从背景里长出来」。
 * 选中态另用 [applePill]（灰底胶囊），同样不画内外凹凸。
 */
fun Modifier.appleDock(shape: Shape = RoundedCornerShape(UiTokens.RadiusPanel)): Modifier = drawBehind {
    val outline = shape.createOutline(size, layoutDirection, this)
    val radius = (outline as? Outline.Rounded)?.roundRect?.topLeftCornerRadius?.x ?: 22f
    val dark = UiTokens.isDark

    // 1) 极轻投影：下溢 8dp，透明度 10%，只够和内容分层（暗色加深，黑底上才看得出托起）
    drawIntoCanvas { c ->
        val n = c.nativeCanvas
        val shadow = blurredPaint(if (dark) Color(0x8C000000) else Color(0x1A000000), 16f)
        n.drawRoundRect(-2f, 2f, size.width + 2f, size.height + 8f, radius, radius, shadow)
    }

    // 2) 磨砂本体：顶部更实、底部略透（iOS 材质向下与内容融合）
    //    暗色版本顶部用 #2C2C2E 一档抬亮，底部回落到半透明 #1C1C1E —— 同一套「上实下透」方向
    drawRoundRect(
        brush = Brush.verticalGradient(
            0f to if (dark) Color(0xF22C2C2E) else Color(0xF2FFFFFF),
            1f to UiTokens.Surface,
        ),
        cornerRadius = CornerRadius(radius, radius),
    )

    // 3) 整圈发丝线（separator），上沿与左右一致粗细，不做「上亮下暗」的拟物描边
    drawRoundRect(
        color = UiTokens.Divider,
        cornerRadius = CornerRadius(radius, radius),
        style = Stroke(1.dp.toPx()),
    )
}

/** Apple 主题选中态：灰底胶囊（systemFill 语义），无外发光、无内凹 */
fun Modifier.applePill(shape: Shape = RoundedCornerShape(UiTokens.RadiusPill)): Modifier = drawBehind {
    val outline = shape.createOutline(size, layoutDirection, this)
    val radius = (outline as? Outline.Rounded)?.roundRect?.topLeftCornerRadius?.x ?: size.height / 2f
    drawRoundRect(
        color = UiTokens.Field,
        cornerRadius = CornerRadius(radius, radius),
    )
    drawRoundRect(
        color = UiTokens.DividerSoft,
        cornerRadius = CornerRadius(radius, radius),
        style = Stroke(1.dp.toPx()),
    )
}

/** 统一主题：按当前主题取拟物亮色 / 玻璃暗色 Material 色板 + 药丸按钮 + 负字距标题。 */
@Composable
fun WheelTheme(content: @Composable () -> Unit) {
    // 64 号：AUTO 主题跟随系统明暗。isSystemInDarkTheme 会随系统设置变化触发重组，
    // 用 SideEffect 回写全局状态（WheelOverlay 等非 Compose View 经 UiTokens 读到同一状态）。
    val systemDark = isSystemInDarkTheme()
    SideEffect { ThemeController.onSystemDarkChanged(systemDark) }

    val p = ThemeController.palette
    // 明暗按 palette.dark 判，不按渲染风格判（63 号）：Apple 暗色是 APPLE 渲染 + 暗表面，
    // 用 style == LIQUID 代判会把它送进 lightColorScheme，得到白底黑字的 Material 组件。
    val scheme = if (p.dark) {
        val opaqueBase = if (p.style == UiRenderStyle.LIQUID) Color(0xFF16213E) else Color(0xFF1C1C1E)
        darkColorScheme(
            primary = p.primary,
            onPrimary = p.primaryOn,
            primaryContainer = p.primarySoft,
            onPrimaryContainer = p.ink,
            // Material 内部组件需要不透明底色，玻璃/暗色材质感由自定义组件的半透明 token 负责
            surface = opaqueBase,
            onSurface = p.ink,
            surfaceVariant = p.surfaceElevated,
            onSurfaceVariant = p.inkSub,
            background = p.canvas,
            onBackground = p.ink,
            outline = p.divider,
            outlineVariant = p.dividerSoft,
        )
    } else {
        lightColorScheme(
            primary = p.primary,
            onPrimary = p.primaryOn,
            primaryContainer = p.primarySoft,
            onPrimaryContainer = p.primary,
            surface = p.surface,
            onSurface = p.ink,
            surfaceVariant = p.field,
            onSurfaceVariant = p.inkSub,
            background = p.canvas,
            onBackground = p.ink,
            outline = p.divider,
            outlineVariant = p.dividerSoft,
        )
    }
    MaterialTheme(
        colorScheme = scheme,
        shapes = androidx.compose.material3.Shapes(
            extraSmall = RoundedCornerShape(UiTokens.RadiusIcon),
            small = RoundedCornerShape(UiTokens.RadiusPill),
            medium = RoundedCornerShape(UiTokens.RadiusField),
            large = RoundedCornerShape(UiTokens.RadiusCard),
            extraLarge = RoundedCornerShape(UiTokens.RadiusPanel),
        ),
        typography = MaterialTheme.typography.copy(
            // 大标题：字阶由 UiType 单源定义，这里只追加负字距（Apple 纪律）
            displayLarge = UiType.display.copy(letterSpacing = (-0.4).sp),
            displayMedium = UiType.displayMd.copy(letterSpacing = (-0.35).sp),
            displaySmall = UiType.displayMd.copy(letterSpacing = (-0.3).sp),
            headlineLarge = UiType.displayMd.copy(letterSpacing = (-0.28).sp),
            headlineMedium = UiType.displayMd.copy(letterSpacing = (-0.22).sp),
            headlineSmall = UiType.title.copy(letterSpacing = (-0.2).sp),
            titleLarge = UiType.title.copy(letterSpacing = (-0.2).sp),
            titleMedium = UiType.section.copy(letterSpacing = (-0.12).sp),
            titleSmall = UiType.section.copy(letterSpacing = (-0.1).sp),
            bodyLarge = UiType.section.copy(letterSpacing = (-0.1).sp),
            // 正文/辅助字距补齐（apple DESIGN.md：body -0.374px、caption -0.224px、
            // fine-print -0.12px、micro-legal -0.08px，按 app 的 14/12/11sp 等比收敛）。
            // 此前只有大字号带负字距，中小字号无 —— 同一段里大小字松紧不一致。
            bodyMedium = UiType.body.copy(letterSpacing = (-0.28).sp),
            bodySmall = UiType.caption.copy(letterSpacing = (-0.22).sp),
            labelLarge = UiType.body.copy(fontWeight = FontWeight.Normal, letterSpacing = (-0.28).sp),
            labelMedium = UiType.caption.copy(letterSpacing = (-0.22).sp),
            labelSmall = UiType.micro.copy(letterSpacing = (-0.08).sp),
        ),
        content = {
            // 默认文字色跟随主题：画布在液态玻璃下是深色渐变，而 GlassCanvas 只是裸 Box，
            // 没有 Surface 提供内容色 —— 未显式写 color 的 Text 会退回系统默认黑字，
            // 压在深色背景上几乎不可读（设置页标题、chip 组标题都是这个问题）。
            CompositionLocalProvider(LocalContentColor provides p.ink) {
                content()
            }
        },
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
            .neuInset(RoundedCornerShape(UiTokens.RadiusPill), blur = 12f, dist = 6f, scale = depth)
            .focusRing(focused, RoundedCornerShape(UiTokens.RadiusPill))
            .padding(horizontal = UiSpace.xl),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = UiTokens.FieldHint,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(UiSpace.ms))
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
        style = UiType.body,
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
 * 新拟物开关：轨道是从底板里挖出来的一道槽 —— 上沿压暗、下沿提亮、内壁一圈暗影，
 * 开启时槽里注满强调色并透出柔光；旋钮是一枚带顶光的实体圆钮，按下会被压扁一点。
 * 整块轨道在一个 drawBehind 里分层画完，避免后续 background 盖掉内壁阴影。
 */
@Composable
fun NeuSwitch(
    checked: Boolean,
    onCheckedChange: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val isChecked = checked
    val motionOn = rememberMotionEnabled()
    // 填充色用连续进度过渡：开关切换时轨道是「注满」而不是硬切颜色
    val fill by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = if (motionOn) tween(durationMillis = 260, easing = EaseInOut) else snap(),
        label = "neu-switch-fill",
    )
    val knobX by animateDpAsState(
        targetValue = if (checked) 27.dp else 3.dp,
        animationSpec = if (motionOn) spring<Dp>(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow) else snap(),
        label = "neu-switch-knob",
    )
    // 按下时旋钮略缩小，松手弹回：触感来自形态，不靠颜色变化
    val knobScale by animateFloatAsState(
        targetValue = if (pressed) 0.86f else 1f,
        animationSpec = if (motionOn) spring(dampingRatio = 0.68f, stiffness = Spring.StiffnessMedium) else snap(),
        label = "neu-switch-knob-press",
    )

    Box(
        modifier = modifier
            .width(54.dp)
            .height(32.dp)
            .drawBehind { drawSwitchTrack(fill) }
            // UI UX Pro Max · Accessibility：自定义开关补 role=Switch + checked 状态，
            // TalkBack 才能朗读"开关、已开/已关"（此前只有填色与旋钮位置表达状态）。
            .then(
                if (onCheckedChange != null) {
                    Modifier.semantics(mergeDescendants = true) {
                        role = Role.Switch
                        stateDescription = if (isChecked) "已开启" else "已关闭"
                    }
                } else Modifier,
            )
            .then(
                if (onCheckedChange != null) {
                    Modifier.clickable(
                        interactionSource = interaction,
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
            Icon(
                Icons.Filled.Check,
                contentDescription = null, // 开关状态由父 Box 的 role/stateDescription 表达
                tint = Color.White,
                modifier = Modifier
                    .padding(start = UiSpace.ms)
                    .size(14.dp),
            )
        }
        Box(
            modifier = Modifier
                .offset(x = knobX)
                .size(26.dp)
                .graphicsLayer {
                    scaleX = knobScale
                    scaleY = knobScale
                }
                .drawBehind { drawSwitchKnob() },
        )
    }
}

/** 槽体：关闭=底板凹槽本色，开启=注满强调色；两者共用同一套明暗关系 */
private fun DrawScope.drawSwitchTrack(fill: Float) {
    val w = size.width
    val h = size.height
    val r = h / 2f
    // 关闭色按主题取（经典/新拟物各一档浅灰，玻璃=white/16）；开启时注满强调色
    val body = androidx.compose.ui.graphics.lerp(UiTokens.SwitchTrack, UiTokens.Primary, fill)

    // 槽底：上暗下亮的竖向渐变（光从上方来，槽底靠上的一段落在阴影里）
    drawRoundRect(
        brush = androidx.compose.ui.graphics.Brush.verticalGradient(
            0f to androidx.compose.ui.graphics.lerp(body, Color.Black, 0.10f),
            0.46f to body,
            1f to androidx.compose.ui.graphics.lerp(body, Color.White, 0.14f),
        ),
        cornerRadius = CornerRadius(r, r),
    )

    // 内壁：暗影贴在左上内沿，亮影贴在右下内沿 —— 槽的「厚度」全靠这一圈
    drawIntoCanvas { c ->
        val n = c.nativeCanvas
        val clip = Path().apply {
            addRoundRect(
                androidx.compose.ui.geometry.RoundRect(
                    0f, 0f, w, h, CornerRadius(r, r),
                ),
            )
        }
        n.save()
        n.clipPath(clip.asAndroidPath())
        val push = 1f * density
        val ring = 2.6f * density
        val dark = blurredPaint(Color(0x4D0F172A), 2f * density).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = ring
        }
        val light = blurredPaint(Color(0x9EFFFFFF), 1.8f * density).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = ring
        }
        val inset = ring / 2f
        n.translate(-push, -push)
        n.drawRoundRect(inset, inset, w - inset, h - inset, r, r, dark)
        n.translate(2 * push, 2 * push)
        n.drawRoundRect(inset, inset, w - inset, h - inset, r, r, light)
        n.restore()
    }

    // 槽口：上沿一道细暗线、下沿一道细亮线，让槽「咬」进底板而不是浮在上面
    drawRoundRect(
        brush = androidx.compose.ui.graphics.Brush.verticalGradient(
            0f to Color(0x14000000),
            0.45f to Color(0x08000000),
            1f to Color.White.copy(alpha = 0.90f),
        ),
        cornerRadius = CornerRadius(r, r),
        style = Stroke(1.dp.toPx()),
    )
}

/** 旋钮：偏下的柔影 + 左上顶光 + 边缘细亮线，读起来是一颗实体圆钮 */
private fun DrawScope.drawSwitchKnob() {
    val r = size.minDimension / 2f
    val cx = size.width / 2f
    val cy = size.height / 2f
    drawIntoCanvas { c ->
        // 落影往右下溢一点是物理正确的，但不能重到糊住槽壁
        val shadow = blurredPaint(Color(0x4D1B2333), 3.2f * density)
        c.nativeCanvas.drawCircle(cx, cy + 1.6f * density, r * 0.95f, shadow)
    }
    drawCircle(
        brush = androidx.compose.ui.graphics.Brush.radialGradient(
            0f to Color.White,
            0.55f to Color(0xFFF7F9FC),
            1f to Color(0xFFDDE4EE),
            center = Offset(cx - r * 0.28f, cy - r * 0.42f),
            radius = r * 2.1f,
        ),
        radius = r,
        center = Offset(cx, cy),
    )
    // 边缘一条亮线：圆钮被环境光勾出来
    drawCircle(
        color = Color.White.copy(alpha = 0.55f),
        radius = r - 0.5.dp.toPx(),
        center = Offset(cx, cy),
        style = Stroke(1.dp.toPx()),
    )
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
    val shape = RoundedCornerShape(UiTokens.RadiusPill)
    if (primary && enabled) {
        // 主要动作：实心强调色药丸（DESIGN.md：药丸 = 唯一动作信号，主按钮不投影），
        // 按下 scale 0.95 是系统级微交互（transform: scale(0.95)），全主题统一。
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val motionOn = rememberMotionEnabled()
        // 63 号：按压非对称节奏（按下 90ms / 松手 180ms，参数统一在 Motion）
        val scale by animateFloatAsState(
            targetValue = if (pressed && motionOn) Motion.PressScale else 1f,
            animationSpec = if (pressed) Motion.pressIn() else Motion.pressOut(),
            label = "btn-press",
        )
        Box(
            modifier = modifier
                .height(44.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .background(UiTokens.Primary, shape)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    // 37 号改动（S9）：primary 分支此前不传 enabled，恒可点靠外层 if 隐式保证
                    enabled = enabled,
                    onClick = onClick,
                )
                .padding(horizontal = UiSpace.xl),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text,
                // 63 号：主按钮文字色取 token（暗色 Apple 的 PrimaryOn 是黑，压 #2997FF 才够 7.2:1；
                // 此前硬编码 Color.White，暗色主题下白字压亮蓝只有 2.9:1）
                color = UiTokens.PrimaryOn,
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
                .padding(horizontal = UiSpace.xl),
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
    /** 37 号改动（S9）：可用性收敛到单一响应式参数，调用侧不再自己 alpha+守卫双写 */
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(UiTokens.RadiusPill)
    val isSelected = selected
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
            // UI UX Pro Max · Accessibility：分段选择补 role + selected 状态，
            // TalkBack 才能读出「选项、已选中/未选中」。仅加语义、不改尺寸/间距 ——
            // 主题 chip 此前专门修过 FlowRow 换行重叠，动热区高度会让布局回归。
            .semantics(mergeDescendants = true) sel@{
                role = Role.RadioButton
                this@sel.selected = isSelected
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = UiSpace.ml),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            // 37 号改动（S9）：置灰态文字一并降级，视觉与行为同源
            color = when {
                !enabled -> UiTokens.FieldHint
                selected -> UiTokens.Primary
                else -> UiTokens.InkSub
            },
        )
    }
}

/**
 * A–Z 索引条（单源组件，37 号改动 T2/C1）。
 * 此前 AppSheetActivity「更多」面板与 AppsTab 应用页各持一份逐行相同的实现（约 70 行 × 2）：
 * 样式、命中、拖动滚动口径全同，注释互相引用但代码不共用，一处改档另一处必漂移。
 * 抽出后：字形走 [UiType.indexGlyph]；选中高亮用 Ink（浅色画布对比 ≈11:1，
 * 此前用 primary 对底衬只有 3.6:1、AA 不过——37 号改动 T3 一并修复）。
 * 字母等分铺满调用方给定的固定高度。
 *
 * 39 号改动（B1b）：Column 显式定宽 28dp。此前没有任何宽度约束，而每个字母格是
 * `weight(1f).fillMaxWidth()`——Compose 里 fillMaxWidth 会顶穿 wrap-content 父容器，
 * 整条索引被撑成全列表宽：字母被居中画在屏幕水平正中、压住中列图标，
 * 且整个带区吞掉纵向拖动手势（列表中段无法滚动）。定宽后索引条贴右缘收成 28dp 窄条，
 * 点按/拖动只发生在条上，列表其余区域正常滚动。
 */
@Composable
fun AlphabetIndexBar(
    letters: List<String>,
    /** 拖动/点按选中一个字母（调用方负责把列表滚到对应分组） */
    onLetterSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var activeLetter by remember { mutableStateOf("") }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .width(28.dp)
            .fillMaxHeight()
            .pointerInput(letters) {
                fun pickAt(y: Float) {
                    val idx = (y / size.height * letters.size).toInt().coerceIn(0, letters.lastIndex)
                    val letter = letters[idx]
                    if (letter != activeLetter) {
                        activeLetter = letter
                        onLetterSelected(letter)
                        if (Prefs.haptic(context)) Haptics.tick(context)
                    }
                }
                detectVerticalDragGestures(
                    onDragStart = { pickAt(it.y) },
                    onDragEnd = { activeLetter = "" },
                    onDragCancel = { activeLetter = "" },
                ) { change, _ -> pickAt(change.position.y) }
            },
    ) {
        letters.forEach { letter ->
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    letter,
                    style = UiType.indexGlyph,
                    fontWeight = if (letter == activeLetter) FontWeight.Bold else FontWeight.Normal,
                    color = if (letter == activeLetter) UiTokens.Ink else UiTokens.InkSub,
                    modifier = Modifier
                        .padding(horizontal = UiSpace.xs)
                        .clickable { onLetterSelected(letter) },
                )
            }
        }
    }
}

/**
 * A–Z 索引条叠层（38 号改动 B1）：调用方只需在 [androidx.compose.foundation.layout.Box]
 * 作用域里调一次，内部统一「贴右缘、纵向撑满列表区」的摆位。
 * 此前两个调用点各写一个 240dp 固定高 + CenterEnd 的盒子，索引条悬在屏幕垂直居中位置，
 * 恰好压在 3 列网格右列图标上；改为撑满列表区后字母沿右缘全高分布，
 * 配合调用方给列表留出的右缘避让区，图标与字母不再重叠。
 */
@Composable
fun BoxScope.AlphabetIndexOverlay(
    letters: List<String>,
    /** 拖动/点按选中一个字母（调用方负责把列表滚到对应分组） */
    onLetterSelected: (String) -> Unit,
) {
    Box(
        modifier = Modifier
            .align(Alignment.CenterEnd)
            .padding(end = UiSpace.xxs)
            .fillMaxHeight(),
        contentAlignment = Alignment.Center,
    ) {
        AlphabetIndexBar(letters = letters, onLetterSelected = onLetterSelected)
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
            // UI UX Pro Max · Accessibility：自定义滑块补 ValuePicker role + 进度范围 + setProgress，
            // TalkBack 才能读出当前值并左右滑动调整（此前纯 pointerInput，读不到也操作不了）。
            // 注：Compose 1.12.1 无 Role.Slider，用 ValuePicker + progressBarRangeInfo 表达。
            .semantics(mergeDescendants = true) {
                role = Role.ValuePicker
                progressBarRangeInfo = ProgressBarRangeInfo(value, valueRange, 0)
                stateDescription = "当前 ${value.toInt()}，范围 ${valueRange.start.toInt()} 到 ${valueRange.endInclusive.toInt()}"
                setProgress("调整数值") { newValue ->
                    onValueChange(newValue.coerceIn(valueRange))
                    true
                }
            }
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
                .neuInset(RoundedCornerShape(UiTokens.RadiusPill), blur = 8f, dist = 4f),
        )
        // 已选段（比槽略窄，视觉上「流在槽里」）
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(10.dp)
                .clip(RoundedCornerShape(UiTokens.RadiusPill))
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
