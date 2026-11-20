package com.pilot.wheel

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pilot.wheel.core.LightOpenScene
import priv.kit.core.Privilege

/**
 * 「轻量打开」设置页。
 *
 * 形态对齐参考页：返回箭头 + 大标题 + 一句说明 + 一组场景开关。
 * 视觉沿用本应用的新拟物卡片，不照抄参考页的扁平白卡，避免与其余页面风格断层。
 */
class LightOpenActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeController.load(this)
        setContent {
            WheelTheme {
                GlassCanvas(modifier = Modifier.fillMaxSize()) {
                    LightOpenScreen(onBack = { finish() })
                }
            }
        }
    }
}

/** 场景 → 图标。用的是 material-icons-core 里确定存在的图标，不额外引依赖 */
private val sceneIcons: Map<LightOpenScene, ImageVector> = mapOf(
    LightOpenScene.NOTIF_HEADS_UP to Icons.Filled.Email,
    LightOpenScene.APP_JUMP to Icons.Filled.Send,
    LightOpenScene.AUTH to Icons.Filled.Lock,
)

@Composable
private fun LightOpenScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(Prefs.lightOpenEnabled(context)) }
    var wheelOnly by remember { mutableStateOf(Prefs.lightOpenWheelOnly(context)) }
    var jumpAggressive by remember { mutableStateOf(Prefs.lightOpenJumpAggressive(context)) }
    val sceneState = remember { mutableStateMapOf<LightOpenScene, Boolean>() }
    LightOpenScene.entries.forEach { scene ->
        if (scene !in sceneState) sceneState[scene] = Prefs.lightOpenScene(context, scene)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(UiSpace.xl))

        // 返回：圆形按钮，与参考页一致
        Box(
            modifier = Modifier
                .padding(start = UiSpace.xl)
                .size(UiTokens.GridIcon)
                .clip(CircleShape)
                .background(UiTokens.SurfaceElevated)
                .semantics(mergeDescendants = true) { role = Role.Button }
                .clickable { onBack() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowLeft,
                contentDescription = "返回",
                tint = UiTokens.Ink,
                modifier = Modifier.size(24.dp),
            )
        }

        Spacer(Modifier.height(UiSpace.xl))
        Text(
            "轻量打开",
            style = UiType.display,
            color = UiTokens.Ink,
            modifier = Modifier.padding(horizontal = UiSpace.xxl),
        )
        Spacer(Modifier.height(UiSpace.ms))
        Text(
            if (enabled) "在以下场景使用自由浮窗打开" else "开启后，以下场景改用浮窗打开应用",
            style = MaterialTheme.typography.bodyLarge,
            color = UiTokens.InkSub,
            modifier = Modifier.padding(horizontal = UiSpace.xxl),
        )
        Spacer(Modifier.height(UiSpace.xl))

        // 总开关
        GlassPanel(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = UiSpace.l),
            shape = RoundedCornerShape(UiTokens.RadiusCard),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // UI UX Pro Max · Accessibility：整行开关补 role=Switch + 状态朗读
                    .semantics(mergeDescendants = true) {
                        role = Role.Switch
                        stateDescription = if (enabled) "已开启" else "已关闭"
                        contentDescription = "启用轻量打开"
                    }
                    .clickable {
                        enabled = !enabled
                        Prefs.setLightOpenEnabled(context, enabled)
                    }
                    .padding(horizontal = UiSpace.xl, vertical = UiSpace.l),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("启用轻量打开", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(UiSpace.xxs))
                    Text(
                        "关闭时下列场景全部按原样全屏打开",
                        style = MaterialTheme.typography.bodySmall,
                        color = UiTokens.InkSub,
                    )
                }
                NeuSwitch(checked = enabled, onCheckedChange = null)
            }
        }

        Spacer(Modifier.height(UiSpace.m))

        // 7 个场景
        GlassPanel(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = UiSpace.l),
            shape = RoundedCornerShape(UiTokens.RadiusPanel),
        ) {
            Column(modifier = Modifier.padding(vertical = UiSpace.sm)) {
                LightOpenScene.entries.forEachIndexed { index, scene ->
                    SceneRow(
                        scene = scene,
                        checked = sceneState[scene] == true,
                        dimmed = !enabled,
                        hint = sceneHint(scene),
                        onToggle = {
                            val next = !(sceneState[scene] ?: false)
                            // 共用判据的一组一起变：界面上三行必须同进同退，
                            // 否则用户会以为能只让「登录授权」生效
                            LightOpenScene.linkedTo(scene).forEach { linked ->
                                sceneState[linked] = next
                            }
                            Prefs.setLightOpenScene(context, scene, next)
                        },
                    )
                    if (index != LightOpenScene.entries.lastIndex) {
                        Box(
                            modifier = Modifier
                                .padding(start = UiSpace.mega, end = UiSpace.xl)
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(UiTokens.Divider),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(UiSpace.sm))
        Text(
            "登录授权、应用分享与「应用跳转」在系统层面是同一种启动方式，三者共用一条判定：" +
                "打开其中任意一个，另外两个会一起打开。",
            style = MaterialTheme.typography.bodySmall,
            color = UiTokens.InkSub,
            modifier = Modifier.padding(horizontal = UiSpace.xxl),
        )
        Text(
            "复制链接受系统限制：Android 10 起后台读不到剪贴板内容，本项改为" +
                "「复制后在轮盘里点一次打开」，不会自动弹出。",
            style = MaterialTheme.typography.bodySmall,
            color = UiTokens.InkSub,
            modifier = Modifier.padding(horizontal = UiSpace.xxl, vertical = UiSpace.sm),
        )

        Spacer(Modifier.height(UiSpace.ms))

        // 生效范围
        GlassPanel(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = UiSpace.l),
            shape = RoundedCornerShape(UiTokens.RadiusCard),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) {
                        role = Role.Switch
                        stateDescription = if (wheelOnly) "已开启" else "已关闭"
                        contentDescription = "只对轮盘里的应用生效"
                    }
                    .clickable {
                        wheelOnly = !wheelOnly
                        Prefs.setLightOpenWheelOnly(context, wheelOnly)
                    }
                    .padding(horizontal = UiSpace.xl, vertical = UiSpace.l),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("只对轮盘里的应用生效", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(UiSpace.xxs))
                    Text(
                        if (wheelOnly) {
                            "只处理轮盘已配置的 ${Prefs.load(context).size} 个应用；通知、横幅、控制中心不受此限制"
                        } else {
                            "对除系统界面、桌面、电话外的全部应用生效"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = UiTokens.InkSub,
                    )
                }
                NeuSwitch(checked = wheelOnly, onCheckedChange = null)
            }
        }

        Spacer(Modifier.height(UiSpace.m))
        GlassPanel(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = UiSpace.l),
            shape = RoundedCornerShape(UiTokens.RadiusCard),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) {
                        role = Role.Switch
                        stateDescription = if (jumpAggressive) "已开启" else "已关闭"
                        contentDescription = "跳转也强制转窗"
                    }
                    .clickable {
                        jumpAggressive = !jumpAggressive
                        Prefs.setLightOpenJumpAggressive(context, jumpAggressive)
                    }
                    .padding(horizontal = UiSpace.xl, vertical = UiSpace.l),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("跳转也强制转窗", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(UiSpace.xxs))
                    Text(
                        if (jumpAggressive) {
                            "应用跳转会重新拉起目标页（可能回到首页）；分享仍走保守方式"
                        } else {
                            "跳转只把已开窗口改成浮窗几何，失败就保持全屏 —— 不会打断分享与授权"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = UiTokens.InkSub,
                    )
                }
                NeuSwitch(checked = jumpAggressive, onCheckedChange = null)
            }
        }

        Spacer(Modifier.height(UiSpace.m))
        HealthPanel()
        Spacer(Modifier.height(UiSpace.m))
        StatsPanel()
        Spacer(Modifier.height(UiSpace.xxxl))
    }
}

/** 需要额外说明的场景；其余留空，保持单行清爽 */
private fun sceneHint(scene: LightOpenScene): String? = when (scene) {
    LightOpenScene.APP_JUMP, LightOpenScene.AUTH -> "与另一项联动开关"
    else -> null
}

@Composable
private fun SceneRow(
    scene: LightOpenScene,
    checked: Boolean,
    dimmed: Boolean,
    hint: String?,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // UI UX Pro Max · Accessibility：场景开关行补 role + 场景名 + 状态朗读
            .semantics(mergeDescendants = true) {
                role = Role.Switch
                stateDescription = if (checked) "已开启" else "已关闭"
                contentDescription = scene.label
            }
            .clickable { onToggle() }
            .padding(horizontal = UiSpace.xl, vertical = UiSpace.ml),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = sceneIcons[scene] ?: Icons.Filled.Edit,
            contentDescription = null,
            // UI UX Pro Max · color-contrast：dimmed 时 FieldHint 对比不足，改 InkSub
            tint = if (dimmed) UiTokens.InkSub else UiTokens.Ink,
            modifier = Modifier.size(UiTokens.IconMd),
        )
        Spacer(Modifier.width(UiSpace.l))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                scene.label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (dimmed) UiTokens.InkSub else UiTokens.Ink,
            )
            if (hint != null) {
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = UiTokens.InkSub,
                )
            }
        }
        NeuSwitch(checked = checked, onCheckedChange = null)
    }
}

/** 无障碍 / 通知使用权 / 特权通道 三项健康状态 */
@Composable
private fun HealthPanel() {
    val context = LocalContext.current
    // 37 号改动（S5/2-9/2-10/2-11）：三项健康状态此前无响应式来源（一次性读/无 key remember），
    // 从系统授权页返回后旧值残留。改为状态变量 + resume 刷新；特权走 collectAsState 订阅。
    var accessibility by remember { mutableStateOf(PilotService.instance != null) }
    var notify by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        accessibility = PilotService.instance != null
        notify = runCatching {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm?.isNotificationListenerAccessGranted(ComponentName(context, PilotNotificationListener::class.java)) == true
        }.getOrDefault(false)
        onPauseOrDispose { }
    }
    val serverInfo by Privilege.serverState.collectAsState()
    val privilege = serverInfo != null

    GlassPanel(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = UiSpace.l),
        shape = RoundedCornerShape(UiTokens.RadiusCard),
    ) {
        Column(modifier = Modifier.padding(horizontal = UiSpace.xl, vertical = UiSpace.ml)) {
            Text("运行条件", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(UiSpace.s))
            HealthRow(
                ok = accessibility,
                title = "无障碍服务",
                hint = if (accessibility) "已连接，能识别前台应用" else "未连接，所有场景都不会生效",
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
            )
            HealthRow(
                ok = notify,
                title = "通知使用权",
                hint = if (notify) "已授权，通知两档可用" else "未授权，通知中心与横幅无法识别",
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
            )
            HealthRow(
                ok = privilege,
                title = "特权通道",
                hint = if (privilege) "可用，转窗带窗口矩形" else "不可用，只能走普通启动通道，浮窗成功率下降",
                onClick = null,
            )
        }
    }
}

@Composable
private fun HealthRow(ok: Boolean, title: String, hint: String, onClick: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // UI UX Pro Max · Accessibility：可点击的健康项补 role=Button + 状态朗读
            .then(
                if (onClick != null) {
                    Modifier
                        .semantics(mergeDescendants = true) {
                            role = Role.Button
                            stateDescription = if (ok) "正常" else "异常，点按去设置"
                        }
                        .clickable { onClick() }
                } else Modifier,
            )
            .padding(vertical = UiSpace.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (ok) UiTokens.Primary else UiTokens.InkSub),
        )
        Spacer(Modifier.width(UiSpace.ms))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = UiTokens.InkSub,
            )
        }
        if (onClick != null) {
            Text("去设置", style = MaterialTheme.typography.bodySmall, color = UiTokens.Primary)
        }
    }
}

/** 最近一次判定 + 计数，便于在真机上确认"到底有没有生效" */
@Composable
private fun StatsPanel() {
    val context = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    val decision = LightOpenWatcher.lastDecision
    val convert = LightOpenWatcher.lastConvertResult
    val stats = remember(tick) { LightOpenWatcher.statsSnapshot() }
    val okCount = stats.filterKeys { it.endsWith("|ok") }.values.sum()

    GlassPanel(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = UiSpace.l),
        shape = RoundedCornerShape(UiTokens.RadiusCard),
    ) {
        Column(modifier = Modifier.padding(horizontal = UiSpace.xl, vertical = UiSpace.ml)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("诊断", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(
                    "刷新",
                    style = MaterialTheme.typography.bodySmall,
                    color = UiTokens.Primary,
                    // 纯文字按钮补最小触控热区 + role/contentDescription（UI UX Pro Max · Touch/Accessibility）
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .semantics(mergeDescendants = true) {
                            role = Role.Button
                            contentDescription = "刷新诊断数据"
                        }
                        .clickable { tick++ },
                )
                Spacer(Modifier.width(UiSpace.l))
                Text(
                    "清空",
                    style = MaterialTheme.typography.bodySmall,
                    color = UiTokens.Primary,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .semantics(mergeDescendants = true) {
                            role = Role.Button
                            contentDescription = "清空统计数据"
                        }
                        .clickable {
                        LightOpenWatcher.resetStats()
                        tick++
                    },
                )
            }
            Spacer(Modifier.height(UiSpace.s))
            Text(
                decision?.let {
                    val sceneLabel = it.scene?.label ?: "未识别"
                    if (it.accept) "最近一次：$sceneLabel · 已转窗" else
                        "最近一次：$sceneLabel · 未转窗（${it.reject?.label ?: "未知"}）"
                } ?: "最近一次：尚无记录",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "累计转窗 $okCount 次" + (convert?.let { " · 最近结果 $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = UiTokens.InkSub,
            )
            val rejects = stats.entries
                .filter { !it.key.endsWith("|ok") && !it.key.contains("|convert:") }
                .sortedByDescending { it.value }
                .take(3)
            rejects.forEach { (key, value) ->
                Text(
                    "未转窗 $key × $value",
                    style = MaterialTheme.typography.bodySmall,
                    color = UiTokens.InkSub,
                )
            }
        }
    }
}
