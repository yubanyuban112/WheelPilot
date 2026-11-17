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
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import priv.kit.ui.PrivilegeScaffold


// 设置页：全部参数

internal enum class Sheet {
    NONE, RADIUS, ICON, ANGLE, HOT_W, HOT_H, HOT_INSET,
    CLOSE_X, CLOSE_DISTANCE, CLOSE_DURATION, CLOSE_BOTTOM,
}

// ---------------------------------------------------------------------------
// 设置页：全部参数
// ---------------------------------------------------------------------------

@Composable
internal fun SettingsScreen(onOpenPrivilege: () -> Unit) {
    val context = LocalContext.current

    var left by remember { mutableStateOf(Prefs.leftEnabled(context)) }
    var right by remember { mutableStateOf(Prefs.rightEnabled(context)) }
    var radius by remember { mutableStateOf(Prefs.radiusDp(context)) }
    var icon by remember { mutableStateOf(Prefs.iconDp(context)) }
    var angle by remember { mutableStateOf(Prefs.angle(context)) }
    var hotW by remember { mutableStateOf(Prefs.hotWidth(context)) }
    var hotH by remember { mutableStateOf(Prefs.hotHeight(context)) }
    var inset by remember { mutableStateOf(Prefs.hotInset(context)) }
    var handle by remember { mutableStateOf(Prefs.cornerHandle(context)) }
    var passThrough by remember { mutableStateOf(Prefs.hotspotPassThrough(context)) }
    var hideRecents by remember { mutableStateOf(Prefs.hideFromRecents(context)) }
    var haptic by remember { mutableStateOf(Prefs.haptic(context)) }
    var wheelHint by remember { mutableStateOf(Prefs.showWheelHint(context)) }
    var dismiss by remember { mutableStateOf(Prefs.dismissOutside(context)) }
    var launchMode by remember { mutableStateOf(Prefs.launchMode(context)) }
    var closeMode by remember { mutableStateOf(Prefs.closeMode(context)) }
    var closeX by remember { mutableStateOf(Prefs.closeX(context)) }
    var closeDistance by remember { mutableStateOf(Prefs.closeDistance(context)) }
    var closeDuration by remember { mutableStateOf(Prefs.closeDuration(context)) }
    var closeBottom by remember { mutableStateOf(Prefs.closeFromBottom(context)) }
    var pauseLandscape by remember { mutableStateOf(Prefs.pauseLandscape(context)) }
    var pauseGame by remember { mutableStateOf(Prefs.pauseGame(context)) }
    var pauseKeyboard by remember { mutableStateOf(Prefs.pauseKeyboard(context)) }
    var verboseLog by remember { mutableStateOf(Prefs.verboseLog(context)) }
    var wechatNotify by remember { mutableStateOf(Prefs.wechatNotify(context)) }
    var showLogDialog by remember { mutableStateOf(false) }
    var logText by remember { mutableStateOf("") }
    var sheet by remember { mutableStateOf(Sheet.NONE) }

    // 一级菜单折叠状态：key = 组名，value = 是否收起
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }
    // 默认收起：首次进入只看到分组标题，点开哪组看哪组
    fun folded(name: String) = collapsed[name] ?: true

    // 确认时同时更新本地状态与持久化（本地状态驱动行文案）
    fun applySlider(target: Sheet, value: Float) {
        when (target) {
            Sheet.RADIUS -> { radius = value; Prefs.setRadiusDp(context, value) }
            Sheet.ICON -> { icon = value; Prefs.setIconDp(context, value) }
            Sheet.ANGLE -> { angle = value; Prefs.setAngle(context, value) }
            Sheet.HOT_W -> { hotW = value; Prefs.setHotWidth(context, value) }
            Sheet.HOT_H -> { hotH = value; Prefs.setHotHeight(context, value) }
            Sheet.HOT_INSET -> { inset = value; Prefs.setHotInset(context, value) }
            Sheet.CLOSE_X -> { closeX = value; Prefs.setCloseX(context, value) }
            Sheet.CLOSE_DISTANCE -> { closeDistance = value; Prefs.setCloseDistance(context, value) }
            Sheet.CLOSE_DURATION -> { closeDuration = value.toLong(); Prefs.setCloseDuration(context, closeDuration) }
            Sheet.CLOSE_BOTTOM -> { closeBottom = value; Prefs.setCloseFromBottom(context, value) }
            Sheet.NONE -> Unit
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        Text(
            "设置",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
        )

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item { SectionTitle("呼出方式", collapsed = folded("呼出方式")) { collapsed["呼出方式"] = !folded("呼出方式") } }
            if (!folded("呼出方式")) item {
                SwitchRow("左角落呼出", "屏幕左下角保留触发区", left) {
                    left = it
                    Prefs.setLeftEnabled(context, it)
                }
            }
            if (!folded("呼出方式")) item {
                SwitchRow("右角落呼出", "屏幕右下角保留触发区", right) {
                    right = it
                    Prefs.setRightEnabled(context, it)
                }
            }
            if (!folded("呼出方式")) item {
                SwitchRow("角落把手", "角落显示小圆点，点一下直接呼出", handle) {
                    handle = it
                    Prefs.setCornerHandle(context, it)
                }
            }

            item { SectionTitle("触发区", collapsed = folded("触发区")) { collapsed["触发区"] = !folded("触发区") } }
            if (!folded("触发区")) item { ValueRow("触发区宽度", "${hotW.toInt()} dp") { sheet = Sheet.HOT_W } }
            if (!folded("触发区")) item { ValueRow("触发区高度", "${hotH.toInt()} dp") { sheet = Sheet.HOT_H } }
            if (!folded("触发区")) item { ValueRow("边缘内缩", "${inset.toInt()} dp") { sheet = Sheet.HOT_INSET } }

            item { SectionTitle("轮盘外观", collapsed = folded("轮盘外观")) { collapsed["轮盘外观"] = !folded("轮盘外观") } }
            if (!folded("轮盘外观")) item { ValueRow("轮盘张角", "${angle.toInt()}°") { sheet = Sheet.ANGLE } }
            if (!folded("轮盘外观")) item { ValueRow("轮盘半径", "${radius.toInt()} dp") { sheet = Sheet.RADIUS } }
            if (!folded("轮盘外观")) item { ValueRow("图标大小", "${icon.toInt()} dp") { sheet = Sheet.ICON } }

            item { SectionTitle("交互行为", collapsed = folded("交互行为")) { collapsed["交互行为"] = !folded("交互行为") } }
            if (!folded("交互行为")) item {
                LaunchModeChips(launchMode) { value ->
                    launchMode = value
                    Prefs.setLaunchMode(context, value)
                }
            }
            if (!folded("交互行为")) item {
                InfoRow(
                    "荣耀小窗接口",
                    if (HonorWindowLauncher.isHonor(context)) "已启用（自动 / 厂商档）" else "非荣耀设备",
                )
            }
            if (!folded("交互行为")) item {
                SwitchRow("角落点击穿透", "点角落仍点到下面的应用；上滑和把手照常呼出", passThrough) {
                    passThrough = it
                    Prefs.setHotspotPassThrough(context, it)
                }
            }
            if (!folded("交互行为")) item {
                SwitchRow("触感反馈", "轮盘划过图标时轻微震动", haptic) {
                    haptic = it
                    Prefs.setHaptic(context, it)
                }
            }
            if (!folded("交互行为")) item {
                SwitchRow("呼出提示", "轮盘顶部的操作提示文字", wheelHint) {
                    wheelHint = it
                    Prefs.setShowWheelHint(context, it)
                }
            }
            if (!folded("交互行为")) item {
                SwitchRow("点击外部关闭", "点空白区域收起轮盘", dismiss) {
                    dismiss = it
                    Prefs.setDismissOutside(context, it)
                }
            }

            item { SectionTitle("关闭方式", collapsed = folded("关闭方式")) { collapsed["关闭方式"] = !folded("关闭方式") } }
            if (!folded("关闭方式")) item {
                CloseModeChips(closeMode) { value ->
                    closeMode = value
                    Prefs.setCloseMode(context, value)
                }
            }
            if (!folded("关闭方式")) item { ValueRow("落点 X", "${closeX.toInt()}%") { sheet = Sheet.CLOSE_X } }
            if (!folded("关闭方式")) item { ValueRow("上滑距离", "${closeDistance.toInt()} dp") { sheet = Sheet.CLOSE_DISTANCE } }
            if (!folded("关闭方式")) item { ValueRow("上滑时长", "${closeDuration} ms") { sheet = Sheet.CLOSE_DURATION } }
            if (!folded("关闭方式")) item { ValueRow("起点离底边", "${closeBottom.toInt()} dp") { sheet = Sheet.CLOSE_BOTTOM } }

            item { SectionTitle("场景暂停", collapsed = folded("场景暂停")) { collapsed["场景暂停"] = !folded("场景暂停") } }
            if (!folded("场景暂停")) item {
                SwitchRow("横屏时暂停", "横屏游戏、视频时不抢角落手势", pauseLandscape) {
                    pauseLandscape = it
                    Prefs.setPauseLandscape(context, it)
                }
            }
            if (!folded("场景暂停")) item {
                SwitchRow("游戏时暂停", "识别到游戏应用时自动让位", pauseGame) {
                    pauseGame = it
                    Prefs.setPauseGame(context, it)
                }
            }
            if (!folded("场景暂停")) item {
                SwitchRow("键盘弹出时让位", "有键盘时不注入上滑，避免误关", pauseKeyboard) {
                    pauseKeyboard = it
                    Prefs.setPauseKeyboard(context, it)
                }
            }

            item { SectionTitle("消息", collapsed = folded("消息")) { collapsed["消息"] = !folded("消息") } }
            if (!folded("消息")) item {
                SwitchRow("微信消息入口", "收到微信通知后轮盘里多一个聊天入口", wechatNotify) {
                    wechatNotify = it
                    Prefs.setWechatNotify(context, it)
                }
            }
            if (!folded("消息")) item {
                ValueRow(
                    "通知使用权",
                    if (PilotNotificationListener.isAuthorized()) "已开启" else "去开启",
                ) {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }
                }
            }
            if (!folded("消息")) item {
                ValueRow(
                    "使用情况访问",
                    if (ForegroundCheck.hasUsageAccess(context)) "已开启（小窗失败自动回落）" else "去开启（可检测小窗是否拉起）",
                ) {
                    ForegroundCheck.openUsageAccessSettings(context)
                }
            }

            item { SectionTitle("系统", collapsed = folded("系统")) { collapsed["系统"] = !folded("系统") } }
            if (!folded("系统")) item {
                SwitchRow("后台隐藏", "不在最近任务列表里显示本应用", hideRecents) {
                    hideRecents = it
                    Prefs.setHideFromRecents(context, it)
                    runCatching {
                        val am = context.getSystemService(ActivityManager::class.java)
                        am.appTasks.forEach { t -> t.setExcludeFromRecents(it) }
                    }
                }
            }

            item { SectionTitle("诊断", collapsed = folded("诊断")) { collapsed["诊断"] = !folded("诊断") } }
            if (!folded("诊断")) item {
                SwitchRow("详细日志", "记录呼出、启动与暂停事件，仅存本机", verboseLog) {
                    verboseLog = it
                    Prefs.setVerboseLog(context, it)
                }
            }
            if (!folded("诊断")) item {
                ValueRow(
                    "查看日志",
                    if (PilotLog.read(context).isBlank()) "暂无日志" else "点击查看",
                ) {
                    logText = PilotLog.read(context).take(4000)
                    showLogDialog = true
                }
            }
            if (!folded("诊断")) item {
                ValueRow("复制并清空日志", "") {
                    runCatching {
                        val cm = context.getSystemService(ClipboardManager::class.java)
                        cm.setPrimaryClip(ClipData.newPlainText("WheelPilot 日志", PilotLog.read(context)))
                        Toast.makeText(context, "日志已复制并清空", Toast.LENGTH_SHORT).show()
                    }
                    PilotLog.clear(context)
                }
            }

            item { SectionTitle("关于", collapsed = folded("关于")) { collapsed["关于"] = !folded("关于") } }
            if (!folded("关于")) item { InfoRow("版本", "1.0.0") }
            if (!folded("关于")) item { Spacer(Modifier.height(16.dp)) }
        }
    }

    when (sheet) {
        Sheet.NONE -> Unit
        else -> SliderSheetsHost(
            sheet = sheet,
            radius = radius, icon = icon, angle = angle,
            hotW = hotW, hotH = hotH, inset = inset,
            closeX = closeX, closeDistance = closeDistance,
            closeDuration = closeDuration, closeBottom = closeBottom,
            closeSheet = { sheet = Sheet.NONE },
            onConfirm = { target, value ->
                applySlider(target, value)
            },
        )
    }

    if (showLogDialog) {
        AlertDialog(
            onDismissRequest = { showLogDialog = false },
            containerColor = UiTokens.DialogSurface,
            title = { Text("WheelPilot 日志") },
            text = { Text(logText.ifBlank { "暂无日志" }) },
            confirmButton = {
                NeuButton(text = "复制", primary = true) {
                    val cm =
                        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(
                        ClipData.newPlainText("WheelPilot 日志", logText.ifBlank { "暂无日志" }),
                    )
                    Toast.makeText(context, "日志已复制", Toast.LENGTH_SHORT).show()
                }
            },
            dismissButton = {
                NeuButton(text = "关闭") { showLogDialog = false }
            },
        )
    }
}

/** 四档启动方式（移植同类小窗应用）：自动 / 厂商小窗 / 自由窗 / 全屏 */
@Composable
private fun LaunchModeChips(current: String, onPick: (String) -> Unit) {
    val context = LocalContext.current
    val options = listOf(
        Prefs.LAUNCH_AUTO to "自动",
        Prefs.LAUNCH_VENDOR to "厂商小窗",
        Prefs.LAUNCH_FREEFORM to "自由窗",
        Prefs.LAUNCH_FULL to "全屏",
    )
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text("启动方式", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, label) ->
                NeuChip(
                    text = label,
                    selected = current == value,
                ) {
                    if (Prefs.haptic(context)) Haptics.select(context)
                    onPick(value)
                }
            }
        }
    }
}

/** 关闭方式三选：点窗外 / 上滑 / 智能 */
@Composable
private fun CloseModeChips(current: Int, onPick: (Int) -> Unit) {
    val context = LocalContext.current
    val options = listOf(
        Prefs.CLOSE_TAP to "点窗外",
        Prefs.CLOSE_SWIPE to "上滑",
        Prefs.CLOSE_SMART to "智能",
    )
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text("关闭方式", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, label) ->
                NeuChip(
                    text = label,
                    selected = current == value,
                ) {
                    if (Prefs.haptic(context)) Haptics.select(context)
                    onPick(value)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "上滑只认底部落点附近，从图标起手不会误关；智能模式不抢正在点选的图标。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * 设置页全部滑杆弹窗的宿主。按当前 [sheet] 弹出对应 SliderSheet；
 * 触发区三档带实时预览（onValueChange 时调 PilotService 的 guide）。
 */
@Composable
private fun SliderSheetsHost(
    sheet: Sheet,
    radius: Float,
    icon: Float,
    angle: Float,
    hotW: Float,
    hotH: Float,
    inset: Float,
    closeX: Float,
    closeDistance: Float,
    closeDuration: Long,
    closeBottom: Float,
    closeSheet: () -> Unit,
    onConfirm: (Sheet, Float) -> Unit,
) {
    val plainDismiss = { closeSheet() }
    val hotDismiss = {
        closeSheet()
        PilotService.instance?.hideHotspotGuide()
        Unit
    }
    val hotPreview = { w: Float, h: Float, i: Float ->
        PilotService.instance?.showHotspotGuide(w, h, i)
        Unit
    }
    when (sheet) {
        Sheet.RADIUS -> SliderSheet("轮盘半径", radius, 120f..300f, "dp", { onConfirm(sheet, it) }, plainDismiss)
        Sheet.ICON -> SliderSheet("图标大小", icon, 36f..72f, "dp", { onConfirm(sheet, it) }, plainDismiss)
        Sheet.ANGLE -> SliderSheet("轮盘张角", angle, 45f..150f, "°", { onConfirm(sheet, it) }, plainDismiss)
        Sheet.HOT_W -> SliderSheet(
            "触发区宽度", hotW, 24f..120f, "dp", { onConfirm(sheet, it) }, hotDismiss,
            onValueChange = { hotPreview(it, hotH, inset) },
        )
        Sheet.HOT_H -> SliderSheet(
            "触发区高度", hotH, 16f..200f, "dp", { onConfirm(sheet, it) }, hotDismiss,
            onValueChange = { hotPreview(hotW, it, inset) },
        )
        Sheet.HOT_INSET -> SliderSheet(
            "边缘内缩", inset, 0f..60f, "dp", { onConfirm(sheet, it) }, hotDismiss,
            onValueChange = { hotPreview(hotW, hotH, it) },
        )
        Sheet.CLOSE_X -> SliderSheet("上滑落点 X", closeX, 0f..100f, "%", { onConfirm(sheet, it) }, plainDismiss)
        Sheet.CLOSE_DISTANCE -> SliderSheet("上滑距离", closeDistance, 24f..200f, "dp", { onConfirm(sheet, it) }, plainDismiss)
        Sheet.CLOSE_DURATION -> SliderSheet("上滑时长", closeDuration.toFloat(), 120f..900f, "ms", { onConfirm(sheet, it) }, plainDismiss)
        Sheet.CLOSE_BOTTOM -> SliderSheet("起点离底边", closeBottom, 0f..120f, "dp", { onConfirm(sheet, it) }, plainDismiss)
        Sheet.NONE -> Unit
    }
}

@Composable
internal fun SectionTitle(text: String, collapsed: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .neuPressable(RoundedCornerShape(12.dp), blur = 10f, dist = 5f) { onToggle() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 一级菜单 18sp 加粗，明确大于行内容 16sp（关于 > 版本 的层级关系）
        Text(
            text,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 18.sp),
            color = UiTokens.Primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        // 箭头也坐在凸起软垫圆底上，与全页拟物语言一致
        Box(
            modifier = Modifier
                .size(28.dp)
                .neuRaised(androidx.compose.foundation.shape.CircleShape, blur = 8f, dist = 4f),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (collapsed) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                contentDescription = if (collapsed) "展开" else "收纳",
                tint = UiTokens.InkSub,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
internal fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 3.dp)
            .neuPressable(blur = 8f, dist = 4f) {
                if (Prefs.haptic(context)) Haptics.select(context)
                onCheckedChange(!checked)
            }
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        // 状态不只靠颜色：文字 + 轨道填充 + 勾 + 旋钮位置，四重线索
        Text(
            if (checked) "已开启" else "关闭",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal,
            color = if (checked) UiTokens.Primary else UiTokens.InkSub,
        )
        Spacer(Modifier.width(8.dp))
        // 整行可点，开关只展示状态
        NeuSwitch(checked = checked, onCheckedChange = null)
    }
}

@Composable
internal fun ValueRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 3.dp)
            .neuPressable(blur = 8f, dist = 4f) { onClick() }
            .padding(horizontal = 8.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun InfoRow(title: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 3.dp)
            .glassRow()
            .padding(horizontal = 8.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun SliderSheet(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    unit: String,
    onConfirm: (Float) -> Unit,
    onDismiss: () -> Unit,
    onValueChange: ((Float) -> Unit)? = null,
) {
    var temp by remember { mutableStateOf(value) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = UiTokens.DialogSurface,
        title = {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = UiTokens.Ink,
            )
        },
        text = {
            Column {
                Text(
                    "${temp.toInt()} $unit",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Medium,
                    color = UiTokens.Primary,
                )
                Spacer(Modifier.height(12.dp))
                NeuSlider(
                    value = temp,
                    valueRange = range,
                    onValueChange = {
                        temp = it
                        onValueChange?.invoke(it)
                    },
                )
                Text(
                    "拖动即可实时预览",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            NeuButton(text = "确定", primary = true) {
                onConfirm(temp)
                onDismiss()
            }
        },
        dismissButton = {
            NeuButton(text = "取消") { onDismiss() }
        },
    )
}

// ---------------------------------------------------------------------------
// 数据
// ---------------------------------------------------------------------------

internal data class AppEntry(
    val pkg: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.ImageBitmap?,
    val letter: String = "#",
    val system: Boolean = false,
    /** 实测轮廓：圆角半径 / 短边，0=直角方形、0.5=圆形；底座与图标裁剪同用 */
    val contour: Float = DEFAULT_ICON_CONTOUR,
)
