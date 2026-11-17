package com.pilot.wheel

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.Build
import android.util.Log
import kotlinx.coroutines.delay
import priv.kit.core.Privilege
import priv.kit.core.command.PrivilegeCommand
import com.pilot.wheel.core.StackListParser
import com.pilot.wheel.core.WindowGeometry
import com.pilot.wheel.core.WindowModes
import com.pilot.wheel.core.WindowRect

/**
 * 启动目标应用。
 *
 * 小窗形态用 ActivityOptions 同时传入
 * launchBounds（窗口矩形）与 windowingMode，系统才会给出圆角浮动小窗；
 * 只传 windowingMode（旧的 am start 通道）会被系统按默认位置贴顶通栏铺开。
 *
 * 首选普通 startActivity + 完整 options；若被系统拦截抛异常，退回 priv-kit
 * shell 通道（以 uid 2000 执行 am start），再退回全屏，保证一定能打开。
 */
object AppLauncher {

    private const val TAG = "WheelPilot"
    private const val KEY_WINDOWING_MODE = "android.activity.windowingMode"
    private const val KEY_ZOOM_LAUNCH_FLAGS = "android:activity.mZoomLaunchFlags"

    /** 旧调用兼容：true = 自动小窗，false = 全屏 */
    suspend fun launch(context: Context, pkg: String, freeWindow: Boolean): String =
        launch(context, pkg, if (freeWindow) Prefs.LAUNCH_AUTO else Prefs.LAUNCH_FULL)

    /**
     * 按启动模式打开应用：auto / vendor / freeform / full。
     * 小窗候选模式都带 launchBounds；依次尝试，成功即返回。
     * 流程分三步：[tryHonorOneStep]（荣耀原生轻窗 102）→
     * [tryWindowLaunch]（ActivityOptions 候选模式）→ shell / 全屏兜底。
     */
    suspend fun launch(context: Context, pkg: String, mode: String): String {
        val component = context.packageManager.getLaunchIntentForPackage(pkg)?.component
            ?: return "no-launcher:$pkg"
        // 诊断：荣耀识别结果直接进日志，避免分支静默跳过无从排查
        log(
            context,
            "device manufacturer=${Build.MANUFACTURER} brand=${Build.BRAND} model=${Build.MODEL} honor=${HonorWindowLauncher.isHonor(context)}",
        )
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            this.component = component
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val isFull = mode == Prefs.LAUNCH_FULL
        val honor = HonorWindowLauncher.isHonor(context)
        if (!isFull && Privilege.serverState.value != null) ensureFreeformEnabled()

        if (!isFull) {
            tryHonorOneStep(context, pkg, mode, component, honor)?.let { return it }
            tryWindowLaunch(context, pkg, mode, intent, honor)?.let { return it }
        }

        // 兜底 1：priv-kit shell 通道（旧行为，能打开但无窗口矩形）
        if (Privilege.serverState.value != null) {
            val shellResult = runShell(component.flattenToShortString(), mode, honor)
            log(context, "shell fallback $pkg mode=$mode -> $shellResult")
            if (shellResult == "shell-ok") return shellResult
        }

        // 兜底 2：普通全屏
        return runCatching {
            context.startActivity(intent)
            "intent-full-fallback"
        }.getOrElse { "intent-failed:${it.message}" }
    }

    /**
     * 荣耀原生轻窗（mode 102，系统直拉小窗的那个层）优先 —— 来源：社区 LSP 模块
     * 「荣耀轻窗」逆向。AOSP freeform(5) 会被 MagicOS 钳制尺寸（实测 74% 请求
     * 只得到 60%），而 102 是 MagicOS 私有轻窗层，尺寸与系统直拉一致（图一形态）。
     * 成功返回 "honor-onestep"；不适用或失败返回 null，由上层走兜底。
     */
    private suspend fun tryHonorOneStep(
        context: Context,
        pkg: String,
        mode: String,
        component: ComponentName,
        honor: Boolean,
    ): String? {
        if (!honor || mode == Prefs.LAUNCH_FREEFORM) return null
        // 荣耀设备：与 AOSP freeform 完全一致 —— 只传 launchBounds + windowingMode 5。
        // 修复图二问题：此前荣耀路径追加私有 launchAsFloatingWindow key 并在启动 300ms 后
        // 反射调 changeActivityPosition，MagicOS 会把窗口迁到它默认的小窗尺寸
        // （约 0.5 屏宽，明显比正常小窗窄小），覆盖了我们传入的 bounds。
        // 现在两条路径合一，比例与「正常的小窗」一致。
        log(
            context,
            "honor device, unified freeform launch bounds=${freeformBounds(context)} " +
                "ops=${HonorWindowLauncher.supportedOperations()?.keys}",
        )
        if (HonorWindowLauncher.launchOneStep(context, component)) {
            // 验证+回落：startActivityOneStep 是 void，静默失败收不到报错
            // （云闪付实测 invoke 成功但窗口不出现）。用前台检测确认，没到前台
            // 返回 null 让上层继续走 mode 5 候选，成功应用不受影响。
            if (!ForegroundCheck.hasUsageAccess(context)) {
                log(context, "honor oneStep verify skip: no usage access")
                return "honor-onestep"
            }
            repeat(2) { round ->
                delay(if (round == 0) 800L else 1000L)
                val fg = ForegroundCheck.currentForeground(context, 6000L)
                if (fg == pkg) {
                    log(context, "honor oneStep verify ok: $pkg foreground")
                    return "honor-onestep"
                }
                log(context, "honor oneStep verify round${round + 1}: foreground=$fg")
            }
            log(context, "honor oneStep verify fail: $pkg not foreground, fallback mode 5")
            return null
        }
        log(context, "honor oneStep unavailable, falling back to bundle candidates")
        return null
    }

    /**
     * 首选：ActivityOptions 带窗口矩形 + windowingMode，得到真正的浮动小窗。
     * 成功返回 "intent-window-<mode>"；全部候选失败返回 null。
     */
    private suspend fun tryWindowLaunch(
        context: Context,
        pkg: String,
        mode: String,
        intent: Intent,
        honor: Boolean,
    ): String? {
        val bounds = freeformBounds(context)
        val candidates = WindowModes.candidates(mode, honor)
        for (wm in candidates) {
            val ok = runCatching {
                context.startActivity(intent, windowModeOptions(wm, bounds))
            }.isSuccess
            if (ok) {
                log(context, "window launch $pkg mode=$wm bounds=$bounds -> ok")
                // 实测 MagicOS 会保留 bounds 的位置、但把窗口尺寸釦制到约 0.6×屏宽
                // （原生小窗实测宽 0.83×屏宽，freeform 达不到）。
                // 荣耀上启动成功后用 shell 找到 task 强制 resize 到目标矩形，
                // 尽量逼近原生小窗几何；命令不存在/失败仅记日志，不影响启动。
                // resize 不再依赖荣耀识别结果：shell 不可用/命令不存在只会记日志，无副作用
                if (bounds != null) {
                    resizeTaskToBounds(context, pkg, bounds)
                }
                return "intent-window-$wm"
            }
        }
        return null
    }

    /**
     * 计算自由窗口矩形，几何规则见 [WindowGeometry]。
     */
    private fun freeformBounds(context: Context): WindowRect? {
        val dm = context.resources.displayMetrics
        return WindowGeometry.freeformBounds(dm.widthPixels, dm.heightPixels)
    }

    /**
     * MagicOS 对 freeform 窗口有尺寸釦制（实测传入 74%×屏宽只得到 60%），
     * bounds 位置生效但尺寸不生效。启动成功后用 shell 找到目标 task 强制 resize。
     * 命令探测顺序：am task resize → am stack resize（不同版本命令名不同）。
     */
    private suspend fun resizeTaskToBounds(context: Context, pkg: String, bounds: WindowRect) {
        delay(700L)
        val result = exec("am stack list", 5_000L) ?: run {
            log(context, "resize skip: shell unavailable")
            return
        }
        var taskId: String? = StackListParser.findTaskId(result.stdout, pkg)
        if (taskId == null) {
            log(context, "resize skip: task not found for $pkg; stack=${result.stdout.take(400)}")
            return
        }
        val rect = "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}"
        execRaw("am task resizeable $taskId 2")
        val ok = execRaw("am task resize $taskId $rect") ||
            execRaw("am stack resize $taskId $rect")
        log(context, "resize task $taskId to $rect -> $ok")
    }

    /**
     * 构造启动 options：makeBasic + setLaunchBounds（公开 API）+ 直接写 windowingMode。
     * setLaunchBounds 把矩形以 "android:activity.launchBounds" 写入 bundle，这是浮动小窗形态的关键。
     * windowingMode 直接写 bundle key（fallback 路径），不反射非 SDK API，
     * 规避 targetSDK 35+ 的 BlockedPrivateApi 限制，真机也更稳。
     * ColorOS 私有小窗另加 mZoomLaunchFlags=4。
     */
    private fun windowModeOptions(windowMode: Int, bounds: WindowRect?): Bundle {
        val bundle = runCatching {
            ActivityOptions.makeBasic().apply {
                if (bounds != null) {
                    runCatching { setLaunchBounds(Rect(bounds.left, bounds.top, bounds.right, bounds.bottom)) }
                }
            }.toBundle()
        }.getOrElse { Bundle() }
        bundle.putInt(KEY_WINDOWING_MODE, windowMode)
        if (windowMode == WindowModes.VENDOR) {
            bundle.putInt(KEY_ZOOM_LAUNCH_FLAGS, 4)
        }
        return bundle
    }

    /**
     * 用 shell 确保自由窗口相关的系统开关已开启。
     * force_resizable_activities 在 secure 命名空间，缺失时很多应用无法进入 freeform。
     */
    private suspend fun ensureFreeformEnabled() {
        runCatching {
            execRaw("settings put global enable_freeform_support 1")
            execRaw("settings put secure force_resizable_activities 1")
        }
    }

    /** 双写日志：logcat + 应用内日志（设置里打开「详细日志」后日志弹窗可见） */
    private fun log(context: Context?, message: String) {
        Log.d(TAG, message)
        if (context != null) PilotLog.d(context, TAG, message)
    }

    /** 工具页也要用 shell（截屏/锁屏/直达扫一扫），这里暴露给同包调用 */
    suspend fun runShellCommand(commandLine: String): Boolean = execRaw(commandLine)

    /** 需要知道为什么失败时用：返回 exit code + 截断的 stdout/stderr */
    suspend fun runShellCommandDetail(commandLine: String): String {
        val r = exec(commandLine, 15_000L) ?: return "server-dead"
        return "exit=${r.exitCode} out=${r.stdout.take(300)} err=${r.stderr.take(300)}"
    }

    /** shell 命令结果：退出码 + 标准输出 + 标准错误 */
    private data class ShellResult(val exitCode: Int, val stdout: String, val stderr: String)

    /** 代码去重：所有 shell 命令共用的执行入口 */
    private suspend fun exec(commandLine: String, timeoutMillis: Long): ShellResult? = try {
        val command = PrivilegeCommand(arguments = listOf("/system/bin/sh", "-c", commandLine))
        val result = Privilege.startCommand(command = command, timeoutMillis = timeoutMillis)
            .use { it.awaitResult() }
        ShellResult(
            exitCode = result.exitCode,
            stdout = result.stdout.toString(Charsets.UTF_8).trim(),
            stderr = result.stderr.toString(Charsets.UTF_8).trim(),
        )
    } catch (t: Throwable) {
        Log.d(TAG, "exec failed: ${t.javaClass.simpleName}")
        null
    }

    /** 执行任意 shell 命令，返回是否成功（exitCode == 0） */
    private suspend fun execRaw(commandLine: String): Boolean =
        exec(commandLine, 10_000L)?.exitCode == 0

    /**
     * am start 的退出码不一定可靠：Activity 被系统拦截时它也可能返回 0。
     * 所以额外检查输出里有没有 Error 字样。
     */
    private suspend fun runShell(componentName: String, mode: String, honor: Boolean): String {
        val modes = WindowModes.candidates(mode, honor, withFullscreen = true)
        for (windowMode in modes) {
            val r = execShell(componentName, windowMode)
            if (r == "shell-ok") return r
        }
        return "shell-blocked:all-window-modes-failed"
    }

    private suspend fun execShell(componentName: String, windowingMode: Int): String {
        val windowFlag = if (windowingMode > 0) " --windowingMode $windowingMode" else ""
        val result = exec("am start -n $componentName$windowFlag", 20_000L)
            ?: return "shell-error:server-dead"

        val out = result.stdout
        val err = result.stderr
        Log.d(TAG, "am start (mode=$windowingMode) exit=${result.exitCode} out=$out err=$err")
        val blocked = err.contains("Error", ignoreCase = true) ||
            err.contains("Exception", ignoreCase = true) ||
            out.contains("Error:", ignoreCase = true)

        return when {
            result.exitCode != 0 -> "shell-exit-${result.exitCode}:$err"
            blocked -> "shell-blocked:$err"
            else -> "shell-ok"
        }
    }
}
