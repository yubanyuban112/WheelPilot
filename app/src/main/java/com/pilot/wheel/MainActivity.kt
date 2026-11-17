package com.pilot.wheel

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.content.pm.ApplicationInfo
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color as GlassColor
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import priv.kit.ui.PrivilegeScaffold

/** 快捷方式（含 AI 键可调用的那种）统一使用的 action */
const val ACTION_OPEN_WHEEL = "com.pilot.wheel.OPEN_WHEEL"

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // AI 键、桌面长按快捷方式、系统快捷方式唤起时走这三个 action 之一。
        // 只借这个入口把轮盘拉起来，随即关掉自己，用户看到的就只有轮盘。
        if (intent?.action == Intent.ACTION_ASSIST || intent?.action == ACTION_OPEN_WHEEL) {
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            PilotService.instance?.previewWheel()
            finish()
            return
        }

        setContent {
            WheelTheme {
                GlassCanvas(modifier = Modifier.fillMaxSize()) {
                    var showPrivilege by remember { mutableStateOf(false) }

                    BackHandler(enabled = showPrivilege) { showPrivilege = false }

                    if (showPrivilege) {
                        // priv.kit 是闭源 AAR，内部样式改不了；把整块内容装进新拟物
                        // 圆角凸起面板里，背景/圆角/留白与本应用统一，消除风格断层
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(UiTokens.Canvas)
                                .systemBarsPadding()
                                .padding(12.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .neuRaised(RoundedCornerShape(UiTokens.RadiusPanel), blur = 24f, dist = 10f),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(UiTokens.RadiusPanel))
                                        .background(UiTokens.Canvas),
                                ) {
                                    // priv.kit 内部按钮的形状来自主题 shapes.small（此前是药丸 50），
                                    // M3 阴影沿药丸渲染成突兀的灰色椭圆 —— 这里覆写为普通圆角矩形，
                                    // 阴影随之变成与卡片一致的圆角矩形；配色也统一到本应用
                                    androidx.compose.material3.MaterialTheme(
                                        colorScheme = androidx.compose.material3.lightColorScheme(
                                            primary = UiTokens.Primary,
                                            onPrimary = UiTokens.PrimaryOn,
                                            surface = UiTokens.Canvas,
                                            onSurface = UiTokens.Ink,
                                            background = UiTokens.Canvas,
                                            onBackground = UiTokens.Ink,
                                            surfaceVariant = UiTokens.Field,
                                            onSurfaceVariant = UiTokens.InkSub,
                                        ),
                                        shapes = androidx.compose.material3.Shapes(
                                            extraSmall = RoundedCornerShape(10.dp),
                                            small = RoundedCornerShape(14.dp),
                                            medium = RoundedCornerShape(18.dp),
                                            large = RoundedCornerShape(22.dp),
                                            extraLarge = RoundedCornerShape(24.dp),
                                        ),
                                    ) {
                                        PrivilegeScaffold(viewModel = viewModel<PilotPrivilegeViewModel>())
                                    }
                                }
                            }
                        }
                    } else {
                        MainTabs(onOpenPrivilege = { showPrivilege = true })
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_ASSIST || intent.action == ACTION_OPEN_WHEEL) {
            PilotService.instance?.previewWheel()
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        // 「后台隐藏」做成运行时开关：excludeFromRecents 是 Manifest 静态属性，
        // 只能用 RecentTaskInfo.setExcludeFromRecents 在运行期切换
        runCatching {
            val am = getSystemService(ActivityManager::class.java)
            val exclude = Prefs.hideFromRecents(this)
            am.appTasks.forEach { it.setExcludeFromRecents(exclude) }
        }
    }
}

// ---------------------------------------------------------------------------
// 底部导航：小窗 / 应用 / 设置 三个页签
// ---------------------------------------------------------------------------

@Composable
private fun MainTabs(onOpenPrivilege: () -> Unit) {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf(Prefs.load(context)) }
    var accessible by remember { mutableStateOf(isAccessibilityEnabled(context)) }
    // 启动提速：默认填充只需判断包名是否存在，用轻量查询，不加载任何图标
    val installedPkgs = remember { loadInstalledPackageNames(context) }

    // togglePin / confirmRemove：contains 决定增删，
    // 上限先拦截并提示，成功写入带 toast，触觉反馈由系统点击音代替
    fun toggleApp(entry: AppEntry) {
        val pkg = entry.pkg
        if (pkg in selected) {
            selected = selected - pkg
            Prefs.save(context, selected)
            Toast.makeText(context, "已从轮盘移除「${entry.label}」", Toast.LENGTH_SHORT).show()
        } else {
            if (selected.size >= Prefs.MAX_APPS) {
                Toast.makeText(context, "轮盘只有 ${Prefs.MAX_APPS} 个位置，先移除一个", Toast.LENGTH_SHORT).show()
                return
            }
            selected = selected + pkg
            Prefs.save(context, selected)
            Toast.makeText(context, "已放到轮盘第 ${selected.size} 位", Toast.LENGTH_SHORT).show()
        }
    }

    /** 应用页顶部的快捷工具：点一下进出轮盘 */
    fun toggleToolKey(key: String, label: String) {
        if (key in selected) {
            selected = selected - key
            Prefs.save(context, selected)
            Toast.makeText(context, "已从轮盘移除「$label」", Toast.LENGTH_SHORT).show()
        } else {
            if (selected.size >= Prefs.MAX_APPS) {
                Toast.makeText(context, "轮盘只有 ${Prefs.MAX_APPS} 个位置，先移除一个", Toast.LENGTH_SHORT).show()
                return
            }
            selected = selected + key
            Prefs.save(context, selected)
            Toast.makeText(context, "已放到轮盘第 ${selected.size} 位", Toast.LENGTH_SHORT).show()
        }
        PilotService.instance?.refreshWheel()
    }

    fun reorder(newOrder: List<String>) {
        selected = newOrder
        Prefs.save(context, newOrder)
        // 轮盘读的是 Prefs，这里只需要让「正开着」的轮盘立刻重建
        PilotService.instance?.refreshWheel()
        PilotLog.d(context, "WheelPilot", "reorder -> ${newOrder.joinToString()}")
        Toast.makeText(context, "顺序已保存", Toast.LENGTH_SHORT).show()
    }

    // 对齐 AppRepo.ensureDefaults：首次进入从常用列表挑已安装应用填充轮盘
    if (!Prefs.appsInitialized(context) && selected.isEmpty()) {
        val installed = installedPkgs
        val favorites = listOf(
            "com.tencent.mm", "com.eg.android.AlipayGphone",
            "com.ss.android.ugc.aweme", "tv.danmaku.bili",
            "com.tencent.mobileqq", "com.xingin.xhs",
            "com.netease.cloudmusic", "com.sina.weibo",
        )
        val defaults = favorites.filter { it in installed }.take(Prefs.MAX_APPS)
        if (defaults.isNotEmpty()) {
            Prefs.save(context, defaults)
            selected = Prefs.load(context)
        } else {
            Prefs.setAppsInitialized(context, true)
        }
    }

    LifecycleResumeEffect(Unit) {
        accessible = isAccessibilityEnabled(context)
        selected = Prefs.load(context)
        onPauseOrDispose { }
    }

    Scaffold(
        containerColor = GlassColor.Transparent,
        bottomBar = {
            GlassBottomBar(tab = tab, onTab = { tab = it })
        },
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            // 页面切换：带方向感的滑动 + 淡入。不用缩放 —— scaleIn 会让带阴影的
            // 面板边缘在缩放中闪现成「方框」；弹簧取临界阻尼（不过冲）+ 缓入缓出淡入，
            // 进出节奏一致（280/260ms），过渡平滑无残影
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    val dir = if (targetState > initialState) 1 else -1
                    (fadeIn(animationSpec = tween(260, easing = androidx.compose.animation.core.FastOutSlowInEasing)) +
                        slideInHorizontally(
                            animationSpec = spring<IntOffset>(dampingRatio = 1f, stiffness = 350f),
                            initialOffsetX = { dir * it / 5 },
                        )) togetherWith
                        (fadeOut(animationSpec = tween(180, easing = androidx.compose.animation.core.FastOutSlowInEasing)) +
                            slideOutHorizontally(
                                animationSpec = tween(180, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                                targetOffsetX = { -dir * it / 6 },
                            ))
                },
                label = "main-tab",
            ) { currentTab ->
                when (currentTab) {
                    0 -> HomeTab(
                        accessible = accessible,
                        selectedCount = selected.size,
                        onOpenPrivilege = onOpenPrivilege,
                    )
                    1 -> AppsTab(
                        selected = selected,
                        onToggle = { entry -> toggleApp(entry) },
                        onReorder = { order -> reorder(order) },
                        onToggleTool = { key ->
                            val label = QuickTools.tool(QuickTools.toolIdOf(key))?.label ?: "工具"
                            toggleToolKey(key, label)
                        },
                    )
                    else -> SettingsScreen(onOpenPrivilege = onOpenPrivilege)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 玻璃底栏（beUI dock）：玻璃条 + 弹簧滑动药丸指示器 + 按压反馈。
// 药丸用 animateDpAsState(GlassMotion.slide()) 在三个页签间滑动（layoutId 等效），
// 按下即响应、弹簧回位，替代 M3 NavigationBarItem 的静态 indicator。
// ---------------------------------------------------------------------------

private val TAB_ICONS = listOf(Icons.Filled.Home, Icons.Filled.List, Icons.Filled.Settings)
private val TAB_LABELS = listOf("小窗", "应用", "设置")

@Composable
private fun GlassBottomBar(tab: Int, onTab: (Int) -> Unit) {
    val context = LocalContext.current
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val cellWidth = maxWidth / TAB_LABELS.size
        // 药丸指示器：目标格随页签变化，弹簧滑动过去
        val pillX by animateDpAsState(
            targetValue = cellWidth * tab,
            animationSpec = androidx.compose.animation.core.spring<Dp>(
                dampingRatio = 0.9f,
                stiffness = Spring.StiffnessMedium,
            ),
            label = "dock-pill",
        )
        Column {
            // 底栏本体：凸起软垫条（顶部圆角），替代原先的同色平面
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .neuRaised(
                        RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
                        blur = 14f,
                        dist = 6f,
                    )
                    .height(68.dp),
            ) {
                // 滑动药丸：内凹垫包住选中页图标（内凹 = 已激活），弹簧滑动。
                // 药丸与图标徽标共用圆心：y 6..46 的圆心 26dp == 徽标圆心，
                // 水平方向按格宽居中，切换时不会出现图标在药丸里偏移的观感
                Box(
                    modifier = Modifier
                        .offset(
                            x = pillX + (cellWidth - 52.dp) / 2,
                            y = 6.dp,
                        )
                        .size(width = 52.dp, height = 40.dp)
                        .neuInset(RoundedCornerShape(50), blur = 10f, dist = 5f),
                )
                Row(modifier = Modifier.fillMaxSize()) {
                    TAB_LABELS.forEachIndexed { index, label ->
                        val selected = tab == index
                        // 选中态颜色柔韧过渡（300ms ease-in-out），不再瞬切
                        val tint by animateColorAsState(
                            targetValue = if (selected) UiTokens.Primary else UiTokens.FieldHint,
                            animationSpec = GlassMotion.moldingColor(),
                            label = "dock-tint",
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                // 去掉 Material 默认矩形涟漪（切换时的「方框」），
                                // 选中态由内凹药丸 + 颜色过渡表达
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) {
                                    if (Prefs.haptic(context)) Haptics.select(context)
                                    onTab(index)
                                },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            // 固定几何：徽标顶部 9dp、直径 34dp → 圆心 26dp，
                            // 与滑动药丸圆心重合；文字固定悬浮在药丸下方，
                            // 选中/未选中布局完全一致，切换时图标不跳动
                            Spacer(Modifier.height(9.dp))
                            // 未选中：凸起软垫圆底上的图标；选中：裸图标坐在内凹药丸里
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .then(
                                        if (!selected) {
                                            Modifier.neuRaised(CircleShape, blur = 8f, dist = 4f)
                                        } else {
                                            Modifier
                                        },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = TAB_ICONS[index],
                                    contentDescription = label,
                                    tint = tint,
                                    modifier = Modifier.size(if (selected) 24.dp else 20.dp),
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                label,
                                style = MaterialTheme.typography.labelSmall,
                                color = tint,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }
    }
}

