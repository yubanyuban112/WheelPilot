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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
        val pagerState = rememberPagerState(pageCount = { 2 })
        val scope = rememberCoroutineScope()

        // 轮盘当前条目：工具也能占位，key 为 tool:<id>
        var wheelKeys by remember { mutableStateOf(Prefs.load(context)) }

        fun toggleTool(tool: QuickTools.Tool) {
            val key = QuickTools.toolKey(tool.id)
            if (key in wheelKeys) {
                wheelKeys = wheelKeys - key
                Prefs.save(context, wheelKeys)
                Toast.makeText(context, "已从轮盘移除「${tool.label}」", Toast.LENGTH_SHORT).show()
            } else {
                if (wheelKeys.size >= Prefs.MAX_APPS) {
                    Toast.makeText(
                        context, "轮盘只有 ${Prefs.MAX_APPS} 个位置，先移除一个", Toast.LENGTH_SHORT,
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
                    .background(Color(0x61000000))
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
                enter = fadeIn(animationSpec = tween(160)) +
                    slideInVertically(
                        initialOffsetY = { it / 6 },
                        animationSpec = androidx.compose.animation.core.spring<IntOffset>(
                            dampingRatio = 0.72f,
                            stiffness = 380f,
                        ),
                    ) +
                    scaleIn(initialScale = 0.96f, animationSpec = GlassMotion.overshoot()),
                exit = fadeOut(animationSpec = tween(GlassMotion.EXIT_MS)) +
                    scaleOut(targetScale = 0.96f, animationSpec = tween(GlassMotion.EXIT_MS)),
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
                                .padding(top = 10.dp, bottom = 2.dp)
                                .size(width = 36.dp, height = 4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(UiTokens.Divider),
                        )

                        // 圆形关闭按钮 + 居中的「应用 / 工具」页签
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .neuPressable(shape = CircleShape, blur = 10f, dist = 5f) {
                                        if (Prefs.haptic(context)) Haptics.select(context)
                                        closeSheet()
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "×",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = UiTokens.InkSub,
                                )
                            }
                            // 应用/工具页签：凸起药丸容器 + 内凹滑动药丸（与底栏同一套语言）。
                            // 药丸位置由实际 cell 宽度推导（maxWidth/2），不再硬编码，保证与文字永远对中
                            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                androidx.compose.foundation.layout.BoxWithConstraints(
                                    modifier = Modifier
                                        .width(190.dp)
                                        .height(36.dp)
                                        .neuRaised(RoundedCornerShape(50), blur = 10f, dist = 5f),
                                ) {
                                    val cellW = maxWidth / 2
                                    val pillW = cellW - 6.dp
                                    val pillX by androidx.compose.animation.core.animateDpAsState(
                                        targetValue = if (tab == 0) 3.dp else cellW + 3.dp,
                                        animationSpec = androidx.compose.animation.core.spring<Dp>(
                                            dampingRatio = 0.9f,
                                            stiffness = Spring.StiffnessMedium,
                                        ),
                                        label = "sheet-tab-pill",
                                    )
                                    Box(
                                        modifier = Modifier
                                            .offset(x = pillX, y = 3.dp)
                                            .size(width = pillW, height = 30.dp)
                                            .neuInset(RoundedCornerShape(50), blur = 8f, dist = 4f),
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

                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier
                                .weight(1f)
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

                        // 分页圆点（图一图二底部）
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            repeat(2) { index ->
                                val on = tab == index
                                Box(
                                    modifier = Modifier
                                        .padding(horizontal = 3.dp)
                                        .size(if (on) 7.dp else 6.dp)
                                        .clip(CircleShape)
                                        .background(if (on) UiTokens.Primary else UiTokens.Divider)
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                        ) {
                                            if (Prefs.haptic(context)) Haptics.select(context)
                                            scope.launch { pagerState.animateScrollToPage(index) }
                                        },
                                )
                            }
                        }
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
                    Spacer(Modifier.height(10.dp))
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
                                color = UiTokens.Ink,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(name) }
                                    .padding(vertical = 10.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = {
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
    ) {
        val context = LocalContext.current
        val apps = remember { loadApps(context, includeSystem = true) }
        val byPkg = remember { apps.associateBy { it.pkg } }
        var recent by remember { mutableStateOf(Prefs.recent(context)) }
        var showSystem by remember { mutableStateOf(Prefs.showSystemApps(context)) }

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
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SearchField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
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
                    .padding(end = 22.dp),
            )

            // 已选：一行圆形图标，点击直接启动（图二）
            val selected = remember { Prefs.load(context) }
            val pinned = selected.mapNotNull { byPkg[it] }
            SectionLabel("已选", modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            if (pinned.isEmpty()) {
                Text(
                    "还没有固定应用，去「应用」页添加",
                    style = MaterialTheme.typography.bodySmall,
                    color = UiTokens.InkSub,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
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
                    .padding(vertical = 6.dp)
                    .height(1.dp)
                    .background(UiTokens.Divider),
            )
        }

        // 列表占剩余高度并滚动，避免被顶部固定区挤出屏幕
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (q.isNotEmpty()) {
                item {
                    Text(
                        if (filtered.isEmpty()) "没有匹配的应用"
                        else "搜索结果 · ${filtered.size} 个",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
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
            item { Spacer(Modifier.height(16.dp)) }
        }

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
            style = MaterialTheme.typography.titleSmall.copy(fontSize = 13.sp),
            fontWeight = FontWeight.SemiBold,
            color = UiTokens.InkSub,
            modifier = Modifier
                .fillMaxWidth()
                .background(UiTokens.Surface)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }

    @Composable
    private fun AppRow(row: List<SheetApp>, onLaunch: (String) -> Unit) {
        // 格子间留出间隙，避免相邻新拟物阴影相互重叠成脏边
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                .padding(vertical = 10.dp, horizontal = 4.dp),
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
            Spacer(Modifier.height(6.dp))
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
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
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
                    color = UiTokens.FieldHint,
                )
            }
            val privilegeReady = remember { priv.kit.core.Privilege.serverState.value != null }
            Text(
                if (privilegeReady) {
                    "特权已连接 · 微信/云闪付由系统 shell 直达"
                } else {
                    "特权未连接 · 微信/云闪付会被 MagicOS 拦截，先回「小窗」页启动特权服务"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (privilegeReady) UiTokens.FieldHint else Color(0xFFD9534F),
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 6.dp),
            )
            if (tools.isEmpty()) {
                Text(
                    "还没有可用的工具",
                    style = MaterialTheme.typography.bodySmall,
                    color = UiTokens.InkSub,
                    modifier = Modifier.padding(16.dp),
                )
            }
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                tools.chunked(2).forEachIndexed { rowIndex, row ->
                    item(key = "tools_${row[0].id}") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                                .cascadeEnter(rowIndex),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            row.forEach { tool ->
                                ToolCard(
                                    tool = tool,
                                    available = QuickTools.isAvailable(context, tool.id),
                                    inWheel = QuickTools.toolKey(tool.id) in selected,
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
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }

    /** 竖向工具卡片：左上彩色图标 + 主标题/副标题，右上角切换轮盘占位 */
    @Composable
    private fun ToolCard(
        tool: QuickTools.Tool,
        available: Boolean,
        inWheel: Boolean,
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
                    .padding(horizontal = 14.dp, vertical = 16.dp),
            ) {
                // 图标：凸起软垫底 + 品牌色块，底座与色块同形状（跟随工具图标圆角）
                val toolShape = remember(tool.id) { iconShape(QuickTools.ICON_CONTOUR) }
                NeuIconBadge(iconSize = 44.dp, shape = toolShape, contour = QuickTools.ICON_CONTOUR) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(toolShape)
                            .background(color),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            tool.glyph,
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    tool.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = UiTokens.Ink,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
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
                    .padding(top = 8.dp, end = 8.dp)
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(if (inWheel) color else Color(0x142F6BFF))
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
                Text(
                    if (inWheel) "✓" else "+",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (inWheel) Color.White else UiTokens.Primary,
                )
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
