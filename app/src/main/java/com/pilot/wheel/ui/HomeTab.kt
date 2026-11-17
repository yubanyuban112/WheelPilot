package com.pilot.wheel

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// 小窗页：一屏看全（服务状态 / 轮盘现状 / 工具 / 用法），不需要下滑

@Composable
internal fun HomeTab(
    accessible: Boolean,
    selectedCount: Int,
    onOpenPrivilege: () -> Unit,
) {
    val context = LocalContext.current
    // 一次性读取：切页回来会重新组合，不需要订阅
    val privilegeReady = remember { priv.kit.core.Privilege.serverState.value != null }
    val radius = remember { Prefs.radiusDp(context) }
    val icon = remember { Prefs.iconDp(context) }
    val angle = remember { Prefs.angle(context) }
    val hotW = remember { Prefs.hotWidth(context) }
    val hotH = remember { Prefs.hotHeight(context) }
    val left = remember { Prefs.leftEnabled(context) }
    val right = remember { Prefs.rightEnabled(context) }
    val tools = remember { QuickTools.orderedEnabled(context) }
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(10.dp))

        // 标题 + 轮盘占用：右上角直接给出「已放几个」
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "小窗",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = UiTokens.Ink,
                )
                Text(
                    "v$versionName · 角落轮盘启动器",
                    style = MaterialTheme.typography.bodySmall,
                    color = UiTokens.InkSub,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(UiTokens.PrimarySoft)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "$selectedCount",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = UiTokens.Primary,
                )
                Text(
                    " / ${Prefs.MAX_APPS}",
                    style = MaterialTheme.typography.labelMedium,
                    color = UiTokens.Primary.copy(alpha = 0.72f),
                )
            }
        }

        Spacer(Modifier.height(12.dp))

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
                Spacer(Modifier.height(2.dp))
                Text(
                    "无障碍开关是灰的？应用信息 → 右上角 ⋮ → 允许受限制的设置",
                    style = MaterialTheme.typography.labelSmall,
                    color = UiTokens.InkSub,
                )
            }
        }

        // 轮盘现状：四个数值一眼看完，不再用小字堆段落
        Panel {
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
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Stat("半径", "${radius.toInt()}", "dp", Modifier.weight(1f))
                Stat("图标", "${icon.toInt()}", "dp", Modifier.weight(1f))
                Stat("张角", "${angle.toInt()}", "°", Modifier.weight(1f))
                Stat("启动", launchMode, "", Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "触发区 ${hotW.toInt()} × ${hotH.toInt()} dp",
                style = MaterialTheme.typography.labelSmall,
                color = UiTokens.InkSub,
            )
        }

        // 快捷工具：小图标一排，直接看清启用了哪些
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "快捷工具",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = UiTokens.Ink,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${tools.size} 项",
                    style = MaterialTheme.typography.labelMedium,
                    color = UiTokens.Primary,
                )
            }
            Spacer(Modifier.height(8.dp))
            if (tools.isEmpty()) {
                Text(
                    "还没有启用任何工具",
                    style = MaterialTheme.typography.bodySmall,
                    color = UiTokens.InkSub,
                )
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(tools, key = { it.id }) { tool ->
                        val shape = remember(tool.id) { iconShape(QuickTools.ICON_CONTOUR) }
                        val bitmap = remember(tool.id) { QuickTools.icon(context, tool).asImageBitmap() }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Image(
                                bitmap = bitmap,
                                contentDescription = tool.label,
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(shape),
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                tool.short,
                                style = MaterialTheme.typography.labelSmall,
                                color = UiTokens.InkSub,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }

        // 用法：三行，标题与说明同行，不占额外高度
        Panel {
            Text(
                "怎么用",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = UiTokens.Ink,
            )
            Spacer(Modifier.height(6.dp))
            StepLine("1", "开启无障碍服务")
            StepLine("2", "把应用放进轮盘")
            StepLine("3", "角落上滑呼出")
            Spacer(Modifier.height(6.dp))
            Text(
                "呼出后滑到图标松手即启动 · 点「•••」看全部应用 · 长按图标可移除",
                style = MaterialTheme.typography.labelSmall,
                color = UiTokens.InkSub,
            )
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NeuButton("预览轮盘", modifier = Modifier.weight(1f), primary = true) {
                PilotService.instance?.previewWheel()
            }
            NeuButton("显示热区", modifier = Modifier.weight(1f)) {
                PilotService.instance?.highlightHotspots()
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

/** 统一卡片外壳：外凸，内部平铺 */
@Composable
private fun Panel(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .neuRaised(RoundedCornerShape(UiTokens.RadiusCard), blur = 16f, dist = 7f),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) { content() }
    }
}

/**
 * 服务状态行：一行说清「名字 + 结果」，未就绪时右侧直接给一个可点的动作，
 * 不用再往下找按钮。
 */
@Composable
private fun StatusRow(
    title: String,
    ok: Boolean,
    onAction: (() -> Unit)?,
    actionText: String,
    offText: String = "未开启",
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (ok) UiTokens.Primary else UiTokens.FieldHint),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
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
                    .clip(RoundedCornerShape(50))
                    .background(UiTokens.PrimarySoft)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onAction,
                    )
                    .padding(horizontal = 12.dp, vertical = 4.dp),
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
                fontWeight = FontWeight.Bold,
                color = UiTokens.Ink,
            )
            if (unit.isNotEmpty()) {
                Text(
                    unit,
                    style = MaterialTheme.typography.labelSmall,
                    color = UiTokens.InkSub,
                    modifier = Modifier.padding(start = 1.dp, bottom = 3.dp),
                )
            }
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = UiTokens.InkSub)
    }
}

/** 单行步骤：编号 + 文案，三行加起来只占一张卡 */
@Composable
private fun StepLine(index: String, text: String) {
    Row(
        modifier = Modifier.padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(UiTokens.Primary),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                index,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = UiTokens.Ink,
        )
    }
}

@Composable
private fun SoftDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .height(1.dp)
            .background(UiTokens.Divider),
    )
}
