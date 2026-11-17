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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import priv.kit.ui.PrivilegeScaffold


// 应用列表加载与无障碍服务检测

/**
 * 启动提速：只取已安装且可启动的包名集合，不解析 label、不加载图标。
 * 用于首次默认填充判断，比 loadLaunchableApps 快一个数量级。
 */
internal fun loadInstalledPackageNames(context: Context): Set<String> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .mapNotNull { it.activityInfo?.packageName }
        .toSet()
}

// 代码去重 + 提速：带图标的应用列表在一个进程内只解析一次，切页不再重复加载
internal var cachedLaunchableApps: List<AppEntry>? = null

internal fun launchableApps(context: Context): List<AppEntry> =
    cachedLaunchableApps ?: loadLaunchableApps(context).also { cachedLaunchableApps = it }

internal fun loadLaunchableApps(context: Context): List<AppEntry> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    // 对齐同类小窗应用 AppRepo.loadBlocking()：MAIN/LAUNCHER、排除自身、按 pkg 去重、
    // 标记系统应用(FLAG_SYSTEM)、预计算首字母，按「# 排最后 → 字母 → 中文拼音」排序
    val collator = java.text.Collator.getInstance(java.util.Locale.CHINA)
    return pm.queryIntentActivities(intent, 0)
        .mapNotNull { resolveInfo ->
            runCatching {
                val info = resolveInfo.activityInfo
                if (info.packageName == context.packageName) return@runCatching null
                val label = resolveInfo.loadLabel(pm).toString()
                // 图标自身的圆角决定底座轮廓，所以要在同一个 Bitmap 上量完再转 ImageBitmap
                val bitmap = resolveInfo.loadIcon(pm).toBitmap(72, 72)
                AppEntry(
                    pkg = info.packageName,
                    label = label,
                    icon = bitmap.asImageBitmap(),
                    letter = LetterUtil.firstLetter(label),
                    system = (info.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    contour = detectIconContour(bitmap),
                )
            }.getOrNull()
        }
        .distinctBy { it.pkg }
        .sortedWith { a, b ->
            when {
                a.letter == "#" && b.letter != "#" -> 1
                a.letter != "#" && b.letter == "#" -> -1
                a.letter != b.letter -> a.letter.compareTo(b.letter)
                else -> {
                    val c = collator.compare(a.label, b.label)
                    if (c != 0) c else a.pkg.compareTo(b.pkg)
                }
            }
        }
}

internal fun isAccessibilityEnabled(context: Context): Boolean {
    // 系统可能把组件名存成 com.pilot.wheel/.PilotService 这种短格式，
    // 所以必须解析后比较，不能直接拼全名做字符串匹配
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ).orEmpty()
    return enabled.split(':').any { entry ->
        val component = ComponentName.unflattenFromString(entry.trim()) ?: return@any false
        component.packageName == context.packageName &&
            component.className == PilotService::class.java.name
    }
}
