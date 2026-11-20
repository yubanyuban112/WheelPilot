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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import priv.kit.ui.PrivilegeScaffold


// 应用页：已选轮盘 + 全部应用列表 + 拖拽排序

/**
 * 应用列表异步加载：图标解码（loadIcon → toBitmap → 轮廓检测）放到 IO 线程。
 *
 * 主线程同步解析上百个图标是切页掉帧的直接原因；缓存命中时零等待返回，
 * 未命中先给空列表，几十到几百毫秒后自动填充，不再阻塞首帧。
 */
@Composable
internal fun rememberLaunchableApps(context: Context): List<AppEntry> {
    val cached = remember { cachedLaunchableApps }
    var apps by remember { mutableStateOf<List<AppEntry>>(cached ?: emptyList()) }
    LaunchedEffect(Unit) {
        if (cached == null) {
            apps = withContext(Dispatchers.IO) { launchableApps(context) }
        }
    }
    return apps
}

@Composable
internal fun AppsTab(
    selected: List<String>,
    onToggle: (AppEntry) -> Unit,
    onReorder: (List<String>) -> Unit,
    onToggleTool: (String) -> Unit,
) {
    val context = LocalContext.current
    val apps = rememberLaunchableApps(context)

    // 对齐同类小窗应用：搜索 120ms 防抖、系统应用开关、首次默认填充
    var query by remember { mutableStateOf("") }
    var debounced by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(Prefs.showSystemApps(context)) }
    var pendingRemove by remember { mutableStateOf<AppEntry?>(null) }
    // 子分页：0=应用（轮盘定位 + 全部应用），1=快捷工具（独立成页）
    var subTab by remember { mutableIntStateOf(0) }

    LaunchedEffect(query) {
        delay(120)
        debounced = query
    }

    // 按当前 selected 顺序解析出已选条目，供拖拽排序与横排显示；
    // tool:<id> 是快捷工具，不在应用列表里，单独转成 AppEntry
    val byPkg = remember(apps) { apps.associateBy { it.pkg } }
    val pinnedApps = selected.mapNotNull { key ->
        byPkg[key] ?: QuickTools.asPilotApp(context, key)?.let { tool ->
            AppEntry(
                pkg = tool.pkg,
                label = tool.label,
                icon = tool.icon?.asImageBitmap(),
                letter = "#",
                system = false,
                // 轮廓取自与「更多」面板同一处的 visual()，工具在三处页面才会是同一个形状
                contour = QuickTools.contourOf(context, key) ?: QuickTools.ICON_CONTOUR,
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            // 63 号：搜索聚焦弹出输入法时，把列表整体顶上去，避免键盘遮住最后的搜索结果
            .imePadding()
            .padding(horizontal = UiSpace.l),
    ) {
        Spacer(Modifier.height(UiSpace.s))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "应用",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = UiTokens.Ink,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${selected.size}/${Prefs.slots(context)}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            "长按拖动，调整轮盘顺序",
            style = MaterialTheme.typography.bodySmall,
            color = UiTokens.InkSub,
            modifier = Modifier.padding(top = UiSpace.xxs, bottom = UiSpace.s),
        )

        // 子分页：应用 / 快捷工具。工具从原应用列表抽出，独立成页更好找
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = UiSpace.s),
            horizontalArrangement = Arrangement.spacedBy(UiSpace.s),
        ) {
            NeuChip(text = "应用", selected = subTab == 0) { subTab = 0 }
            NeuChip(text = "快捷工具", selected = subTab == 1) { subTab = 1 }
        }

        if (subTab == 1) {
            // 工具子页：先给「轮盘固定定位」，再给工具网格。
            // 之前只有网格，用户在这里加了工具却看不到它排在第几位、也没法调顺序，
            // 必须切回「应用」页才能排序 —— 两个子页信息不对称。
            LazyColumn(modifier = Modifier.weight(1f)) {
                item(key = "tool_pinned") {
                    SectionCard(
                        title = "轮盘固定定位",
                        subtitle = "长按 0.2 秒拖动排序 · 点一下移除",
                    ) {
                        if (pinnedApps.isEmpty()) {
                            Text(
                                "点下面的工具，就能放进这个位置",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = UiSpace.xs),
                            )
                        } else {
                            PinnedRow(
                                apps = pinnedApps,
                                onTap = { entry -> pendingRemove = entry },
                                onReorder = onReorder,
                            )
                        }
                    }
                }
                item(key = "tool_tip") {
                    Text(
                        "点一下工具即放进轮盘，再点一次移出",
                        style = MaterialTheme.typography.labelMedium,
                        color = UiTokens.InkSub,
                        modifier = Modifier.padding(top = UiSpace.s, bottom = UiSpace.xs),
                    )
                }
                // 4 列网格：9 个工具两行多一个，比 3 列少一行、不留大片空槽
                val toolRows = QuickTools.ALL.chunked(4)
                items(toolRows.size) { r ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = UiSpace.xxs),
                        horizontalArrangement = Arrangement.spacedBy(UiSpace.sm),
                    ) {
                        toolRows[r].forEach { tool ->
                            val key = QuickTools.toolKey(tool.id)
                            ToolCell(
                                tool = tool,
                                checked = key in selected,
                                modifier = Modifier.weight(1f),
                                onClick = { onToggleTool(key) },
                            )
                        }
                        repeat(4 - toolRows[r].size) { Spacer(modifier = Modifier.weight(1f)) }
                    }
                }
                item(key = "tool_bottom") { Spacer(Modifier.height(UiSpace.l)) }
            }
            return@Column
        }

        // 全部应用：过滤系统应用 + 搜索(label 或 pkg 包含, 大小写不敏感)。
        // 分组规则与「更多」面板同一套：按预计算首字母 groupBy 后 toSortedMap，
        // 不是靠数据源顺序「连续同字母归组」——那样一旦数据源排序变了（例如把 # 挪到末尾），
        // 同一个字母会被切成两组，索引条也就对不上位置了。
        val q = debounced.trim().lowercase()
        val filtered = apps.filter { app ->
            (showSystem || !app.system) &&
                (q.isEmpty() ||
                    app.label.lowercase().contains(q) ||
                    app.pkg.lowercase().contains(q))
        }
        val grouped = filtered.groupBy { it.letter }.toSortedMap()
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        val scope = rememberCoroutineScope()
        // 38 号改动（B1）→ 39 号更正（B1b）：索引条定宽 28dp + 右缘内缩 2dp，
        // 避让区取 28dp 保证最宽字母（W）的完整字形在避让区内
        val showIndexBar = q.isEmpty() && grouped.isNotEmpty()

        // 列表 + 右侧 A-Z 索引（索引条叠在列表上，不占布局宽度）
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(end = if (showIndexBar) 28.dp else 0.dp),
            state = listState,
        ) {
            // 轮盘固定定位放最上面：这是最常调整的东西，不该被工具区挤到下面
            item(key = "pinned") {
                SectionCard(title = "轮盘固定定位", subtitle = "长按 0.2 秒拖动排序 · 点一下移除") {
                    if (pinnedApps.isEmpty()) {
                        Text(
                            "点下面的应用，就能放进这个位置",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = UiSpace.ms),
                        )
                    } else {
                        PinnedRow(
                            apps = pinnedApps,
                            onTap = { entry -> pendingRemove = entry },
                            onReorder = onReorder,
                        )
                    }
                }
            }

            // 快捷工具已独立到「快捷工具」子页，应用页不再重复占据列表空间

            item(key = "search") {
                SearchField(value = query, onValueChange = { query = it })
                Spacer(Modifier.height(UiSpace.s))
            }

            item(key = "all_header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = UiSpace.ml, bottom = UiSpace.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionLabel(
                        "全部应用",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "显示系统应用",
                        style = MaterialTheme.typography.labelMedium,
                        color = UiTokens.InkSub,
                    )
                    Spacer(Modifier.width(UiSpace.sm))
                    NeuSwitch(
                        checked = showSystem,
                        onCheckedChange = {
                            showSystem = !showSystem
                            Prefs.setShowSystemApps(context, showSystem)
                        },
                    )
                }
            }

            if (filtered.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "没有匹配的应用",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = UiSpace.l),
                    )
                }
            }

            // 按首字母分组（键已按字母升序，与更多面板一致）
            grouped.forEach { (letter, list) ->
                item(key = "head_$letter") {
                    SectionLabel(
                        letter,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = UiSpace.m, bottom = UiSpace.xs),
                    )
                }
                // 3 列网格（对齐图三）：图标 + 名称居中，空槽补位避免末行拉伸
                val rows = list.chunked(3)
                items(rows.size) { r ->
                    // 格子间留出间隙，避免相邻新拟物阴影相互重叠成脏边
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = UiSpace.xs),
                        horizontalArrangement = Arrangement.spacedBy(UiSpace.sm),
                    ) {
                        rows[r].forEach { app ->
                            AppCell(
                                app = app,
                                checked = app.pkg in selected,
                                modifier = Modifier.weight(1f),
                                onClick = { onToggle(app) },
                            )
                        }
                        repeat(3 - rows[r].size) { Spacer(modifier = Modifier.weight(1f)) }
                    }
                }
            }
            // 底部余量：索引条与网格在同一块区域，末行不贴边
            item(key = "apps_bottom") { Spacer(Modifier.height(UiSpace.l)) }
        }

        // 右侧 A-Z 快捷索引：与「更多」面板同一套交互 —— 点按或上下拖动，
        // 滚到对应字母分组（分组头的 key 规则同为 head_$letter）。
        if (showIndexBar) {
            val letters = grouped.keys.toList()
            // 精确推算每个分组头在 LazyColumn 里的 item 序号：
            // 固定前置 3 项（pinned / search / all_header）+ 每组 1 个头 + ceil(n/3) 行
            var runningIndex = 3
            val headerIndexByLetter = linkedMapOf<String, Int>()
            letters.forEach { letter ->
                headerIndexByLetter[letter] = runningIndex
                val count = grouped[letter]?.size ?: 0
                runningIndex += 1 + (count + 2) / 3
            }
            // 38 号改动（B1）：摆位收进单源组件 AlphabetIndexOverlay（贴右缘、纵向撑满列表区），
            // 不再是 240dp 居中带悬在屏幕中部压住右列图标
            AlphabetIndexOverlay(
                letters = letters,
                onLetterSelected = { letter ->
                    // 优先用已渲染 item 的真实序号，避免推算与实际不一致
                    val pos = listState.layoutInfo.visibleItemsInfo
                        .firstOrNull { it.key == "head_$letter" }?.index
                    val target = pos ?: headerIndexByLetter[letter] ?: 0
                    scope.launch { listState.animateScrollToItem(target) }
                },
            )
        }
        } // Box（列表 + 右侧索引）
    }

    // 确认删除弹框（对齐 confirmRemove）
    pendingRemove?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            containerColor = UiTokens.DialogSurface,
            title = { Text("从轮盘移除？") },
            text = { Text("${entry.label} 会从固定位里消失，随时可以再加回来。") },
            // 37 号改动（T13/C2）：删除确认弹窗与同角色弹窗统一走 NeuButton 主题按钮体系，
            // 不再裸用 M3 TextButton（未着主题强调色，外观/行为与其他弹窗不一致）
            confirmButton = {
                NeuButton(text = "移除", primary = true) {
                    onToggle(entry)
                    pendingRemove = null
                }
            },
            dismissButton = {
                NeuButton(text = "取消") { pendingRemove = null }
            },
        )
    }
}

/** 分组卡片外壳 */
@Composable
internal fun SectionCard(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit,
) {
    // 面板已浮起，内部完全平铺：不用阴影、不用凹槽，只靠间距与标题分层
    Box(modifier = Modifier.fillMaxWidth().padding(top = UiSpace.s)) {
        Column(modifier = Modifier.padding(UiSpace.l)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = UiTokens.Ink,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = UiTokens.InkSub,
                modifier = Modifier.padding(top = UiSpace.xxs),
            )
            Spacer(Modifier.height(UiSpace.m))
            content()
        }
    }
}

/** 单个应用格子：图标 + 名称 + 勾选态 */
@Composable
internal fun AppCell(
    app: AppEntry,
    checked: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    // 底座轮廓 = 图标自己的圆角：圆图标配圆底座，方图标配方底座
    val shape = remember(app.pkg, app.contour) { iconShape(app.contour) }
    Column(
        modifier = modifier
            // UI UX Pro Max · Accessibility：应用格补 role=Checkbox + 选中态，
            // TalkBack 读"微信，复选框，已勾选/未勾选"（勾选态此前只有 ✓ 视觉符号）
            .semantics(mergeDescendants = true) cell@{
                role = Role.Checkbox
                this@cell.selected = checked
            }
            .neuPressable(RoundedCornerShape(UiTokens.RadiusRow)) {
                // 勾选切换 = 普通点击，统一 select 手感
                if (Prefs.haptic(context)) Haptics.select(context)
                onClick()
            }
            .padding(vertical = UiSpace.s),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            if (app.icon != null) {
                NeuIconBadge(iconSize = UiTokens.GridIcon, shape = shape, contour = app.contour) {
                    Box(modifier = Modifier.size(UiTokens.GridIcon).clip(shape)) {
                        Image(
                            bitmap = app.icon,
                            contentDescription = app.label,
                            modifier = Modifier.fillMaxSize(),
                        )
                        // 顶部一层极淡高光：图标不再是「贴纸」，而是有厚度的键帽
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    androidx.compose.ui.graphics.Brush.verticalGradient(
                                        0f to androidx.compose.ui.graphics.Color.White.copy(alpha = 0.26f),
                                        0.42f to androidx.compose.ui.graphics.Color.Transparent,
                                    ),
                                ),
                        )
                    }
                }
            } else {
                Spacer(Modifier.size(48.dp))
            }
            if (checked) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(UiTokens.IconSm)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null, // 勾选态已由外层 Checkbox 的 selected 语义表达
                        tint = UiTokens.PrimaryOn,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(UiSpace.xs))
        Text(
            app.label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            color = UiTokens.Ink,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

/**
 * 轮盘固定位网格（3 列 × 2 行，对齐图三）：图标 + 名称，虚线占位补足 6 格。
 *  - 长按后把图标「拿起」：浮起阴影 + 轻微放大 + 高亮底衬；
 *  - 拖动时被拖图标跟手移动，其它图标用动画平滑让位（不是瞬间跳变）；
 *  - 横向越过相邻格中线、纵向越过上/下行中线都会交换顺序并给一次 tick 震动；
 *  - 松手保存并短暂高亮确认。点击某项回调 onTap（由上层弹确认删除框）。
 *
 * 实现要点：每个包名对应一对 Animatable（X/Y），key 稳定；
 * 拖动只改被拖项的 offset，交换后目标槽位变化触发其它项 animateTo 平滑让位。
 */
@Composable
internal fun PinnedRow(
    apps: List<AppEntry>,
    onTap: (AppEntry) -> Unit,
    onReorder: (List<String>) -> Unit,
) {
    val context = LocalContext.current
    val order = remember(apps.map { it.pkg }) {
        mutableStateListOf<String>().apply { addAll(apps.map { it.pkg }) }
    }
    LaunchedEffect(apps.map { it.pkg }) {
        val target = apps.map { it.pkg }
        if (order.toList() != target) {
            order.clear(); order.addAll(target)
        }
    }

    // 轮盘最多 8 个：行数按实际条数算，不能只看 MAX_PINNED(6)，
    // 否则第 7、8 个会溢出卡片、压在「全部应用」标题上
    val cols = 3
    val slotCount = maxOf(order.size, Prefs.MAX_PINNED)
    val placeholders = (slotCount - order.size).coerceAtLeast(0)
    val rows = (slotCount + cols - 1) / cols

    /** 被拖走的槽位；-1 = 没在拖 */
    var dragIndex by remember { mutableIntStateOf(-1) }
    /** ghost 左上角（px），跟手用 */
    var ghostX by remember { mutableFloatStateOf(0f) }
    var ghostY by remember { mutableFloatStateOf(0f) }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val cellW = maxWidth / cols
        val cellH = 84.dp
        val cellWpx = with(density) { cellW.toPx() }
        val cellHpx = with(density) { cellH.toPx() }
        val maxX = (cols - 1) * cellWpx
        val maxY = (rows - 1) * cellHpx

        fun slotAt(x: Float, y: Float): Int {
            val c = (x / cellWpx).toInt().coerceIn(0, cols - 1)
            val r = (y / cellHpx).toInt().coerceIn(0, rows - 1)
            return r * cols + c
        }

        // 交互对齐同类小窗应用 PinnedDragGrid：一个整体手势区 + 固定槽位 + ghost 跟手。
        // 槽位本身不动，交换只换内容，所以没有「整片位移动画」，只有 ghost 在跟手。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(cellH * rows)
                // UI UX Pro Max · Accessibility：拖拽排序的 TalkBack 替代操作。
                // 长按拖拽对读屏用户不可用，这里为每个已占用槽位生成「前移/后移」动作，
                // 动作名带应用名，读屏用户可在「自定义操作」里听到并触发。
                .semantics {
                    customActions = order.mapIndexed { index, pkg ->
                        val label = byPkgLookup(apps, pkg)?.label ?: pkg
                        val move: (Int) -> CustomAccessibilityAction = { delta ->
                            CustomAccessibilityAction("将${label}${if (delta < 0) "前移" else "后移"}一位") {
                                val to = index + delta
                                if (to in order.indices) {
                                    val moved = order.removeAt(index)
                                    order.add(to, moved)
                                    onReorder(order.toList())
                                    Haptics.tick(context)
                                }
                                true
                            }
                        }
                        buildList {
                            if (index > 0) add(move(-1))
                            if (index < order.lastIndex) add(move(1))
                        }
                    }.flatten()
                }
                .pointerInput(order.size) {
                    detectTapGestures { offset ->
                        if (dragIndex >= 0) return@detectTapGestures
                        val idx = slotAt(offset.x, offset.y)
                        byPkgLookup(apps, order.getOrNull(idx) ?: "")?.let(onTap)
                    }
                }
                .pointerInput(order.size, cellWpx, cellHpx) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { offset ->
                            val idx = slotAt(offset.x, offset.y)
                            if (idx in order.indices) {
                                dragIndex = idx
                                // ghost 中心落在手指上（同同类小窗应用 updateGhost 的 -cellW/2）
                                ghostX = (offset.x - cellWpx / 2f).coerceIn(0f, maxX)
                                ghostY = (offset.y - cellHpx / 2f).coerceIn(0f, maxY)
                                Haptics.pick(context)
                            }
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            if (dragIndex < 0) return@detectDragGesturesAfterLongPress
                            ghostX = (ghostX + amount.x).coerceIn(0f, maxX)
                            ghostY = (ghostY + amount.y).coerceIn(0f, maxY)
                            val target = slotAt(ghostX + cellWpx / 2f, ghostY + cellHpx / 2f)
                                .coerceIn(0, order.lastIndex)
                            if (target != dragIndex) {
                                // 直接交换数据，槽位内容立即改绑（同类小窗应用 swap + bindCell）
                                val moved = order.removeAt(dragIndex)
                                order.add(target, moved)
                                dragIndex = target
                                Haptics.tick(context)
                            }
                        },
                        onDragEnd = {
                            if (dragIndex >= 0) {
                                dragIndex = -1
                                onReorder(order.toList())
                                Haptics.select(context)
                            }
                        },
                        onDragCancel = {
                            if (dragIndex >= 0) {
                                dragIndex = -1
                                onReorder(order.toList())
                            }
                        },
                    )
                },
        ) {
            order.forEachIndexed { index, pkg ->
                val app = byPkgLookup(apps, pkg) ?: return@forEachIndexed
                PinnedCell(
                    app = app,
                    modifier = Modifier
                        .width(cellW)
                        .height(cellH)
                        .offset {
                            IntOffset(
                                ((index % cols) * cellWpx).toInt(),
                                ((index / cols) * cellHpx).toInt(),
                            )
                        }
                        // 被拖走那一格淡到 0.28，ghost 才显眼（同 applyDragAlphas）
                        .alpha(if (dragIndex == index) 0.28f else 1f),
                )
            }

            // ghost：浮在最上层跟手，缩放 + 14dp 投影（同 startDrag 的 setElevation）
            val ghostApp = order.getOrNull(dragIndex)?.let { byPkgLookup(apps, it) }
            if (dragIndex >= 0 && ghostApp != null) {
                PinnedCell(
                    app = ghostApp,
                    lifted = true,
                    modifier = Modifier
                        .width(cellW)
                        .height(cellH)
                        .offset { IntOffset(ghostX.toInt(), ghostY.toInt()) }
                        .zIndex(3f)
                        .graphicsLayer {
                            scaleX = 1.08f
                            scaleY = 1.08f
                            shadowElevation = 14.dp.toPx()
                            shape = androidx.compose.foundation.shape.CircleShape
                        },
                )
            }

            // 虚线「+」占位：补足到 MAX_PINNED 格
            repeat(placeholders) { i ->
                val slot = order.size + i
                Column(
                    modifier = Modifier
                        .width(cellW)
                        .height(cellH)
                        .offset {
                            IntOffset(
                                ((slot % cols) * cellWpx).toInt(),
                                ((slot / cols) * cellHpx).toInt(),
                            )
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(UiSpace.xs))
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .drawBehind {
                                drawCircle(
                                    color = UiTokens.InkSub.copy(alpha = 0.55f),
                                    radius = size.minDimension / 2f - 1.dp.toPx(),
                                    style = Stroke(
                                        width = 1.5.dp.toPx(),
                                        pathEffect = PathEffect.dashPathEffect(
                                            floatArrayOf(6.dp.toPx(), 5.dp.toPx()), 0f,
                                        ),
                                    ),
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = null, // 空槽装饰，无语义
                            tint = UiTokens.InkSub,
                            modifier = Modifier.size(UiTokens.IconMd),
                        )
                    }
                }
            }
        }
    }
}

/** 固定位单个格子：软垫圆底 + 图标 + 名字。ghost 与槽位共用同一套绘制 */
@Composable
private fun PinnedCell(
    app: AppEntry,
    modifier: Modifier = Modifier,
    lifted: Boolean = false,
) {
    val shape = remember(app.pkg, app.contour) { iconShape(app.contour) }
    val plate = remember(app.pkg, app.contour) { iconPlateShape(app.contour, 42.dp, 5.dp) }
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(UiSpace.xs))
        Box(
            modifier = Modifier
                .size(52.dp)
                .neuRaised(plate, blur = 12f, dist = 6f),
            contentAlignment = Alignment.Center,
        ) {
            if (app.icon != null) {
                Box(modifier = Modifier.size(42.dp).clip(shape)) {
                    Image(
                        bitmap = app.icon,
                        contentDescription = app.label,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                androidx.compose.ui.graphics.Brush.verticalGradient(
                                    0f to androidx.compose.ui.graphics.Color.White.copy(alpha = 0.26f),
                                    0.42f to androidx.compose.ui.graphics.Color.Transparent,
                                ),
                            ),
                    )
                }
            }
            if (lifted) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(shape)
                        .background(UiTokens.PrimarySoft.copy(alpha = 0.35f)),
                )
            }
        }
        Spacer(Modifier.height(UiSpace.xs))
        Text(
            app.label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            color = UiTokens.Ink,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

internal fun byPkgLookup(apps: List<AppEntry>, pkg: String): AppEntry? = apps.firstOrNull { it.pkg == pkg }


// ---------------------------------------------------------------------------
// 设置页：全部参数
// ---------------------------------------------------------------------------

/** 快捷工具格子：图标 + 短名 + 勾选态（对齐同类小窗应用首页的工具横排） */
@Composable
private fun ToolCell(
    tool: QuickTools.Tool,
    checked: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    // 图标与轮廓统一走 QuickTools.visual()：与「更多」面板工具卡、轮盘是同一份数据
    val visual = remember(tool.id) { QuickTools.visual(context, tool) }
    val bitmap = remember(tool.id) { visual.icon.asImageBitmap() }
    // 底座与图标裁剪共用同一个形状，且形状取自工具实际显示的图标
    val shape = remember(tool.id) { iconShape(visual.contour) }
    Column(
        modifier = modifier
            // 宽度交给外层 weight 决定（4 列网格），不再写死 64dp
            .clip(RoundedCornerShape(UiTokens.RadiusRow))
            // UI UX Pro Max · Accessibility：工具格补 role=Checkbox + 选中态
            .semantics(mergeDescendants = true) cell@{
                role = Role.Checkbox
                this@cell.selected = checked
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                if (Prefs.haptic(context)) Haptics.select(context)
                onClick()
            }
            .padding(vertical = UiSpace.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            NeuIconBadge(iconSize = 40.dp, shape = shape, contour = visual.contour) {
                Image(
                    bitmap = bitmap,
                    contentDescription = tool.label,
                    modifier = Modifier.size(40.dp).clip(shape),
                )
            }
            if (checked) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(UiTokens.IconSm)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null, // 勾选态已由外层 Checkbox 的 selected 语义表达
                        tint = UiTokens.PrimaryOn,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(UiSpace.xs))
        Text(
            tool.short,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            color = UiTokens.Ink,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}
