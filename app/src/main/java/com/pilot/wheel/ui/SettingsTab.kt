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
import com.pilot.wheel.smartlink.SmartLinkFeature
import com.pilot.wheel.smartlink.rule.RuleRepository
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
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
import androidx.compose.ui.draw.alpha
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import priv.kit.ui.PrivilegeScaffold


// 设置页：全部参数

internal enum class Sheet {
    NONE, RADIUS, ICON, ANGLE, HOT_W, HOT_H, HOT_INSET, HOT_BOTTOM,
}

// ---------------------------------------------------------------------------
// 设置页：全部参数
// ---------------------------------------------------------------------------

@Composable
internal fun SettingsScreen(
    // 37 号改动（S1/1-1）：原 onOpenPrivilege 参数父级已传但本组件全文件无调用点（死事件），
    // 经确认为遗留参数，直接移除；特权入口在「小窗」页的状态行里。
    initialGroup: String? = null,
    onInitialGroupConsumed: () -> Unit = {},
) {
    val context = LocalContext.current

    var left by remember { mutableStateOf(Prefs.leftEnabled(context)) }
    var right by remember { mutableStateOf(Prefs.rightEnabled(context)) }
    var radius by remember { mutableStateOf(Prefs.radiusDp(context)) }
    var icon by remember { mutableStateOf(Prefs.iconDp(context)) }
    var angle by remember { mutableStateOf(Prefs.angle(context)) }
    var hotW by remember { mutableStateOf(Prefs.hotWidth(context)) }
    var hotH by remember { mutableStateOf(Prefs.hotHeight(context)) }
    var inset by remember { mutableStateOf(Prefs.hotInset(context)) }
    // 59 号：热区方向生效范围（点击循环切换）
    var hotOrient by remember { mutableStateOf(Prefs.hotOrientation(context)) }
    var handle by remember { mutableStateOf(Prefs.cornerHandle(context)) }
    var passThrough by remember { mutableStateOf(Prefs.hotspotPassThrough(context)) }
    var rawHotspot by remember { mutableStateOf(Prefs.rawHotspotEnabled(context)) }
    var hideRecents by remember { mutableStateOf(Prefs.hideFromRecents(context)) }
    var haptic by remember { mutableStateOf(Prefs.haptic(context)) }
    var hapticStrength by remember { mutableStateOf(Prefs.hapticStrength(context)) }
    var wheelHint by remember { mutableStateOf(Prefs.showWheelHint(context)) }
    var dismiss by remember { mutableStateOf(Prefs.dismissOutside(context)) }
    var closeOutside by remember { mutableStateOf(Prefs.closeOnOutside(context)) }
    var launchMode by remember { mutableStateOf(Prefs.launchMode(context)) }
    var pauseLandscape by remember { mutableStateOf(Prefs.pauseLandscape(context)) }
    var pauseGame by remember { mutableStateOf(Prefs.pauseGame(context)) }
    var pauseKeyboard by remember { mutableStateOf(Prefs.pauseKeyboard(context)) }
    var verboseLog by remember { mutableStateOf(Prefs.verboseLog(context)) }
    var wechatNotify by remember { mutableStateOf(Prefs.wechatNotify(context)) }
    var showLogDialog by remember { mutableStateOf(false) }
    var logText by remember { mutableStateOf("") }
    var sheet by remember { mutableStateOf(Sheet.NONE) }
    var hotBottom by remember { mutableStateOf(Prefs.hotBottom(context)) }
    var slots by remember { mutableStateOf(Prefs.slots(context)) }
    var blacklist by remember { mutableStateOf(Prefs.blacklist(context)) }
    var showBlacklistDialog by remember { mutableStateOf(false) }
    var lightOpenOn by remember { mutableStateOf(Prefs.lightOpenEnabled(context)) }
    var lightOpenCount by remember { mutableStateOf(Prefs.lightOpenScenes(context).size) }
    var usageAccess by remember { mutableStateOf(ForegroundCheck.hasUsageAccess(context)) }
    var showUsageGuide by remember { mutableStateOf(false) }
    // 37 号改动（S5/2-3…2-5/2-7）：以下行读的是非 Compose 单例状态，此前在组合内直读，
    // 动作后与授权返回后都不刷新。改为状态变量，在 LifecycleResumeEffect 与动作回调里刷新。
    var logBlank by remember { mutableStateOf(PilotLog.read(context).isBlank()) }
    var notifyAuth by remember { mutableStateOf(PilotNotificationListener.isAuthorized()) }

    // ---- 菜单形态 ----
    var menuStyle by remember { mutableStateOf(Prefs.quickMenuStyle(context)) }
    var doubleRow by remember { mutableStateOf(Prefs.doubleRow(context)) }
    var danmakuOn by remember { mutableStateOf(Prefs.danmaku(context)) }
    var smartLinkOn by remember { mutableStateOf(SmartLinkFeature.isEnabled(context)) }
    // 60 号：规则管理弹层（复制模板 / 导入 / 导出 / 恢复默认）
    var showRuleDialog by remember { mutableStateOf(false) }
    // 首帧就取真实条数：否则设置页会先显示「0 条规则」，要点进弹层才变（实测发现的观感缺陷）
    var ruleCount by remember {
        mutableStateOf(runCatching { RuleRepository(context.applicationContext).count() }.getOrDefault(0))
    }
    var ruleMsg by remember { mutableStateOf<String?>(null) }

    // 总开关、场景开关、使用情况访问都在系统/子页面里改，回到设置页要看到新值
    LifecycleResumeEffect(Unit) {
        lightOpenOn = Prefs.lightOpenEnabled(context)
        lightOpenCount = Prefs.lightOpenScenes(context).size
        usageAccess = ForegroundCheck.hasUsageAccess(context)
        // 37 号改动（S5）：从系统授权页/子页面返回时一并刷新这几行
        notifyAuth = PilotNotificationListener.isAuthorized()
        logBlank = PilotLog.read(context).isBlank()
        onPauseOrDispose { }
    }

    /**
     * 从包管理器读真实版本号：以前这里写死 "1.0.0"，和 build.gradle 的 versionName 对不上。
     * 3.19.2：同时取 versionCode —— 本项目 versionCode = 累计发包次数，直接读出来就是「第几个包」，
     * 不再依赖任何手写常量，跟 APK 文件名里的编号天然一致。
     */
    val versionName = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty().ifBlank { "未知" }
    }
    val versionCode = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        }.getOrDefault(0L)
    }

    // 两级菜单（IA 重构）：7 个大类 + 22 个分组，按用户任务（JTBD）切分。
    // 原 4 大类按「代码模块残留」切——「小窗与增强」一个分组塞 21 条、跨 5 个功能域；
    // 现按「用户进来找什么」切：外观 / 呼出 / 手势与关闭 / 打开方式 / 消息与通知 / 工具 / 系统与关于。
    // 分组按任务簇细分（呼出→呼出位置/触发区域/触摸方式/暂停场景/例外），
    // 每条 Pref key 仍只在一个 group 下渲染，存量配置与导入备份零丢失。
    data class Category(val title: String, val groups: List<String>)

    val categories = listOf(
        Category("外观", listOf("主题", "轮盘外观", "菜单形态")),
        Category("呼出", listOf("呼出位置", "触发区域", "触摸方式", "暂停场景", "例外")),
        Category("手势与关闭", listOf("触感", "其他")),
        Category("打开方式", listOf("启动方式", "轻量打开", "智能链接")),
        Category("消息与通知", listOf("消息入口", "通知呈现")),
        Category("系统与关于", listOf("备份", "日志", "应用")),
    )
    val groupToCategory: Map<String, String> = categories.flatMap { cat ->
        cat.groups.map { group -> group to cat.title }
    }.toMap()

    // 大类默认全部展开：进设置页就能同时看到一级（大类）与二级（分组）两层结构
    val categoryCollapsed = remember { mutableStateMapOf<String, Boolean>() }
    fun categoryFolded(title: String) = categoryCollapsed[title] ?: false

    // 分组内容默认收起：两级标题都露出来，具体条目按需展开，页面不被四十行条目撑长
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }
    fun folded(name: String): Boolean {
        if (categoryFolded(groupToCategory[name] ?: "")) return true
        return collapsed[name] ?: true
    }

    // 从小窗页轮盘卡片跳进来时，自动展开目标分组及其所属大类
    LaunchedEffect(initialGroup) {
        if (initialGroup != null) {
            val category = groupToCategory[initialGroup]
            if (category != null) categoryCollapsed[category] = false
            collapsed[initialGroup] = false
            onInitialGroupConsumed()
        }
    }

    // 轮盘外观改为页内滑杆后面：拖动即改本地状态（预览立刻跟手），停下 160ms 才落盘，
    // 顺手刷新正开着的轮盘 —— 不再需要弹窗确认，也不会因为每帧写 SharedPreferences 卡顿
    LaunchedEffect(radius) {
        delay(160L)
        Prefs.setRadiusDp(context, radius)
        PilotService.instance?.refreshWheel()
    }
    LaunchedEffect(icon) {
        delay(160L)
        Prefs.setIconDp(context, icon)
        PilotService.instance?.refreshWheel()
    }
    LaunchedEffect(angle) {
        delay(160L)
        Prefs.setAngle(context, angle)
        PilotService.instance?.refreshWheel()
    }

    // 确认时同时更新本地状态与持久化（本地状态驱动行文案）
    fun applySlider(target: Sheet, value: Float) {
        when (target) {
            Sheet.RADIUS -> { radius = value; Prefs.setRadiusDp(context, value) }
            Sheet.ICON -> { icon = value; Prefs.setIconDp(context, value) }
            Sheet.ANGLE -> { angle = value; Prefs.setAngle(context, value) }
            Sheet.HOT_W -> { hotW = value; Prefs.setHotWidth(context, value) }
            Sheet.HOT_H -> { hotH = value; Prefs.setHotHeight(context, value) }
            Sheet.HOT_INSET -> { inset = value; Prefs.setHotInset(context, value) }
            Sheet.HOT_BOTTOM -> { hotBottom = value; Prefs.setHotBottom(context, value) }
            Sheet.NONE -> Unit
        }
    }

    // ---------------------------------------------------------------------
    // 文件出入口：配置备份、日志导出都走系统文件选择器（SAF），不申请存储权限
    // ---------------------------------------------------------------------
    val backupFileName = remember {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.getDefault()).format(Date())
        PilotBackup.suggestedFileName(stamp)
    }

    val exportBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri != null) {
            runCatching { PilotBackup.exportToUri(context, uri) }
                .onSuccess { Toast.makeText(context, "配置已导出", Toast.LENGTH_SHORT).show() }
                .onFailure {
                    Toast.makeText(context, it.message ?: "导出失败", Toast.LENGTH_LONG).show()
                }
        }
    }

    val importBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching { PilotBackup.importFromUri(context, uri) }
                .onSuccess { count ->
                    // 恢复后立刻把界面状态同步成新配置，否则页面还显示导入前的旧值
                    left = Prefs.leftEnabled(context)
                    right = Prefs.rightEnabled(context)
                    radius = Prefs.radiusDp(context)
                    icon = Prefs.iconDp(context)
                    angle = Prefs.angle(context)
                    hotW = Prefs.hotWidth(context)
                    hotH = Prefs.hotHeight(context)
                    inset = Prefs.hotInset(context)
                    hotBottom = Prefs.hotBottom(context)
                    slots = Prefs.slots(context)
                    handle = Prefs.cornerHandle(context)
                    passThrough = Prefs.hotspotPassThrough(context)
                    hideRecents = Prefs.hideFromRecents(context)
                    haptic = Prefs.haptic(context)
                    hapticStrength = Prefs.hapticStrength(context)
                    wheelHint = Prefs.showWheelHint(context)
                    dismiss = Prefs.dismissOutside(context)
                    closeOutside = Prefs.closeOnOutside(context)
                    launchMode = Prefs.launchMode(context)
                    pauseLandscape = Prefs.pauseLandscape(context)
                    pauseGame = Prefs.pauseGame(context)
                    pauseKeyboard = Prefs.pauseKeyboard(context)
                    verboseLog = Prefs.verboseLog(context)
                    wechatNotify = Prefs.wechatNotify(context)
                    blacklist = Prefs.blacklist(context)
                    // 37 号改动（S6/2-13）：菜单形态与弹幕此前不在 resync 里，导入后显示旧值
                    menuStyle = Prefs.quickMenuStyle(context)
                    doubleRow = Prefs.doubleRow(context)
                    danmakuOn = Prefs.danmaku(context)
                    Toast.makeText(context, "已恢复 $count 项配置", Toast.LENGTH_SHORT).show()
                    PilotService.instance?.refreshWheel()
                }
                .onFailure {
                    Toast.makeText(context, it.message ?: "恢复失败", Toast.LENGTH_LONG).show()
                }
        }
    }

    val exportLog = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri != null) {
            runCatching {
                val text = PilotLog.read(context).ifBlank { "（无日志）" }
                context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) }
                    ?: throw IllegalStateException("无法写入该位置")
            }.onSuccess {
                Toast.makeText(context, "日志已导出", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(context, it.message ?: "导出失败", Toast.LENGTH_LONG).show()
            }
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
            color = UiTokens.Ink,
            modifier = Modifier.padding(start = UiSpace.Content, top = UiSpace.s, bottom = UiSpace.xxs),
        )

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            // 分组内容：一个分组 = 一条二级标题 + 收起时为空、展开时的若干条目。
            // 用 driver 统一遍历，替代之前「同一句 if (!folded(x))」写四十遍的结构
            // （那种写法一改分组名就会漏改，也是这次能顺手合并分组的底气）。
            fun addGroup(scope: LazyListScope, group: String) {
                scope.item(key = "grp_$group") {
                    SectionTitle(group, collapsed = folded(group)) {
                        collapsed[group] = !folded(group)
                    }
                }
                if (folded(group)) return

                when (group) {
                    "主题" -> scope.item(key = "g_theme") {
                        // 37 号改动（S10/4-5）：主题切换回调用父级作用域执行，ThemeChips
                        // 不再零回调直写 ThemeController/Prefs 单例
                        ThemeChips { theme -> ThemeController.select(context, theme) }
                    }

                    "轮盘外观" -> {
                        // 预览放在滑杆上方：拖动下面的滑杆，上面的轮盘立刻变 —— 这就是「实时预览」
                        scope.item(key = "g_wheel_preview") {
                            WheelAppearancePreview(
                                radiusDp = radius,
                                iconDp = icon,
                                angle = angle,
                                slots = slots,
                                // 3.19.2：预览跟随「呼出与触发」里的左右角开关 ——
                                // 双角各演示一角、单角放大居中、都不开时只给提示
                                leftEnabled = left,
                                rightEnabled = right,
                            )
                        }
                        scope.item(key = "g_wheel_radius") {
                            InlineSliderRow("轮盘半径", radius, 120f..300f, "dp") { radius = it }
                        }
                        scope.item(key = "g_wheel_icon") {
                            InlineSliderRow("图标大小", icon, 36f..72f, "dp") { icon = it }
                        }
                        scope.item(key = "g_wheel_angle") {
                            InlineSliderRow("轮盘张角", angle, 45f..150f, "°") { angle = it }
                        }
                        scope.item(key = "g_wheel_slots") {
                            SlotChips(current = slots, doubleRow = doubleRow) { value ->
                                slots = value
                                Prefs.setSlots(context, value)
                                PilotService.instance?.refreshWheel()
                            }
                        }
                        scope.item(key = "g_wheel_preview_btn") {
                            NeuButton(
                                "在屏幕上预览轮盘",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = UiSpace.Content, vertical = UiSpace.s),
                                primary = true,
                            ) {
                                PilotService.instance?.previewWheel()
                            }
                        }
                    }

                    "呼出位置" -> {
                        scope.item {
                            SwitchRow("左角落呼出", "屏幕左下角保留触发区", left) {
                                left = it
                                Prefs.setLeftEnabled(context, it)
                            }
                        }
                        scope.item {
                            SwitchRow("右角落呼出", "屏幕右下角保留触发区", right) {
                                right = it
                                Prefs.setRightEnabled(context, it)
                            }
                        }
                        scope.item {
                            SwitchRow("角落把手", "角落显示小圆点，点一下直接呼出", handle) {
                                handle = it
                                Prefs.setCornerHandle(context, it)
                            }
                        }
                    }

                    "触发区域" -> {
                        scope.item { ValueRow("触发区宽度", "${hotW.toInt()} dp") { sheet = Sheet.HOT_W } }
                        scope.item { ValueRow("触发区高度", "${hotH.toInt()} dp") { sheet = Sheet.HOT_H } }
                        scope.item { ValueRow("边缘内缩", "${inset.toInt()} dp") { sheet = Sheet.HOT_INSET } }
                        scope.item { ValueRow("热区离底高度", "${hotBottom.toInt()} dp") { sheet = Sheet.HOT_BOTTOM } }
                        // 59 号：点一下循环「竖屏与横屏 → 仅竖屏 → 仅横屏」，横屏游戏误触靠这一档收敛
                        scope.item {
                            ValueRow("生效方向", com.pilot.wheel.core.HotspotScope.label(hotOrient)) {
                                hotOrient = com.pilot.wheel.core.HotspotScope.next(hotOrient)
                                Prefs.setHotOrientation(context, hotOrient)
                            }
                        }
                    }

                    "触摸方式" -> {
                        scope.item {
                            SwitchRow(
                                "旁路触摸采集",
                                "热区不拦截任何触摸，感知走 Shizuku（monitorInput/getevent），失效自动回退无障碍 onMotionEvent",
                                rawHotspot,
                            ) {
                                rawHotspot = it
                                Prefs.setRawHotspotEnabled(context, it)
                            }
                        }
                        scope.item {
                            DetailRow(
                                "旁路触摸通道",
                                TouchLayerState.describe(rawHotspot),
                                onClick = {},
                            )
                        }
                        scope.item {
                            SwitchRow("角落点击穿透", "点角落仍点到下面的应用；上滑和把手照常呼出", passThrough) {
                                passThrough = it
                                Prefs.setHotspotPassThrough(context, it)
                            }
                        }
                    }

                    "触感" -> {
                        scope.item {
                            SwitchRow("触感反馈", "轮盘划过图标时轻微震动", haptic) {
                                haptic = it
                                Prefs.setHaptic(context, it)
                                if (it) Haptics.preview(context)
                            }
                        }
                        scope.item {
                            HapticStrengthChips(current = hapticStrength, enabled = haptic) { value ->
                                hapticStrength = value
                                // 先落盘再试振：preview 内部按 Prefs 里的档位取波形，
                                // 顺序反了就会「选了强档却按中档振」
                                Prefs.setHapticStrength(context, value)
                                Haptics.preview(context)
                            }
                        }
                        scope.item {
                            DetailRow("试一下这个强度", "按当前档位振一次，不满意就换") {
                                Haptics.preview(context)
                            }
                        }
                    }

                    "其他" -> {
                        scope.item {
                            SwitchRow("呼出提示", "轮盘顶部的操作提示文字", wheelHint) {
                                wheelHint = it
                                Prefs.setShowWheelHint(context, it)
                            }
                        }
                        scope.item {
                            SwitchRow("点击外部关闭", "点空白区域收起轮盘", dismiss) {
                                dismiss = it
                                Prefs.setDismissOutside(context, it)
                            }
                        }
                        scope.item {
                            SwitchRow(
                                "点窗外即关小窗",
                                "点在小窗外面就把小窗关掉，即用即走",
                                closeOutside,
                            ) {
                                closeOutside = it
                                Prefs.setCloseOnOutside(context, it)
                            }
                        }
                    }

                    "启动方式" -> {
                        scope.item {
                            LaunchModeChips(launchMode) { value ->
                                launchMode = value
                                Prefs.setLaunchMode(context, value)
                            }
                        }
                        scope.item {
                            InfoRow(
                                "荣耀小窗接口",
                                if (HonorWindowLauncher.isHonor(context)) "已启用（自动 / 厂商档）" else "非荣耀设备",
                            )
                        }
                    }

                    "暂停场景" -> {
                        scope.item {
                            SwitchRow("横屏时暂停", "横屏游戏、视频时不抢角落手势", pauseLandscape) {
                                pauseLandscape = it
                                Prefs.setPauseLandscape(context, it)
                            }
                        }
                        scope.item {
                            SwitchRow("游戏时暂停", "识别到游戏应用时自动让位", pauseGame) {
                                pauseGame = it
                                Prefs.setPauseGame(context, it)
                            }
                        }
                        scope.item {
                            SwitchRow(
                                "键盘弹出时让位",
                                "打字时热区不拦触摸，删除、大写键正常点；键盘收起自动恢复",
                                pauseKeyboard,
                            ) {
                                pauseKeyboard = it
                                Prefs.setPauseKeyboard(context, it)
                            }
                        }
                    }

                    "例外" -> {
                        scope.item {
                            ValueRow("不呼出的应用", if (blacklist.isEmpty()) "未设置" else "${blacklist.size} 个") {
                                showBlacklistDialog = true
                            }
                        }
                    }

                    "轻量打开" -> {
                        scope.item { BetaWarningCard() }
                        scope.item {
                            SwitchRow("总开关", "7 个场景下改用浮窗打开，而不是全屏拉起", lightOpenOn) { on ->
                                lightOpenOn = on
                                Prefs.setLightOpenEnabled(context, on)
                            }
                        }
                        scope.item {
                            ValueRow(
                                "场景与运行条件",
                                if (lightOpenCount == 0) {
                                    "全部关闭"
                                } else {
                                    "已开 $lightOpenCount / ${com.pilot.wheel.core.LightOpenScene.entries.size}"
                                },
                            ) {
                                runCatching {
                                    context.startActivity(
                                        Intent(context, com.pilot.wheel.LightOpenActivity::class.java),
                                    )
                                }
                            }
                        }
                    }

                    "消息入口" -> {
                        scope.item {
                            SwitchRow("微信消息入口", "来消息时置顶显示带红点的微信图标，点开后自动移除", wechatNotify) {
                                wechatNotify = it
                                Prefs.setWechatNotify(context, it)
                            }
                        }
                        scope.item {
                            ValueRow(
                                "通知使用权",
                                // 37 号改动（S5/2-6）：改读响应式变量，从系统页授权返回后文案刷新
                                if (notifyAuth) "已开启" else "去开启",
                            ) {
                                runCatching {
                                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                                }
                            }
                        }
                        scope.item {
                            DetailRow(
                                "使用情况访问",
                                if (usageAccess) {
                                    "已开启 · 荣耀原生小窗验证可用，拉起失败会自动回落"
                                } else {
                                    "未开启 · 点这里授予后，原生小窗才能验证是否真的拉起"
                                },
                            ) { showUsageGuide = true }
                        }
                    }

                    "菜单形态" -> {
                        scope.item {
                            InfoRow("排列方式", if (doubleRow) "双排" else "单排")
                        }
                        scope.item {
                            OptionChips(
                                current = doubleRow,
                                options = listOf(
                                    false to "单排",
                                    true to "双排",
                                ),
                            ) {
                                doubleRow = it
                                Prefs.setDoubleRow(context, it)
                                PilotService.instance?.refreshWheel()
                            }
                        }
                        scope.item {
                            InfoRow("形状", if (menuStyle == Prefs.MENU_STYLE_VERTICAL) "竖形" else "扇形")
                        }
                        scope.item {
                            OptionChips(
                                current = menuStyle,
                                options = listOf(
                                    Prefs.MENU_STYLE_ARC to "扇形",
                                    Prefs.MENU_STYLE_VERTICAL to "竖形",
                                ),
                            ) {
                                menuStyle = it
                                Prefs.setQuickMenuStyle(context, it)
                                PilotService.instance?.refreshWheel()
                            }
                        }
                    }

                    "智能链接" -> {
                        scope.item {
                            SwitchRow(
                                "智能链接悬浮",
                                "检测分享链接后左下角弹胶囊，点按以小窗打开；默认关，开关即时生效",
                                smartLinkOn,
                            ) {
                                smartLinkOn = it
                                SmartLinkFeature.setEnabled(context, it)
                            }
                        }
                        scope.item {
                            DetailRow(
                                "规则管理",
                                "$ruleCount 条规则 · 复制模板 / 导入 / 导出",
                            ) {
                                ruleCount = RuleRepository(context.applicationContext).count()
                                ruleMsg = null
                                showRuleDialog = true
                            }
                        }
                    }

                    "通知呈现" -> {
                        scope.item {
                            SwitchRow("微信/QQ 弹幕", "横屏时把聊天消息飘在屏幕上（不拦触摸）", danmakuOn) {
                                danmakuOn = it
                                Prefs.setDanmaku(context, it)
                            }
                        }
                    }

                    "备份" -> {
                        scope.item {
                            DetailRow("导出配置", "槽位、外观、行为参数存成一个文本文件") {
                                exportBackup.launch(backupFileName)
                            }
                        }
                        scope.item {
                            DetailRow("从文件恢复配置", "逐项覆盖同名设置，不动备份里没有的项") {
                                importBackup.launch(arrayOf("text/plain", "*/*"))
                            }
                        }
                    }

                    "日志" -> {
                        scope.item {
                            SwitchRow("详细日志", "记录呼出、启动与暂停事件，仅存本机", verboseLog) {
                                verboseLog = it
                                Prefs.setVerboseLog(context, it)
                            }
                        }
                        scope.item {
                            ValueRow(
                                "查看日志",
                                // 37 号改动（S5/2-5）：改读响应式变量，清空日志后行文案立即刷新
                                if (logBlank) "暂无日志" else "点击查看",
                            ) {
                                logText = PilotLog.read(context).take(4000)
                                showLogDialog = true
                            }
                        }
                    }

                    "应用" -> {
                        scope.item {
                            SwitchRow("后台隐藏", "不在最近任务列表里显示本应用", hideRecents) {
                                hideRecents = it
                                Prefs.setHideFromRecents(context, it)
                                runCatching {
                                    val am = context.getSystemService(ActivityManager::class.java)
                                    am.appTasks.forEach { t -> t.setExcludeFromRecents(it) }
                                }
                            }
                        }
                        // 3.19.2：版本行带上「第几个包」（versionCode = 累计发包次数）
                        scope.item {
                            InfoRow(
                                "版本",
                                if (versionCode > 0L) "$versionName · 第 $versionCode 个包" else versionName,
                            )
                        }
                    }
                }
            }

            categories.forEach { cat ->
                item(key = "cat_${cat.title}") {
                    CategoryHeader(cat.title, collapsed = categoryFolded(cat.title)) {
                        categoryCollapsed[cat.title] = !categoryFolded(cat.title)
                    }
                }
                if (!categoryFolded(cat.title)) {
                    cat.groups.forEach { group -> addGroup(this, group) }
                }
            }
            item(key = "settings_bottom") { Spacer(Modifier.height(UiSpace.l)) }
        }
    }

    when (sheet) {
        Sheet.NONE -> Unit
        else -> SliderSheetsHost(
            sheet = sheet,
            radius = radius, icon = icon, angle = angle,
            hotW = hotW, hotH = hotH, inset = inset, hotBottom = hotBottom,
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
            // 日志最多塞 4000 字，AlertDialog 的 text 槽不会自带滚动：
            // 直接放 Text 时超出一屏的部分被裁掉且无法拖动，用户只能看到开头几十行。
            // IA 重构：复制/导出/清空三个动作从「系统与诊断」平铺的三条收敛到这里，
            // 与「详细日志」「查看日志」合并为一个「日志」分组入口。
            text = {
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(UiSpace.s)) {
                        NeuButton("复制", primary = true) {
                            val cm =
                                context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            cm?.setPrimaryClip(
                                ClipData.newPlainText("WheelPilot 日志", logText.ifBlank { "暂无日志" }),
                            )
                            Toast.makeText(context, "日志已复制", Toast.LENGTH_SHORT).show()
                        }
                        NeuButton("导出") { exportLog.launch("wheelpilot-log.txt") }
                        NeuButton("清空") {
                            PilotLog.clear(context)
                            logText = ""
                            logBlank = true
                            Toast.makeText(context, "日志已清空", Toast.LENGTH_SHORT).show()
                        }
                    }
                    Spacer(Modifier.height(UiSpace.s))
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        Text(logText.ifBlank { "暂无日志" })
                    }
                }
            },
            confirmButton = {
                NeuButton(text = "关闭") { showLogDialog = false }
            },
        )
    }

    if (showUsageGuide) {
        AlertDialog(
            onDismissRequest = { showUsageGuide = false },
            containerColor = UiTokens.DialogSurface,
            title = { Text("使用情况访问权限") },
            text = {
                Column {
                    Text(
                        if (usageAccess) {
                            "已授予。荣耀原生小窗（mode 102）拉起后会用前台检测确认窗口真的出现；" +
                                "拉不起来会自动回落到普通小窗，不会卡在全屏。"
                        } else {
                            "荣耀原生小窗是一条无返回值的系统接口，调用成功不代表窗口真的出现。" +
                                "授予本权限后，WheelPilot 才能检测小窗是否拉起，失败时自动回落到普通小窗。" +
                                "不授予也能用，但只能用特权 shell 检测（需要 Shizuku/Root 在线）。"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(UiSpace.ms))
                    Text(
                        "开启路径：系统设置 → 应用 → 特殊应用权限 → 使用情况访问 → 找到 WheelPilot 打开。" +
                            "开启后回到这里，状态会自动刷新。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                NeuButton(text = "去开启", primary = true) {
                    showUsageGuide = false
                    runCatching { ForegroundCheck.openUsageAccessSettings(context) }
                }
            },
            dismissButton = {
                NeuButton(text = "关闭") { showUsageGuide = false }
            },
        )
    }

    if (showRuleDialog) {
        val repo = remember { RuleRepository(context.applicationContext) }
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        AlertDialog(
            onDismissRequest = { showRuleDialog = false },
            containerColor = UiTokens.DialogSurface,
            title = { Text("链接规则") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        "当前 $ruleCount 条。导入按 ruleId 合并：同 id 覆盖、新 id 追加，不删未提及的规则。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(UiSpace.ms))
                    Text(
                        "子域自动匹配：写 taobao.com 可匹配 m.taobao.com；带协议、路径或通配符会被整批拒绝。" +
                            "目标 App 未安装的规则不会弹胶囊。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ruleMsg?.let { msg ->
                        Spacer(Modifier.height(UiSpace.m))
                        Text(
                            msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (msg.startsWith("导入失败")) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                    }
                    Spacer(Modifier.height(UiSpace.m))
                    Column(verticalArrangement = Arrangement.spacedBy(UiSpace.s)) {
                        NeuButton("复制规则模板", modifier = Modifier.fillMaxWidth()) {
                            cm?.setPrimaryClip(
                                ClipData.newPlainText("SmartLink 规则模板", repo.templateJson()),
                            )
                            Toast.makeText(context, "模板已复制到剪贴板", Toast.LENGTH_SHORT).show()
                        }
                        NeuButton(
                            "从剪贴板导入",
                            modifier = Modifier.fillMaxWidth(),
                            primary = true,
                        ) {
                            val text = cm?.primaryClip
                                ?.takeIf { it.itemCount > 0 }
                                ?.getItemAt(0)
                                ?.coerceToText(context)
                                ?.toString()
                            ruleMsg = if (text.isNullOrBlank()) {
                                "剪贴板为空"
                            } else {
                                repo.importJson(text).message()
                            }
                            ruleCount = repo.count()
                        }
                        NeuButton("导出当前规则到剪贴板", modifier = Modifier.fillMaxWidth()) {
                            cm?.setPrimaryClip(
                                ClipData.newPlainText("SmartLink 规则", repo.exportJson()),
                            )
                            Toast.makeText(context, "已复制 $ruleCount 条规则", Toast.LENGTH_SHORT).show()
                        }
                        NeuButton("恢复默认规则", modifier = Modifier.fillMaxWidth()) {
                            ruleMsg = "已恢复 ${repo.resetToDefaults()} 条默认规则"
                            ruleCount = repo.count()
                        }
                    }
                    Spacer(Modifier.height(UiSpace.m))
                    Text(
                        "外部导入接口：分享 json 文件给「轻窗 · 规则导入」，或 adb 执行 " +
                            "am start -a com.pilot.wheel.IMPORT_RULES --es json '<JSON>'",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                NeuButton("关闭") { showRuleDialog = false }
            },
        )
    }

    if (showBlacklistDialog) {
        val apps = rememberLaunchableApps(context)
        AlertDialog(
            onDismissRequest = { showBlacklistDialog = false },
            containerColor = UiTokens.DialogSurface,
            title = { Text("不呼出轮盘的应用") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    // 37 号改动（S5/2-7）：改读响应式变量，授权状态变化后提示行刷新
                    if (!usageAccess) {
                        Text(
                            "未开启「使用情况访问」时识别不到前台应用，黑名单不会生效。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(UiSpace.s))
                    }
                    apps.forEach { app ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = app.pkg in blacklist,
                                onCheckedChange = { checked ->
                                    val next =
                                        if (checked) blacklist + app.pkg else blacklist - app.pkg
                                    blacklist = next
                                    Prefs.setBlacklist(context, next)
                                },
                            )
                            Text(app.label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {
                NeuButton(text = "完成", primary = true) { showBlacklistDialog = false }
            },
        )
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
            .padding(horizontal = UiSpace.l, vertical = 0.dp)
            // UI UX Pro Max · Accessibility：整行开关补 role=Switch + 状态朗读
            .semantics(mergeDescendants = true) {
                role = Role.Switch
                stateDescription = if (checked) "已开启" else "已关闭"
            }
            .flatRow {
                if (Prefs.haptic(context)) Haptics.select(context)
                onCheckedChange(!checked)
            }
            .padding(horizontal = UiSpace.s, vertical = UiSpace.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(UiSpace.xxs))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(UiSpace.m))
        // 状态不只靠颜色：文字 + 轨道填充 + 勾 + 旋钮位置，四重线索
        Text(
            if (checked) "已开启" else "关闭",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal,
            color = if (checked) UiTokens.Primary else UiTokens.InkSub,
        )
        Spacer(Modifier.width(UiSpace.s))
        // 整行可点，开关只展示状态
        NeuSwitch(checked = checked, onCheckedChange = null)
    }
}

@Composable
internal fun ValueRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = UiSpace.l, vertical = 0.dp)
            .semantics(mergeDescendants = true) { role = Role.Button }
            .flatRow { onClick() }
            .padding(horizontal = UiSpace.s, vertical = UiSpace.s),
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

/**
 * 说明型行：标题一行、说明一行，右侧不再挤文字。
 * 原来这类行把长说明塞在右侧（`从文件恢复配置` 被压成「从文件恢复配 / 置」），
 * 现在说明独占一行，字号与 [SwitchRow] 的二级文案一致，整页排版才统一。
 */
@Composable
internal fun DetailRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = UiSpace.l, vertical = 0.dp)
            .semantics(mergeDescendants = true) { role = Role.Button }
            .flatRow { onClick() }
            .padding(horizontal = UiSpace.s, vertical = UiSpace.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(UiSpace.xxs))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(UiSpace.ms))
        // 右侧不再放文字，用一个浅箭头表示「点了会有下一步」
        Icon(
            imageVector = Icons.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = UiTokens.FieldHint,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
internal fun InfoRow(title: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = UiSpace.l, vertical = 0.dp)
            // 纯展示行：不压阴影、不加分隔（3.19.2 起条目统一扁平行）
            .flatRow(enabled = false, showDivider = false) { }
            .padding(horizontal = UiSpace.s, vertical = UiSpace.s),
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

/**
 * 多行说明的纯展示块（38 号改动 B2）：标题在上、说明整行铺开在下。
 * 横排 [InfoRow] 适合「标题短 + 值短」；说明超过一行时值不占权重，
 * 会把 weight(1f) 的标题挤到 0 宽，视觉上标题与说明叠在同一处。
 * 补偿说明 / 分享面板两行已改用本组件。
 */
@Composable
internal fun InfoBlock(title: String, description: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = UiSpace.l, vertical = 0.dp)
            // 与 InfoRow 同底：纯展示行，不压阴影、不加分隔
            .flatRow(enabled = false, showDivider = false) { }
            .padding(horizontal = UiSpace.s, vertical = UiSpace.s),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(UiSpace.xxs))
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 槽位数：单排 6–8、双排 6–15，呼出轮盘时显示的图标数 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SlotChips(current: Int, doubleRow: Boolean, onPick: (Int) -> Unit) {
    val context = LocalContext.current
    val max = if (doubleRow) com.pilot.wheel.core.ConfigRules.MAX_SLOTS_DOUBLE
    else com.pilot.wheel.core.ConfigRules.MAX_SLOTS
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = UiSpace.Content, vertical = UiSpace.s)) {
        Text("轮盘槽位", style = MaterialTheme.typography.bodyLarge, color = UiTokens.Ink)
        Spacer(Modifier.height(UiSpace.s))
        // 双排最多 10 枚 chip，单排只有 3 枚；用 FlowRow 换行，不用 Row 硬挤
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(UiSpace.s),
            verticalArrangement = Arrangement.spacedBy(UiSpace.s),
        ) {
            (com.pilot.wheel.core.ConfigRules.MIN_SLOTS..max).forEach { value ->
                NeuChip(text = "$value 个", selected = current == value) {
                    if (Prefs.haptic(context)) Haptics.select(context)
                    onPick(value)
                }
            }
        }
    }
}

/** 五选一主题：液态玻璃 / 经典拟物 / Apple 流体 / Apple 暗色 / 自动。切换立即生效并持久化 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThemeChips(onThemeChange: (UiTheme) -> Unit) {
    val context = LocalContext.current
    val current = ThemeController.current
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = UiSpace.Content, vertical = UiSpace.s)) {
        // 分组标题已由 SectionTitle 承担，这里不再重复渲染"主题"，
        // 否则同一屏会出现两个"主题"上下叠着，看起来像控件重叠
        // verticalArrangement 必须显式给：FlowRow 默认行间距为 0，
        // 五枚 chip 换行后第二行会直接顶在第一行上；
        // 再叠上 chip 自身画在边界外 4dp 的柔光，两行看上去就是重叠的。
        // 行距取 12dp：盖过两枚 chip 各自外扩的 8dp 阴影，留出净空。
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(UiSpace.s),
            verticalArrangement = Arrangement.spacedBy(UiSpace.m),
        ) {
            UiTheme.entries.forEach { theme ->
                NeuChip(text = theme.label, selected = current == theme) {
                    if (Prefs.haptic(context)) Haptics.select(context)
                    // 37 号改动（S10/4-5）：写单例的副作用上抛给父级（SettingsScreen）
                    onThemeChange(theme)
                }
            }
        }
        Spacer(Modifier.height(UiSpace.m))
        Text(
            when (current) {
                UiTheme.LIQUID_GLASS -> "深色渐变底 + 半透明玻璃面板，Apple 流动玻璃风格"
                UiTheme.APPLE -> "iOS 浅色毛玻璃材质 + 系统色板，Apple 弹簧动效"
                UiTheme.APPLE_DARK -> "Apple 同一套纪律的暗色表面：纯黑画布、#2997FF 强调、材料高光反相"
                UiTheme.CLASSIC -> "v2.8 以来的原有风格，原样保留"
                UiTheme.AUTO -> "跟随系统明暗：亮色用经典拟物、暗色用 Apple 暗色"
            },
            style = MaterialTheme.typography.bodySmall,
            color = UiTokens.InkSub,
        )
    }
}

/** 四档启动方式：自动 / 厂商小窗 / 自由窗 / 全屏 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LaunchModeChips(current: String, onPick: (String) -> Unit) {
    val context = LocalContext.current
    val options = listOf(
        Prefs.LAUNCH_AUTO to "自动",
        Prefs.LAUNCH_VENDOR to "厂商小窗",
        Prefs.LAUNCH_FREEFORM to "自由窗",
        Prefs.LAUNCH_FULL to "全屏",
    )
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = UiSpace.Content, vertical = UiSpace.s)) {
        Text("启动方式", style = MaterialTheme.typography.bodyLarge, color = UiTokens.Ink)
        Spacer(Modifier.height(UiSpace.s))
        // 四枚 chip 单排合计约 268dp，加上左右各 28dp 边距后 360dp 屏仅余 36dp；
        // 系统字号放大到 1.3 倍就顶到边界，Text 会在 32dp 高的 chip 里折行并被裁掉。
        // 换成可换行的 FlowRow：宽度不够时第二行承接，不再压边界
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(UiSpace.s),
            verticalArrangement = Arrangement.spacedBy(UiSpace.s),
        ) {
            options.forEach { (value, label) ->
                NeuChip(text = label, selected = current == value) {
                    if (Prefs.haptic(context)) Haptics.select(context)
                    onPick(value)
                }
            }
        }
    }
}

/**
 * 通用多选一（系统增强里的「边侧」「菜单样式」共用）。
 * 与 LaunchModeChips 同一套视觉：NeoChip 一排铺开，选中态由调用方给。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> OptionChips(
    current: T,
    options: List<Pair<T, String>>,
    onPick: (T) -> Unit,
) {
    val context = LocalContext.current
    // 与 LaunchModeChips 同理：单排宽度不可控时换行，不用 Row 硬挤
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = UiSpace.Content, vertical = UiSpace.s),
        horizontalArrangement = Arrangement.spacedBy(UiSpace.s),
        verticalArrangement = Arrangement.spacedBy(UiSpace.s),
    ) {
        options.forEach { (value, label) ->
            NeuChip(text = label, selected = current == value) {
                if (Prefs.haptic(context)) Haptics.select(context)
                onPick(value)
            }
        }
    }
}

/**
 * 一级菜单（大类）标题。
 * 3.18.1：字号从 titleMedium(16sp) 提到 title(22sp) —— 之前大类 16sp 而二级分组 18sp，
 * 父级比子级还小，层级是反的。现在 22 > 18 > 16（条目），一眼能读出谁是谁的父级。
 * 3.19.0：上下间距拉开到 20/8，并加一条发丝线收尾 —— 大类之间、大类与它的第一个
 * 分组标题之间不再紧贴（此前 top 14 / bottom 4，折叠状态下 8 个标题会连成一片）。
 */
@Composable
private fun CategoryHeader(title: String, collapsed: Boolean, onToggle: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = UiSpace.Content)
                .padding(top = UiSpace.xl, bottom = UiSpace.s)
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    stateDescription = if (collapsed) "$title，已折叠" else "$title，已展开"
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onToggle() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(width = 4.dp, height = 24.dp)
                    .clip(RoundedCornerShape(UiTokens.IndicatorRadius))
                    .background(UiTokens.Primary),
            )
            Spacer(Modifier.width(UiSpace.ms))
            Text(
                title,
                style = UiType.title,
                fontWeight = FontWeight.Bold,
                color = UiTokens.Ink,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (collapsed) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                contentDescription = null,
                tint = UiTokens.Primary,
                modifier = Modifier.size(UiTokens.IconMd),
            )
        }
        // 发丝线：一级大类的视觉收口，与下面缩进的二级分组区分开
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = UiSpace.Content)
                .height(1.dp)
                .background(UiTokens.DividerSoft),
        )
    }
}

/** 震动强度三选：轻 / 中 / 强；总开关关闭时置灰不可点 */
@Composable
private fun HapticStrengthChips(current: Int, enabled: Boolean, onPick: (Int) -> Unit) {
    val options = listOf(
        Prefs.HAPTIC_LIGHT to "轻",
        Prefs.HAPTIC_MEDIUM to "中",
        Prefs.HAPTIC_STRONG to "强",
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = UiSpace.Content, vertical = UiSpace.s)
            .alpha(if (enabled) 1f else 0.45f),
    ) {
        Text(
            "震动强度",
            style = MaterialTheme.typography.bodyLarge,
            color = UiTokens.Ink,
        )
        Spacer(Modifier.height(UiSpace.s))
        Row(horizontalArrangement = Arrangement.spacedBy(UiSpace.s)) {
            options.forEach { (value, label) ->
                // 37 号改动（S9/3-2）：可用性收敛到 NeuChip 的 enabled 单一参数，
                // 不再视觉 alpha 与 onClick 内守卫两处双写
                NeuChip(text = label, selected = current == value, enabled = enabled) {
                    onPick(value)
                }
            }
        }
    }
}

/**
 * 页内滑杆（不再弹窗）：拖动时立即回传数值，上方的 [WheelAppearancePreview] 同步变化。
 * 这三个参数原来走 AlertDialog —— 弹窗盖住整页，参数在变、轮盘看不见，等于没有预览；
 * 落到页面里之后「所见即所调」，也不用再点「确定」。
 */
@Composable
private fun InlineSliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    unit: String,
    onChange: (Float) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = UiSpace.Content, vertical = UiSpace.xxs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = UiTokens.Ink,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${value.toInt()} $unit",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = UiTokens.Primary,
            )
        }
        NeuSlider(value = value, valueRange = range, onValueChange = onChange)
    }
}

/**
 * 二级菜单（分组）标题。
 *
 * 3.18.1：字号 18sp，与一级大类 22sp、条目 16sp 形成三级阶梯。
 * 3.19.0：去掉凸起面板与它那层外阴影 —— 重叠的真正来源。
 *  - 原来这里挂的是 neuPressable(blur=10, dist=5)：drawBehind 把阴影画在自身边界**之外**，
 *    而 LazyColumn 里 8 个分组标题上下紧邻（外间距 vertical = 0），每块的阴影都压在
 *    相邻块和相邻条目上，浅色底上糊成一片，读起来就是「二级菜单叠在一起」；
 *  - 现在改用「主色细条 + 文字 + 裸箭头」的扁平分组头：不绘制任何背景与阴影，
 *    上下留出明确的 8/2dp 间距，与相邻内容不会互相覆盖；
 *  - 层级仍由「一级 4dp 色条 + 22sp 粗体」> 「二级 3dp 色条 + 18sp 主色」> 「条目 16sp」
 *    表达，重排后 8 个分组标题总高度也比原来矮，一屏能多看两行条目。
 */
@Composable
internal fun SectionTitle(text: String, collapsed: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 外 16：与该页条目左缘对齐；上下 8/2：给相邻内容留出安全间距
            .padding(horizontal = UiSpace.l)
            .padding(top = UiSpace.s, bottom = UiSpace.xxs)
            // UI UX Pro Max · Accessibility：可折叠分组补 role=Button + 展开/收纳状态朗读
            .semantics(mergeDescendants = true) {
                role = Role.Button
                stateDescription = if (collapsed) "已折叠，点按展开" else "已展开，点按收纳"
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onToggle() }
            // 内层 8：把点击热区抬到 ≥44dp，同时让色条与文字居中
            .padding(horizontal = UiSpace.s, vertical = UiSpace.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 3dp 主色细条：接续一级大类的 4dp 色条，明确「这一组属于上面那个大类」
        Box(
            modifier = Modifier
                .size(width = 3.dp, height = 16.dp)
                .clip(RoundedCornerShape(UiTokens.IndicatorRadius))
                .background(UiTokens.Primary.copy(alpha = 0.55f)),
        )
        Spacer(Modifier.width(UiSpace.ms))
        Text(
            text,
            style = UiType.groupTitle,
            color = UiTokens.Primary,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = if (collapsed) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
            contentDescription = if (collapsed) "展开" else "收纳",
            tint = UiTokens.InkSub,
            modifier = Modifier.size(UiTokens.IconSm),
        )
    }
}

/** 轻量打开的 beta 风险提示：依赖无障碍事后判定 + 厂商私有反射接口，行为不可控 */
@Composable
internal fun BetaWarningCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = UiSpace.l, vertical = 0.dp)
            // 37 号改动（T8/M1）：警告卡三色收进主题 token，深色玻璃主题用暗底亮字，
            // 不再四主题渲染同一组硬编码亮橙
            .background(UiTokens.WarningBg, RoundedCornerShape(UiTokens.RadiusField))
            .padding(horizontal = UiSpace.s, vertical = UiSpace.s),
    ) {
        Text(
            "BETA · 实验功能，存在大量不可控问题",
            style = MaterialTheme.typography.bodyLarge,
            color = UiTokens.WarningInk,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(UiSpace.xs))
        Text(
            "依赖无障碍事后判断与厂商私有接口，可能出现重复小窗、分享失败、" +
                "应用被误转成小窗等情况。建议仅按需开启单个场景，出问题请关闭总开关。",
            style = MaterialTheme.typography.bodySmall,
            color = UiTokens.WarningInkSub,
        )
    }
}

/** 设置页全部滑杆弹窗的宿主；触发区三档带实时预览 */
@Composable
private fun SliderSheetsHost(
    sheet: Sheet,
    radius: Float,
    icon: Float,
    angle: Float,
    hotW: Float,
    hotH: Float,
    inset: Float,
    hotBottom: Float,
    closeSheet: () -> Unit,
    onConfirm: (Sheet, Float) -> Unit,
) {
    val plainDismiss = { closeSheet() }
    val hotDismiss = {
        closeSheet()
        PilotService.instance?.hideHotspotGuide()
        Unit
    }
    val hotPreview = { w: Float, h: Float, i: Float, b: Float ->
        PilotService.instance?.showHotspotGuide(w, h, i, b)
        Unit
    }
    when (sheet) {
        Sheet.RADIUS -> SliderSheet("轮盘半径", radius, 120f..300f, "dp", { onConfirm(sheet, it) }, plainDismiss)
        Sheet.ICON -> SliderSheet("图标大小", icon, 36f..72f, "dp", { onConfirm(sheet, it) }, plainDismiss)
        Sheet.ANGLE -> SliderSheet("轮盘张角", angle, 45f..150f, "°", { onConfirm(sheet, it) }, plainDismiss)
        Sheet.HOT_W -> SliderSheet(
            "触发区宽度", hotW, 24f..120f, "dp", { onConfirm(sheet, it) }, hotDismiss,
            onValueChange = { hotPreview(it, hotH, inset, hotBottom) },
        )
        Sheet.HOT_H -> SliderSheet(
            "触发区高度", hotH, 16f..200f, "dp", { onConfirm(sheet, it) }, hotDismiss,
            onValueChange = { hotPreview(hotW, it, inset, hotBottom) },
        )
        Sheet.HOT_INSET -> SliderSheet(
            "边缘内缩", inset, 0f..60f, "dp", { onConfirm(sheet, it) }, hotDismiss,
            onValueChange = { hotPreview(hotW, hotH, it, hotBottom) },
        )
        Sheet.HOT_BOTTOM -> SliderSheet(
            "热区离底高度", hotBottom, 24f..240f, "dp", { onConfirm(sheet, it) }, hotDismiss,
            onValueChange = { hotPreview(hotW, hotH, inset, it) },
        )
        Sheet.NONE -> Unit
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
        shape = RoundedCornerShape(UiTokens.RadiusPanel),
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
                    fontWeight = FontWeight.SemiBold,
                    color = UiTokens.Primary,
                )
                Spacer(Modifier.height(UiSpace.m))
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
