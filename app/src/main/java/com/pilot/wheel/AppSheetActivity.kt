package com.pilot.wheel

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 轮盘「更多」按钮弹出的居中小窗。版面结构：
 *  - 顶部拖拽条 + 圆形关闭按钮 + 居中的「应用 / 工具」页签（图一图二）；
 *  - 应用页：搜索框 →「已选」圆形图标横排 → 分隔线 → 最近使用 / 拼音字母分组（每行 4 个，图标 + 名称）；
 *  - 工具页：「快捷工具」两列横向卡片，左侧圆形彩色图标 + 右侧主副标题（图一）；
 *  - 左右滑动或点页签切换，底部有分页圆点。
 * 保留本项目的启动小窗、最近使用记录等逻辑，仅重构展示与操作路径。
 */
class AppSheetActivity : ComponentActivity() {

    private val ui = android.os.Handler(android.os.Looper.getMainLooper())

    /** 手电筒要相机权限，首次点击现申请，授权后接着执行 */
    private var pendingTorch = false

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!pendingTorch) return@registerForActivityResult
        pendingTorch = false
        if (granted) {
            dispatchTool(QuickTools.TORCH)
        } else {
            Toast.makeText(this, "没有相机权限，手电筒用不了", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeController.load(this)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        intent?.getStringExtra(EXTRA_PICK_TOOL)?.let { toolId ->
            pickTarget = QuickTools.pickTarget(toolId)
        }

        setContent {
            WheelTheme {
                MoreSheet(onFinish = { finish() })
            }
        }
    }

    /**
     * 执行工具。返回 false 表示被权限流程接管，面板先别关。
     * 全部同步执行完再收面板：旧实现先 finish 再跑协程，Activity 销毁后
     * lifecycleScope 被取消，锁屏/扫码这些动作实际没发出去。
     */
    private fun runTool(tool: QuickTools.Tool): Boolean {
        if (tool.id == QuickTools.TORCH && QuickTools.needsCameraPermission(this)) {
            pendingTorch = true
            cameraPermission.launch(Manifest.permission.CAMERA)
            return false
        }
        dispatchTool(tool.id)
        return true
    }

    /** 云闪付/微信自动匹配失败时，把真实 Activity 列表摆给用户挑一次 */
    private var pickTarget by androidx.compose.runtime.mutableStateOf<QuickTools.PickTarget?>(null)

    companion object {
        /** 轮盘里点工具、自动匹配失败时，通过这个 extra 让面板直接弹选择器 */
        const val EXTRA_PICK_TOOL = "pick_tool"
    }

    private fun dispatchTool(id: String) {
        // 这几项要同步拿结果：匹配失败时不关面板，直接弹入口选择
        val pickable = id == QuickTools.UNIONPAY_SCAN ||
            id == QuickTools.UNIONPAY_PAY ||
            id == QuickTools.WECHAT_PAY
        if (pickable) {
            App.workScope.launch {
                val message = QuickTools.run(this@AppSheetActivity, id)
                if (message.startsWith(QuickTools.NEED_PICK + ":")) {
                    pickTarget = QuickTools.pickTarget(message.substringAfter(':'))
                    return@launch
                }
                if (message.isNotBlank()) {
                    Toast.makeText(this@AppSheetActivity, message, Toast.LENGTH_SHORT).show()
                }
                ui.postDelayed({ if (!isFinishing) finish() }, 120L)
            }
            return
        }
        // 其余工具先让面板退场再执行：截屏/识屏如果立刻做，截到/按到的是面板自己。
        // 用 applicationContext：Activity 已经 finish，不能再用它启动外部界面。
        ui.postDelayed({ if (!isFinishing) finish() }, 30L)
        App.workScope.launch {
            kotlinx.coroutines.delay(320L)
            val message = QuickTools.run(applicationContext, id)
            if (message.isNotBlank()) {
                Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun launchApp(pkg: String) {
        val mode = Prefs.launchMode(this)
        Prefs.noteRecent(this, pkg)
        // 用 Application 级 scope：这里紧接着就 finish，Activity scope 会被取消
        App.workScope.launch {
            val result = AppLauncher.launch(this@AppSheetActivity, pkg, mode)
            if (result.startsWith("shell-exit") || result.startsWith("shell-error") ||
                result.startsWith("intent-failed") || result.startsWith("shell-blocked")
            ) {
                Toast.makeText(
                    this@AppSheetActivity, "启动失败：$pkg", Toast.LENGTH_SHORT,
                ).show()
            }
        }
        finish()
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun MoreSheet(onFinish: () -> Unit) {
        val context = LocalContext.current
        var query by remember { mutableStateOf("") }
        var debounced by remember { mutableStateOf("") }
        var tab by remember { mutableStateOf(0) }
        var visible by remember { mutableStateOf(true) }
        val openedAt = remember { SystemClock.uptimeMillis() }
        val motionOn = rememberMotionEnabled()
        val pagerState = rememberPagerState(pageCount = { 2 })
        val scope = rememberCoroutineScope()

        // 轮盘当前条目：工具也能占位，key 为 tool:<id>
        var wheelKeys by remember { mutableStateOf(Prefs.load(context)) }
        // 与应用页联动：从别的页面改了轮盘（应用/工具勾选），回到面板自动同步最新值
        LifecycleResumeEffect(Unit) {
            wheelKeys = Prefs.load(context)
            onPauseOrDispose { }
        }

        fun toggleTool(tool: QuickTools.Tool) {
            val key = QuickTools.toolKey(tool.id)
            if (key in wheelKeys) {
                wheelKeys = wheelKeys - key
                Prefs.save(context, wheelKeys)
                Toast.makeText(context, "已从轮盘移除「${tool.label}」", Toast.LENGTH_SHORT).show()
            } else {
                if (wheelKeys.size >= Prefs.slots(context)) {
                    Toast.makeText(
                        context, "轮盘只有 ${Prefs.slots(context)} 个位置，先移除一个", Toast.LENGTH_SHORT,
                    ).show()
                    return
                }
                wheelKeys = wheelKeys + key
                Prefs.save(context, wheelKeys)
                Toast.makeText(context, "已放到轮盘第 ${wheelKeys.size} 位", Toast.LENGTH_SHORT).show()
            }
        }

        // 关闭时先播退场动画，再真正 finish，避免面板「啪」一下消失
        fun closeSheet() {
            if (!visible) return
            visible = false
            scope.launch {
                delay(GlassMotion.EXIT_MS + 30L)
                onFinish()
            }
        }

        LaunchedEffect(query) {
            delay(110L)
            debounced = query
        }
        LaunchedEffect(pagerState.currentPage) {
            tab = pagerState.currentPage
        }

        BackHandler { closeSheet() }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    if (SystemClock.uptimeMillis() - openedAt >= 350L) closeSheet()
                },
            contentAlignment = Alignment.Center,
        ) {
            // 面板遮罩：只有「更多」面板有，轮盘本身仍然无遮罩
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // 37 号改动（M5）：遮罩色收进 token，不再硬编码
                    .background(UiTokens.Scrim)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        if (SystemClock.uptimeMillis() - openedAt >= 350L) closeSheet()
                    },
            )

            AnimatedVisibility(
                visible = visible,
                // Apple sheet 官方弹簧（damping 0.8 / response 0.3s）叠加 5-8% 过冲（beUI 面板 morph），
                // 出场进慢出快：快速淡出 + 微缩，退场时长对齐 GlassMotion.EXIT_MS
                // UI UX Pro Max · reduced-motion：减弱动效开启时，进出场时长退化为 0（直接呈现）
                enter = if (!motionOn) {
                    fadeIn(animationSpec = snap())
                } else {
                    fadeIn(animationSpec = tween(160)) +
                        slideInVertically(
                            initialOffsetY = { it / 6 },
                            animationSpec = androidx.compose.animation.core.spring<IntOffset>(
                                dampingRatio = 0.72f,
                                stiffness = 380f,
                            ),
                        ) +
                        scaleIn(initialScale = 0.96f, animationSpec = GlassMotion.overshoot())
                },
                exit = if (!motionOn) {
                    fadeOut(animationSpec = snap())
                } else {
                    fadeOut(animationSpec = tween(GlassMotion.EXIT_MS)) +
                        scaleOut(targetScale = 0.96f, animationSpec = tween(GlassMotion.EXIT_MS))
                },
            ) {
                // 玻璃面板：白渐变 + 高光描边 + 柔影，替代不透明 Surface
                // 尺寸对齐系统小窗：约 0.86 屏宽 × 0.68 屏高，不再写死 440×620dp
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.86f)
                        .fillMaxHeight(0.68f)
                        .widthIn(max = 560.dp)
                        .neuRaised(
                            RoundedCornerShape(UiTokens.RadiusPanel),
                            blur = 28f,
                            dist = 12f,
                            body = UiTokens.SurfaceElevated,
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { },
                ) {
                    // 必须用 fillMaxSize 给 weight 一个确定的总高度，否则 HorizontalPager
                    // 高度塌陷为 0：应用页列表不显示、工具页内容溢出并透出相邻页图标。
                    Column(modifier = Modifier.fillMaxSize()) {
                        // 顶部拖拽条（图一图二）
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .padding(top = UiSpace.ms, bottom = UiSpace.xxs)
                                .size(width = 36.dp, height = 4.dp)
                                .clip(RoundedCornerShape(UiTokens.IndicatorRadius))
                                .background(UiTokens.Divider),
                        )

                        // 圆形关闭按钮 + 居中的「应用 / 工具」页签
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = UiSpace.ml, vertical = UiSpace.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    // UI UX Pro Max · Accessibility：关闭按钮补 role + 中文标签，
                                    // TalkBack 读"关闭 按钮"而非"乘号"（符号本身的替换属视觉范畴，未改）。
                                    .semantics(mergeDescendants = true) {
                                        role = Role.Button
                                        contentDescription = "关闭"
                                    }
                                    .neuPressable(shape = CircleShape, blur = 10f, dist = 5f) {
                                        if (Prefs.haptic(context)) Haptics.select(context)
                                        closeSheet()
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = null, // 语义已在父 Box 的 "关闭" 标签
                                tint = UiTokens.InkSub,
                                modifier = Modifier.size(20.dp),
                            )
                            }
                            // 应用/工具页签：凸起药丸容器 + 内凹滑动药丸（与底栏同一套语言）。
                            // 药丸位置由实际 cell 宽度推导（maxWidth/2），不再硬编码，保证与文字永远对中
                            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                androidx.compose.foundation.layout.BoxWithConstraints(
                                    modifier = Modifier
                                        // 37 号改动（M4）：固定宽改下限，fontScale 大字号时按内容撑开不截断
                                        .widthIn(min = 190.dp)
                                        .height(36.dp)
                                        .neuRaised(RoundedCornerShape(UiTokens.RadiusPill), blur = 10f, dist = 5f),
                                ) {
                                    val cellW = maxWidth / 2
                                    val pillW = cellW - 6.dp
                                    val pillX by androidx.compose.animation.core.animateDpAsState(
                                        targetValue = if (tab == 0) 3.dp else cellW + 3.dp,
                                        animationSpec = if (motionOn) {
                                            androidx.compose.animation.core.spring<Dp>(
                                                dampingRatio = 0.9f,
                                                stiffness = Spring.StiffnessMedium,
                                            )
                                        } else snap(),
                                        label = "sheet-tab-pill",
                                    )
                                    Box(
                                        modifier = Modifier
                                            .offset(x = pillX, y = 3.dp)
                                            .size(width = pillW, height = 30.dp)
                                            .neuInset(RoundedCornerShape(UiTokens.RadiusPill), blur = 8f, dist = 4f),
                                    )
                                    Row(modifier = Modifier.fillMaxSize()) {
                                        SheetTab(
                                            "应用",
                                            selected = tab == 0,
                                            modifier = Modifier.weight(1f),
                                        ) {
                                            scope.launch { pagerState.animateScrollToPage(0) }
                                        }
                                        SheetTab(
                                            "工具",
                                            selected = tab == 1,
                                            modifier = Modifier.weight(1f),
                                        ) {
                                            scope.launch { pagerState.animateScrollToPage(1) }
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.size(34.dp))
                        }

                        // 内容区 + 叠在底部的分页指示器。
                        // 3.18.1：指示器从「布局里的一行」改成「压在内容底部的一小块」——
                        // 旧的 44dp 触控热区 + 上下 12dp 留白一共吃掉约 68dp 高度，
                        // 把列表最后一行直接挤出可视区，看起来像「分页切换挡住了内容」。
                        // 3.19.0：内容区必须裁到面板底部圆角。HorizontalPager 的背景是一条
                        // 直角矩形，铺满整块内容区；面板本身只在 neuRaised 里画圆角、并不裁剪子级，
                        // 于是矩形的两个下角从圆角外面露出来 —— 就是底部那道「方形边框」。
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .clip(
                                    RoundedCornerShape(
                                        topStart = 0.dp,
                                        topEnd = 0.dp,
                                        bottomEnd = UiTokens.RadiusPanel,
                                        bottomStart = UiTokens.RadiusPanel,
                                    ),
                                ),
                        ) {
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier
                                .fillMaxSize()
                                .background(UiTokens.Surface),
                        ) { page ->
                            // 滑动/切页时非当前页轻微淡出，消除硬切换感；
                            // graphicsLayer 块在 draw 阶段读取 state，滚动时不额外重组
                            val pageDist = kotlin.math.abs(
                                pagerState.currentPage + pagerState.currentPageOffsetFraction - page,
                            )
                            // 每页铺满并给接近不透明的底色，防止滑动/溢出时
                            // 透出相邻页内容（此前高度塌陷 bug 的防护），同时保留拟物质感
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(UiTokens.Surface)
                                    .graphicsLayer {
                                        alpha = 1f - 0.35f * pageDist.coerceIn(0f, 1f)
                                    },
                            ) {
                                if (page == 0) {
                                    AppsPage(
                                        query = query,
                                        debounced = debounced,
                                        onQueryChange = { query = it },
                                        onLaunch = { pkg -> launchApp(pkg) },
                                        wheelKeys = wheelKeys,
                                    )
                                } else {
                                    ToolsPage(
                                        selected = wheelKeys,
                                        onRun = { tool -> runTool(tool) },
                                        onToggleWheel = { tool -> toggleTool(tool) },
                                    )
                                }
                            }
                        }

                        // 分页指示器：药丸底 + 当前页拉长的胶囊，叠在列表底部不占高度。
                        // 列表末尾留了 24dp 余量，最后一行不会被这一小块压住。
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = UiSpace.xxs)
                                .neuRaised(
                                    RoundedCornerShape(UiTokens.RadiusPill),
                                    blur = 6f,
                                    dist = 3f,
                                    body = UiTokens.Surface.copy(alpha = 0.92f),
                                )
                                .padding(horizontal = UiSpace.sm, vertical = 3.dp),
                            horizontalArrangement = Arrangement.spacedBy(UiSpace.xxs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            repeat(2) { index ->
                                val on = tab == index
                                Box(
                                    modifier = Modifier
                                        .size(width = 24.dp, height = 14.dp)
                                        // UI UX Pro Max · Accessibility：补「第 N 页，共 2 页」朗读
                                        .semantics(mergeDescendants = true) dot@{
                                            role = Role.Tab
                                            this@dot.selected = on
                                            contentDescription = "第 ${index + 1} 页，共 2 页"
                                        }
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                        ) {
                                            if (Prefs.haptic(context)) Haptics.select(context)
                                            scope.launch { pagerState.animateScrollToPage(index) }
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(width = if (on) 14.dp else 6.dp, height = 6.dp)
                                            .clip(RoundedCornerShape(UiTokens.RadiusPill))
                                            .background(if (on) UiTokens.Primary else UiTokens.Divider),
                                    )
                                }
                            }
                        }
                        } // Box（内容 + 指示器）
                    }
                }
            }
        }

        // 自动匹配失败：把真实 Activity 列表摆出来让用户挑一次
        pickTarget?.let { target ->
            ComponentPicker(
                target = target,
                onDismiss = { pickTarget = null },
                onPick = { cls ->
                    QuickTools.setToolComponent(context, target.toolId, cls)
                    pickTarget = null
                    App.workScope.launch {
                        val message = QuickTools.run(applicationContext, target.toolId)
                        if (message.isNotBlank() && !message.startsWith(QuickTools.NEED_PICK)) {
                            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
                        }
                    }
                    ui.postDelayed({ if (!isFinishing) finish() }, 220L)
                },
            )
        }
    }

    /** 入口选择器：列出目标包的 exported Activity，选一次就记住 */
    @Composable
    private fun ComponentPicker(
        target: QuickTools.PickTarget,
        onDismiss: () -> Unit,
        onPick: (String) -> Unit,
    ) {
        val context = LocalContext.current
        val names = remember(target) { QuickTools.activityNames(context, target.pkg, target.keywords) }
        // 37 号改动（S2/1-2）：此前 confirmButton 槽放的是「取消」且缺 dismissButton，语义倒挂。
        // 现改为「列表点选 → 确定」两步：confirm=确定（主色，选中前置灰），dismiss=取消。
        var picked by remember(target) { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = UiTokens.DialogSurface,
            title = { Text("选择${target.title}入口") },
            text = {
                Column {
                    Text(
                        "自动匹配没找到。点一个能到${target.title}页面的入口，下次直接用它。",
                        style = MaterialTheme.typography.bodySmall,
                        color = UiTokens.InkSub,
                    )
                    Spacer(Modifier.height(UiSpace.ms))
                    if (names.isEmpty()) {
                        Text(
                            "读不到页面列表，请确认已安装并授予应用列表权限。",
                            style = MaterialTheme.typography.bodySmall,
                            color = UiTokens.InkSub,
                        )
                    }
                    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        items(names) { name ->
                            Text(
                                name.removePrefix("${target.pkg}."),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (picked == name) UiTokens.Primary else UiTokens.Ink,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    // UI UX Pro Max · Accessibility：选择器列表项补 role=Button + ≥44dp 热区
                                    .semantics(mergeDescendants = true) { role = Role.Button }
                                    .clickable { picked = name }
                                    .heightIn(min = UiTokens.GridIcon)
                                    .padding(vertical = UiSpace.ms),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                NeuButton(text = "确定", primary = true, enabled = picked != null) {
                    picked?.let(onPick)
                }
            },
            dismissButton = {
                NeuButton(text = "取消") { onDismiss() }
            },
        )
    }

    /** 页签文字：占满所在 cell 并水平垂直双居中，保证与内凹药丸永远对齐 */
    @Composable
    private fun SheetTab(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
        val context = LocalContext.current
        Box(
            modifier = modifier
                .fillMaxHeight()
                // UI UX Pro Max · Accessibility：页签补 role=Tab + selected 状态
                .semantics(mergeDescendants = true) tab@{
                    role = Role.Tab
                    this@tab.selected = selected
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    if (Prefs.haptic(context)) Haptics.select(context)
                    onClick()
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) UiTokens.Ink else UiTokens.InkSub,
            )
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun AppsPage(
        query: String,
        debounced: String,
        onQueryChange: (String) -> Unit,
        onLaunch: (String) -> Unit,
        wheelKeys: List<String>,
    ) {
        val context = LocalContext.current
        // 图标解码放到 IO：面板每次打开都要解析全部应用图标，主线程同步做会卡住进场动画
        var apps by remember { mutableStateOf<List<SheetApp>>(emptyList()) }
        LaunchedEffect(Unit) {
            apps = withContext(Dispatchers.IO) { loadApps(context, includeSystem = true) }
        }
        val byPkg = remember(apps) { apps.associateBy { it.pkg } }
        var recent by remember { mutableStateOf(Prefs.recent(context)) }
        var showSystem by remember { mutableStateOf(Prefs.showSystemApps(context)) }
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()

        val q = debounced.trim().lowercase()
        val visibleApps = if (showSystem) apps else apps.filter { !it.system }
        val filtered = if (q.isEmpty()) {
            visibleApps
        } else {
            visibleApps.filter {
                it.label.contains(q, ignoreCase = true) || it.pkg.contains(q, ignoreCase = true)
            }
        }
        val grouped = filtered.groupBy { it.letter }.toSortedMap()

        Column(modifier = Modifier.fillMaxSize()) {
        // 顶部固定区：搜索框 +「显示系统应用」开关（图二右上角）
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = UiSpace.l, vertical = UiSpace.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SearchField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(UiSpace.s))
                NeuSwitch(
                    checked = showSystem,
                    modifier = Modifier,
                    onCheckedChange = {
                        if (Prefs.haptic(context)) Haptics.select(context)
                        showSystem = !showSystem
                        Prefs.setShowSystemApps(context, showSystem)
                    },
                )
            }
            Text(
                "显示系统应用",
                style = MaterialTheme.typography.labelSmall,
                color = UiTokens.InkSub,
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(end = UiSpace.xl),
            )

            // 已选：一行圆形图标，点击直接启动（图二）——与应用页联动，用上层同步后的 wheelKeys
            val selected = wheelKeys
            val pinned = selected.mapNotNull { byPkg[it] }
            SectionLabel("已选", modifier = Modifier.padding(horizontal = UiSpace.l, vertical = UiSpace.xs))
            if (pinned.isEmpty()) {
                Text(
                    "还没有固定应用，去「应用」页添加",
                    style = MaterialTheme.typography.bodySmall,
                    color = UiTokens.InkSub,
                    modifier = Modifier.padding(horizontal = UiSpace.l, vertical = UiSpace.s),
                )
            } else {
                Row(
                    // 6 枚徽标实占 6×48 + 5×10 = 338dp，面板内容宽只有 0.86×屏宽−32dp
                    // （360dp 屏 → 277.6dp），必溢出约 60dp：第 6 枚被父级 clip 切掉一半且点不到。
                    // 改成横向可滚动，徽标尺寸与单行高度都不变，固定头总高维持 170dp
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = UiSpace.l, vertical = UiSpace.xs)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(UiSpace.ms),
                ) {
                    pinned.take(6).forEach { app ->
                        if (app.icon != null) {
                            val shape = remember(app.pkg, app.contour) { iconShape(app.contour) }
                            NeuIconBadge(
                                iconSize = 38.dp,
                                shape = shape,
                                contour = app.contour,
                                pressable = true,
                                onClick = {
                                    if (Prefs.haptic(context)) Haptics.pick(context)
                                    onLaunch(app.pkg)
                                },
                            ) {
                                Image(
                                    bitmap = app.icon,
                                    contentDescription = app.label,
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(shape),
                                )
                            }
                        }
                    }
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = UiSpace.sm)
                    .height(1.dp)
                    .background(UiTokens.Divider),
            )
        }

        // 列表占剩余高度并滚动，避免被顶部固定区挤出屏幕；右侧叠 A-Z 字母索引
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
        // 39 号改动（B1b）：索引条定宽 28dp + 右缘内缩 2dp，避让区取 28dp
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(end = if (q.isEmpty() && grouped.isNotEmpty()) 28.dp else 0.dp),
            state = listState,
        ) {
            if (q.isNotEmpty()) {
                item {
                    Text(
                        "搜索结果 · ${filtered.size} 个",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = UiSpace.l, vertical = UiSpace.s),
                    )
                }
                // 搜索态：紧凑单行文字列表，不占图标位，一屏能看到更多结果
                items(filtered, key = { it.pkg }) { app ->
                    SearchResultRow(app = app, onClick = { onLaunch(app.pkg) })
                }
            } else {
                val recentApps = recent.mapNotNull { byPkg[it] }
                if (recentApps.isNotEmpty()) {
                    item { SectionHeader("最近使用") }
                    recentApps.chunked(3).forEach { row ->
                        item(key = "recent_${row[0].pkg}") {
                            AppRow(row, onLaunch)
                        }
                    }
                }
                grouped.forEach { (letter, list) ->
                    item(key = "head_$letter") { SectionHeader(letter) }
                    list.chunked(3).forEach { row ->
                        item(key = "app_${letter}_${row[0].pkg}") {
                            AppRow(row, onLaunch)
                        }
                    }
                }
            }
            // 底部给叠在内容上的分页指示器留出余量，最后一行不会被压住
            item { Spacer(Modifier.height(UiSpace.xxl)) }
        } // LazyColumn

        // 右侧 A-Z 快捷索引：仅非搜索态显示，列出的都是当前实际存在的分组字母。
        // 点按或上下拖动即可滚到对应字母分组（key 规则 head_$letter）。
        if (q.isEmpty() && grouped.isNotEmpty()) {
            val letters = grouped.keys.toList()
            // 精确算出每个字母 header 在 LazyColumn 中的 item 索引：
            // 「最近使用」= 1 header + ceil(n/3) 行；每个字母组 = 1 header + ceil(n/3) 行
            var runningIndex = 0
            val headerIndexByLetter = linkedMapOf<String, Int>()
            if (recent.isNotEmpty()) {
                runningIndex += 1 + (recent.size + 2) / 3
            }
            letters.forEach { letter ->
                headerIndexByLetter[letter] = runningIndex
                val count = grouped[letter]?.size ?: 0
                runningIndex += 1 + (count + 2) / 3
            }
            // 37 号改动（T2/C1 + T1/C3 + T3）：索引条抽成单源组件 AlphabetIndexBar
            //（样式/命中/滚动口径与 AppsTab 应用页共用），9sp 字面量收进 UiType.indexGlyph，
            // 选中字母色由 primary 改 Ink（primary 对底衬 3.69:1 AA 失败，Ink ≈11:1）。
            // 38 号改动（B1）：摆位收进 AlphabetIndexOverlay（贴右缘、纵向撑满列表区），
            // 不再是 240dp 居中带悬在屏幕中部压住右列图标。
            AlphabetIndexOverlay(
                letters = letters,
                onLetterSelected = { letter ->
                    // 用精确 key 找 item 索引，避免粗算误差
                    val pos = listState.layoutInfo.visibleItemsInfo
                        .firstOrNull { it.key == "head_$letter" }?.index
                    val target = pos ?: headerIndexByLetter[letter] ?: 0
                    scope.launch { listState.animateScrollToItem(target) }
                },
            )
        }
        } // Box（列表 + 右侧索引）

        LaunchedEffect(Unit) {
            // 从别处启动应用后回到面板时，最近列表保持最新
            recent = Prefs.recent(context)
        }
        } // outer Column
    }

    @Composable
    private fun SectionHeader(text: String) {
        Text(
            text,
            style = UiType.body,
            fontWeight = FontWeight.SemiBold,
            color = UiTokens.InkSub,
            modifier = Modifier
                .fillMaxWidth()
                .background(UiTokens.Surface)
                .padding(horizontal = UiSpace.l, vertical = UiSpace.s),
        )
    }

    /** 搜索结果紧凑行：无图标，名称 + 包名两行，一屏能看到更多结果 */
    @Composable
    private fun SearchResultRow(app: SheetApp, onClick: () -> Unit) {
        val context = LocalContext.current
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .neuPressable(RoundedCornerShape(UiTokens.RadiusField)) {
                    if (Prefs.haptic(context)) Haptics.pick(context)
                    onClick()
                }
                .padding(horizontal = UiSpace.l, vertical = UiSpace.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    app.label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = UiTokens.Ink,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Text(
                    app.pkg,
                    style = MaterialTheme.typography.labelSmall,
                    color = UiTokens.InkSub,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = UiTokens.InkSub,
                modifier = Modifier.size(UiTokens.IconSm),
            )
        }
    }

    @Composable
    private fun AppRow(row: List<SheetApp>, onLaunch: (String) -> Unit) {
        // 格子间留出间隙，避免相邻新拟物阴影相互重叠成脏边
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = UiSpace.s, vertical = UiSpace.xs),
            horizontalArrangement = Arrangement.spacedBy(UiSpace.s),
        ) {
            row.forEach { app ->
                GridItem(
                    app = app,
                    modifier = Modifier.weight(1f),
                    onClick = { onLaunch(app.pkg) },
                )
            }
            // 空槽补位：保证末行格子宽度与整行一致，不再被拉伸
            repeat(3 - row.size) { Spacer(modifier = Modifier.weight(1f)) }
        }
    }

    @Composable
    private fun GridItem(
        app: SheetApp,
        onClick: () -> Unit,
        modifier: Modifier = Modifier,
    ) {
        val context = LocalContext.current
        Column(
            modifier = modifier
                .neuPressable(RoundedCornerShape(UiTokens.RadiusRow)) {
                    // 应用启动统一 pick（与轮盘启动同手感）
                    if (Prefs.haptic(context)) Haptics.pick(context)
                    onClick()
                }
                .padding(vertical = UiSpace.ms, horizontal = UiSpace.xs),
            // 名称与图标居中对齐（对齐图五）
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (app.icon != null) {
                val shape = remember(app.pkg, app.contour) { iconShape(app.contour) }
                NeuIconBadge(iconSize = UiTokens.GridIcon, shape = shape, contour = app.contour) {
                    Image(
                        bitmap = app.icon,
                        contentDescription = app.label,
                        modifier = Modifier
                            .size(UiTokens.GridIcon)
                            .clip(shape),
                    )
                }
            } else {
                Spacer(Modifier.size(UiTokens.GridIcon))
            }
            Spacer(Modifier.height(UiSpace.sm))
            Text(
                app.label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                color = UiTokens.Ink,
                textAlign = TextAlign.Center,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }

    @Composable
    private fun ToolsPage(
        selected: List<String>,
        onRun: (QuickTools.Tool) -> Unit,
        onToggleWheel: (QuickTools.Tool) -> Unit,
    ) {
        val context = LocalContext.current
        val tools = remember { QuickTools.orderedEnabled(context) }
        // 工具卡跟随关联应用的真实图标与轮廓（如支付宝扫一扫显示支付宝图标）
        val linkedApps = remember { loadApps(context, includeSystem = true).associateBy { it.pkg } }
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = UiSpace.l, vertical = UiSpace.ms),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionLabel(
                    "快捷工具",
                    color = UiTokens.InkSub,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "点卡片执行 · + 放轮盘",
                    style = MaterialTheme.typography.labelSmall,
                    // UI UX Pro Max · color-contrast：labelSmall(11sp) 小字用 FieldHint 在浅色主题
                    // 对比约 2.5–3.5:1 < 4.5:1，改用 InkSub（次级文字色，对比达标）
                    color = UiTokens.InkSub,
                )
            }
            // 37 号改动（S5/2-2）：serverState 是 StateFlow，此前一次性读值不订阅，
            // 特权连接成功后提示文本与颜色不刷新。改为 collectAsState 订阅。
            val serverInfo by priv.kit.core.Privilege.serverState.collectAsState()
            val privilegeReady = serverInfo != null
            Text(
                if (privilegeReady) {
                    "特权已连接 · 微信/云闪付由系统 shell 直达"
                } else {
                    "特权未连接 · 微信/云闪付会被 MagicOS 拦截，先回「小窗」页启动特权服务"
                },
                style = MaterialTheme.typography.labelSmall,
                // 同上：特权已连接的说明小字提升对比度（未连接的警示色 Danger 保留）
                color = if (privilegeReady) UiTokens.InkSub else UiTokens.Danger,
                modifier = Modifier.padding(horizontal = UiSpace.l).padding(bottom = UiSpace.sm),
            )
            if (tools.isEmpty()) {
                Text(
                    "还没有可用的工具",
                    style = MaterialTheme.typography.bodySmall,
                    color = UiTokens.InkSub,
                    modifier = Modifier.padding(UiSpace.l),
                )
            }
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                tools.chunked(2).forEachIndexed { rowIndex, row ->
                    item(key = "tools_${row[0].id}") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = UiSpace.l, vertical = UiSpace.sm)
                                .cascadeEnter(rowIndex),
                            horizontalArrangement = Arrangement.spacedBy(UiSpace.m),
                        ) {
                            row.forEach { tool ->
                                val linked = QuickTools.linkedPackage(tool.id)?.let { linkedApps[it] }
                                ToolCard(
                                    tool = tool,
                                    available = QuickTools.isAvailable(context, tool.id),
                                    inWheel = QuickTools.toolKey(tool.id) in selected,
                                    linkedApp = linked,
                                    modifier = Modifier.weight(1f),
                                    onClick = { onRun(tool) },
                                    onToggleWheel = { onToggleWheel(tool) },
                                    onCalibrate = QuickTools.pickTarget(tool.id)?.let { target ->
                                        { pickTarget = target }
                                    },
                                )
                            }
                            repeat(2 - row.size) { Spacer(modifier = Modifier.weight(1f)) }
                        }
                    }
                }
                // 底部给叠在内容上的分页指示器留出余量，最后一行不会被压住
            item { Spacer(Modifier.height(UiSpace.xxl)) }
            }
        }
    }

    /** 竖向工具卡片：左上彩色图标 + 主标题/副标题，右上角切换轮盘占位 */
    @Composable
    private fun ToolCard(
        tool: QuickTools.Tool,
        available: Boolean,
        inWheel: Boolean,
        linkedApp: SheetApp? = null,
        modifier: Modifier = Modifier,
        onClick: () -> Unit,
        onToggleWheel: () -> Unit,
        onCalibrate: (() -> Unit)? = null,
    ) {
        val context = LocalContext.current
        val color = Color(tool.color)
        Box(modifier = modifier.heightIn(min = 120.dp)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp)
                    .alpha(if (available) 1f else 0.42f)
                    .neuPressable(RoundedCornerShape(UiTokens.RadiusRow), enabled = available) {
                        // 工具启动与普通点击区分：启动=pick
                        if (Prefs.haptic(context)) Haptics.pick(context)
                        onClick()
                    }
                    .padding(horizontal = UiSpace.ml, vertical = UiSpace.l),
            ) {
                // 图标：优先显示关联应用的真实图标与轮廓；无关联应用时回退到工具字形图标。
                // 底座形状始终跟随图标自身轮廓（用户选的「跟随应用图标形状」）。
                val appIcon = linkedApp?.icon
                val appContour = linkedApp?.contour
                val toolShape = remember(tool.id, linkedApp?.pkg) {
                    iconShape(appContour ?: QuickTools.ICON_CONTOUR)
                }
                val contour = appContour ?: QuickTools.ICON_CONTOUR
                NeuIconBadge(iconSize = UiTokens.GridIcon, shape = toolShape, contour = contour) {
                    Box(
                        modifier = Modifier
                            .size(UiTokens.GridIcon)
                            .clip(toolShape)
                            // 未加入轮盘（未启用）时底色转灰：一眼区分哪些在用
                            .background(if (inWheel) color else UiTokens.SurfaceElevated),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (appIcon != null) {
                            Image(
                                bitmap = appIcon,
                                contentDescription = tool.label,
                                modifier = Modifier
                                    .size(UiTokens.GridIcon)
                                    .clip(toolShape)
                                    // 未启用时降饱和度变灰（跟随启用状态变化）
                                    .then(
                                        if (!inWheel) Modifier.graphicsLayer { alpha = 0.55f } else Modifier,
                                    ),
                            )
                        } else {
                            Text(
                                tool.glyph,
                                color = if (inWheel) Color.White else UiTokens.InkSub,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(UiSpace.m))
                Text(
                    tool.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = UiTokens.Ink,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(UiSpace.xs))
                Text(
                    if (available) tool.desc else "不可用",
                    style = MaterialTheme.typography.labelSmall,
                    color = UiTokens.InkSub,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            // 右上角：点击加入/移出轮盘，长按（云闪付）校准入口。单独吃掉手势，不影响卡片
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = UiSpace.s, end = UiSpace.s)
                    // UI UX Pro Max · touch-target：44dp 触控热区。pointerInput 必须挂在外层
                    // 44dp 盒上 —— 旧写法 minimumInteractiveComponentSize().size(26dp) 里
                    // pointerInput 落在 26dp 内层节点，外环 9dp 命中穿透回卡片本体
                    // （点外环直接执行工具、长按校准丢失）
                    .size(UiTokens.GridIcon)
                    .pointerInput(tool.id) {
                        detectTapGestures(
                            onTap = {
                                if (Prefs.haptic(context)) Haptics.tick(context)
                                onToggleWheel()
                            },
                            onLongPress = { onCalibrate?.invoke() },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        // 视觉圆 26dp 不变
                        .size(26.dp)
                        .clip(CircleShape)
                        // 未入轮盘的「+」底色跟主题走（原硬编码经典蓝会在其余三主题串味）
                        .background(if (inWheel) color else UiTokens.PrimarySoft),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (inWheel) Icons.Filled.Check else Icons.Filled.Add,
                        contentDescription = if (inWheel) "已加入轮盘，点按移出" else "加入轮盘",
                        tint = if (inWheel) Color.White else UiTokens.Primary,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }
    }

    private data class SheetApp(
        val pkg: String,
        val label: String,
        val icon: androidx.compose.ui.graphics.ImageBitmap?,
        val letter: String,
        val system: Boolean = false,
        /** 实测轮廓比例，底座与图标裁剪共用 */
        val contour: Float = DEFAULT_ICON_CONTOUR,
    )

    /**
     * 加载应用：默认只取 LAUNCHER（用户应用）；includeSystem=true 时额外把
     * 带系统签名但有启动入口的应用也纳入（图二「显示系统应用」开关）。
     */
    private fun loadApps(context: Context, includeSystem: Boolean = false): List<SheetApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = pm.queryIntentActivities(intent, 0)
            .mapNotNull { ri ->
                runCatching {
                    val info = ri.activityInfo
                    if (info.packageName == context.packageName) return@runCatching null
                    val label = ri.loadLabel(pm).toString()
                    val isSystem =
                        (info.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                    val bitmap = ri.loadIcon(pm).toBitmap(72, 72)
                    SheetApp(
                        pkg = info.packageName,
                        label = label,
                        icon = bitmap.asImageBitmap(),
                        letter = LetterUtil.firstLetter(label),
                        system = isSystem,
                        contour = detectIconContour(bitmap),
                    )
                }.getOrNull()
            }
            .distinctBy { it.pkg }
        // includeSystem=false 时过滤系统应用；true 时全部保留，由 UI 开关决定展示
        return list
            .let { if (includeSystem) it else it.filter { app -> !app.system } }
            .sortedWith(compareBy({ it.letter }, { it.label }))
    }
}
