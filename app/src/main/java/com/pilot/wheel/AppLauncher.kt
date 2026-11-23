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
import com.pilot.wheel.core.FreeformLayout
import com.pilot.wheel.core.StackListParser
import com.pilot.wheel.core.TaskBinderBridge
import com.pilot.wheel.core.WindowBoundsApplier
import com.pilot.wheel.core.WindowGeometry
import com.pilot.wheel.core.WindowModes
import com.pilot.wheel.core.WindowRect
import com.pilot.wheel.core.WindowStateParser

/**
 * 启动目标应用。
 *
 * 小窗形态用 ActivityOptions 同时传入
 * launchBounds（窗口矩形）与 windowingMode，系统才会给出圆角浮动小窗；
 * 只传 windowingMode（旧的 am start 通道）会被系统按默认位置贴顶通栏铺开。
 *
 * 首选普通 startActivity + 完整 options；若被系统拦截抛异常，退回 priv-kit
 * shell 通道（以 uid 2000 执行 am start），再退回全屏，保证一定能打开。
 *
 * 启动后的确认改为「轮询任务表，命中即返回」，不再写死整段 delay：
 * 写死等待会让每次都白等满，冷启动感知 3s 以上；轮询多数情况 100–300ms 命中。
 */
object AppLauncher {

    private const val TAG = "WheelPilot"
    private const val KEY_WINDOWING_MODE = "android.activity.windowingMode"
    private const val KEY_ZOOM_LAUNCH_FLAGS = "android:activity.mZoomLaunchFlags"
    /** 后台启动放行的 bundle key，与 ActivityOptions 的序列化名一致 */
    private const val KEY_BAL_MODE = "android.activity.pendingIntentBackgroundActivityStartMode"

    /** 标准桌面启动旗标：NEW_TASK 保证独立任务栈，RESET 让已有任务回到入口页 */
    private val FLAG_LAUNCH_STANDARD =
        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED

    /** 小窗启动额外关掉窗口动画，避免「先整屏铺开、再缩成窗」的跳变 */
    private val FLAG_LAUNCH_QUIET =
        FLAG_LAUNCH_STANDARD or Intent.FLAG_ACTIVITY_NO_ANIMATION

    /** shell 通道的旗标值：am start 的 -f 是整体替换，必须显式给出 */
    private const val SHELL_LAUNCH_FLAGS = "0x10200000"

    /**
     * 任务表轮询的等待序列（毫秒）。首轮不等待（立即查一次），之后递增退避。
     *
     * 原先固定「6 次 × 70ms ≈ 0.42s」在两个场景下必然超时：
     * - 冷启动大应用（11-19 09:57:54 酷安）刚发出 startActivity，0.42s 内新进程
     *   还建不出 task，判 task-not-found；
     * - 退避递增让热启动（第 1 次即命中）不受影响，冷启动给到约 2.5s。
     */
    private val POLL_WAITS_MS = longArrayOf(0L, 100L, 150L, 200L, 250L, 300L, 400L, 500L, 600L)

    /**
     * 41B：最近一次小窗启动实际使用的 bounds。
     * PilotService 启动成功后据此把 task 登记 [FreeformWindowRegistry] 并显示把手；
     * 空表示本次不是带 bounds 的小窗启动（全屏兜底 / shell 无矩形）。
     */
    @Volatile
    var lastLaunchBounds: WindowRect? = null
        private set

    /**
     * 当前用户号：uid = 用户号 × 100000 + appId，整除即得。
     * UserHandle.myUserId() 是隐藏 API，这里不依赖它。
     */
    private fun currentUserId(): Int = android.os.Process.myUid() / 100000

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
        // 自己发起的启动：告诉轻量打开「这次是用户点的图标」，避免被当成应用跳转转成浮窗
        LightOpenWatcher.markManualLaunch(pkg)
        // 诊断：荣耀识别结果直接进日志，避免分支静默跳过无从排查
        log(
            context,
            "device manufacturer=${Build.MANUFACTURER} brand=${Build.BRAND} model=${Build.MODEL} honor=${HonorWindowLauncher.isHonor(context)}",
        )

        val isFull = mode == Prefs.LAUNCH_FULL
        val honor = HonorWindowLauncher.isHonor(context)
        val hasShell = Privilege.serverState.value != null

        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            this.component = component
            if (isFull) addFlags(FLAG_LAUNCH_STANDARD) else addFlags(FLAG_LAUNCH_QUIET)
        }

        if (!isFull && hasShell) {
            ensureFreeformEnabled()
            // 启动前放开可调整：声明 resizeableActivity=false 的应用若等启动后再改，
            // 已经按全屏铺开，后续 resize 对它的 task 无效。
            preflightResizeable(context, pkg)
        }

        if (!isFull) {
            // 75 号：**已存在的 task 优先原地转窗**。
            // 冷启动链路（oneStep / ActivityOptions / am start 5|100）对已存在的 task
            // 全部无效 —— windowingMode 会被系统静默忽略，落成全屏。需求 1「第二次起
            // 都变全屏」与需求 2「朋友圈里把微信缩成小窗」都是这一个根因。
            promoteExistingTask(context, pkg, mode)?.let {
                return it
            }
            // 启动成功即推一条实况（B1）：「小窗运行中 · 应用名」
            tryHonorOneStep(context, pkg, mode, component, honor)?.let {
                return it
            }
            tryWindowLaunch(context, pkg, mode, intent, honor)?.let {
                return it
            }
        }

        // 兜底 1：priv-kit shell 通道（旧行为，能打开但无窗口矩形）
        if (hasShell) {
            val shellResult = runShell(component.flattenToShortString(), mode, honor)
            log(context, "shell fallback $pkg mode=$mode -> $shellResult")
            if (shellResult == "shell-ok") return shellResult
        }

        // 兜底 2：普通全屏
        val fallback = runCatching {
            context.startActivity(intent)
            "intent-full-fallback"
        }.getOrElse { return "intent-failed:${it.message}" }

        // 75 号（需求 1）：冷启动时 task 还不存在，任何 windowingMode 参数都会被系统
        // 丢弃 —— 只能先铺开、等 task 出现后再原地转窗。上面 promoteExistingTask 在
        // 「已存在」时已经拦住了；这里补「刚铺开」的那一次，于是**第一次打开也是小窗**，
        // 而不是「第一次小窗、以后全屏」。
        if (!isFull) {
            for (wait in longArrayOf(120L, 200L, 300L, 400L)) {
                delay(wait)
                promoteExistingTask(context, pkg, mode)?.let { return it }
            }
        }
        return fallback
    }

    /**
     * 75 号：把**已经在跑的 task** 原地转成浮动小窗。
     *
     * 为什么单独一条路：`am start --windowingMode 5|100` 只在 task **不存在**（冷启动）
     * 时被消费；task 已经存在时系统直接把 Intent 投给现有实例，windowingMode 被忽略，
     * 结果就是「第一次小窗、以后都全屏」。荣耀 MagicOS 上唯一还能被消费的是私有层
     * 102（`WindowModes.MAGIC`），且它**只对已存在的 task 生效** —— 与 5/100 正好互补。
     *
     * 实测（荣耀 ASUJ6R6415001222 / Android 17）：对前台或后台的既有 task 执行
     * `am start -n <cmp> -f 0x10200000 --windowingMode 102`，同一个 taskId 变
     * `mode=freeform` 并前移到栈顶，底下的页面（朋友圈、上一个应用）仍在。
     *
     * 成功返回 `"promote-$verdict"`；目标包当前没有 task（真冷启动）返回 null，
     * 由调用方走原有冷启动链。
     */
    private suspend fun promoteExistingTask(context: Context, pkg: String, mode: String): String? {
        if (!PilotShellClient.possible()) return null
        val component = context.packageManager.getLaunchIntentForPackage(pkg)?.component
            ?: return null
        // 冷启动时这里查不到 task（一次 dumpsys 的成本，换来「热启动永远小窗」）。
        // 刻意**不用** awaitTask 的轮询：那条要等 9 轮、每次 dumpsys，冷启动会白等
        // 两秒多才落回原链。这里只要一次即时快照 —— 有就是热启动，没有就是冷启动。
        val entry = quickTask(context, pkg) ?: return null
        if (WindowStateParser.isFreeform(entry.mode)) {
            // 已经是小窗：只把它拉回前台，不重投 Intent（否则会重置用户翻到的页面）
            log(context, "promote $pkg task=${entry.taskId} already freeform, refocus")
            TaskBinderBridge.refocus(context, entry.taskId)
            return "promote-already"
        }
        val bounds = freeformBounds(context, FreeformWindowRegistry.list().size)
        lastLaunchBounds = bounds
        // 后台 task 转窗前先放开可调整，否则后续 resize 对它无效
        WindowBoundsApplier.markResizeable(entry.taskId)
        val result = execShell(component.flattenToShortString(), WindowModes.MAGIC)
        log(context, "promote $pkg task=${entry.taskId} mode=${entry.mode} mode=$mode -> $result")
        if (result != "shell-ok") return null
        // 102 转窗后系统给的是它自己的尺寸，必须显式拉到我们的 bounds，
        // 否则 registry 里记的矩形与真实窗口不符，「点外面关闭」会判错内外。
        if (bounds != null) applyBounds(context, pkg, entry.taskId, bounds)
        val verdict = if (bounds == null) "ok" else confirmWindow(context, pkg, bounds)
        return "promote-$verdict"
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
                // 无 usage access：走特权 shell 的 dumpsys 验证 102 是否真的拉起了浮窗。
                // 只有明确 OK 才保留 102；FAILED/INCONCLUSIVE 都保守回落 mode 5，
                // 避免 void 接口静默失败被当成成功（云闪付实测过的坑）。
                // 轮询而非写死：首轮 300ms 多半已切完，命中即返回，不等满 700ms。
                // 轮次与等待：原「300ms + 500ms」共 800ms 对冷启动大应用不够。
                // 11-19 09:57:54 酷安在 800ms 时 task 仍不存在 → 判 FAILED → 立刻又发
                // 一次 startActivity，两个启动请求互相打架，最终落成 task-not-found。
                // 改成 350/700/1100 三轮（上限约 2.15s），且只有 TASK_ABSENT 才继续等：
                // 「task 已在但形态是全屏」不会自己变浮窗，多等纯属白花时间。
                val waits = longArrayOf(350L, 700L, 1_100L)
                for ((round, wait) in waits.withIndex()) {
                    delay(wait)
                    val dump = shellStdout("dumpsys activity activities", 6_000L)
                    val detail = ForegroundCheck.verifyViaShellDetailed(dump, pkg)
                    when (detail.verdict) {
                        ForegroundCheck.ShellVerify.OK -> {
                            log(context, "honor oneStep verify ok via shell: $pkg ${detail.describe()}")
                            alignBounds(context, pkg)
                            return "honor-onestep"
                        }
                        ForegroundCheck.ShellVerify.FAILED -> {
                            // 只有「目标 task 还不存在」才值得再等一轮：冷启动 300ms 时任务表
                            // 里常常还没有它（11-19 09:42 知乎冷启 325ms 判 FAILED、热启同样
                            // 322ms 却 OK）。而「task 已在但形态是全屏」不会自己变成浮窗。
                            val retryable = detail.reason == ForegroundCheck.VerifyReason.TASK_ABSENT
                            if (retryable && round < waits.lastIndex) {
                                log(context, "honor oneStep verify miss round$round: ${detail.describe()}, retry")
                            } else {
                                log(context, "honor oneStep verify failed via shell: $pkg ${detail.describe()}, fallback")
                                return null
                            }
                        }
                        ForegroundCheck.ShellVerify.INCONCLUSIVE -> {
                            log(context, "honor oneStep verify inconclusive round$round: ${detail.describe()}, retry")
                        }
                    }
                }
                log(context, "honor oneStep verify exhausted via shell: $pkg, fallback")
                return null
            }
            // 有 usage access：前台事件是便宜的本地查询，三轮退避（220/420/700ms），
            // 命中即返回，最坏 1.34s（原固定 800+1000 必等满 1.8s）。
            for (wait in longArrayOf(220L, 420L, 700L)) {
                delay(wait)
                val fg = ForegroundCheck.currentForeground(context, 6000L)
                if (fg == pkg) {
                    log(context, "honor oneStep verify ok: $pkg foreground")
                    alignBounds(context, pkg)
                    return "honor-onestep"
                }
                log(context, "honor oneStep verify miss: foreground=$fg")
            }
            log(context, "honor oneStep verify fail: $pkg not foreground, fallback mode 5")
            return null
        }
        log(context, "honor oneStep unavailable, falling back to bundle candidates")
        return null
    }

    /**
     * 75 号：把刚拉起的小窗拉到我们的几何，并把**真实矩形**记进 [lastLaunchBounds]。
     *
     * 荣耀 oneStep 走的是系统私有接口，它自己决定窗口多大（实测 102 层恒为
     * `Rect(133, 580 - 1406, 2655)`），既不听我们传的 launchBounds，也不回报尺寸。
     * 于是 registry 里若记的是我们算的那组，「点小窗外侧关闭」就会判错内外。
     * 这里统一收拾：先拉到目标矩形，再回读 dump 里的 `mBounds` 作为登记值。
     */
    private suspend fun alignBounds(context: Context, pkg: String) {
        val entry = awaitTask(context, pkg) ?: return
        val target = lastLaunchBounds
            ?: freeformBounds(context, FreeformWindowRegistry.list().size)
            ?: return
        WindowBoundsApplier.markResizeable(entry.taskId)
        applyBounds(context, pkg, entry.taskId, target)
        log(context, "align bounds task=${entry.taskId} target=$target")
        // 回读真实矩形：resize 可能被系统钳制（MagicOS 对最小宽度有自己的下限）
        val dump = TaskBinderBridge.dumpTasks(context)
            ?: exec("dumpsys activity activities", 4_000L)?.stdout
        val real = dump?.let { StackListParser.boundsOf(it, entry.taskId) }
        if (real != null && real.width > 0 && real.height > 0) {
            lastLaunchBounds = real
            log(context, "align bounds task=${entry.taskId} real=$real")
        }
    }

    /**
     * 首选：ActivityOptions 带窗口矩形 + windowingMode，得到真正的浮动小窗。
     * 成功返回 "intent-window-<mode>"；全部候选失败返回 null。
     *
     * startActivity 不抛异常只说明「请求被接受」，不代表真的进了小窗：
     * MagicOS 会保留位置、把尺寸钳制甚至忽略 windowingMode。因此启动后用一次
     * `am stack list` 确认真实形态，形态不符再放开可调整并 resize 补救。
     */
    private suspend fun tryWindowLaunch(
        context: Context,
        pkg: String,
        mode: String,
        intent: Intent,
        honor: Boolean,
    ): String? {
        val bounds = freeformBounds(context, FreeformWindowRegistry.list().size)
        lastLaunchBounds = bounds
        val candidates = WindowModes.candidates(mode, honor)
        // 首选（A2）：以 shell 身份 startActivityAsUser，启动源头就是目标窗口模式。
        // 这条路不存在「先全屏铺开、再缩成窗」的中间态 —— 跳变的根因就在源头。
        tryShellStart(context, pkg, candidates, bounds)?.let { return it }
        for (wm in candidates) {
            val ok = runCatching {
                context.startActivity(intent, windowModeOptions(wm, bounds))
            }.isSuccess
            if (!ok) continue
            if (bounds == null) {
                log(context, "window launch $pkg mode=$wm -> ok (no bounds)")
                return "intent-window-$wm"
            }
            val verdict = confirmWindow(context, pkg, bounds)
            log(context, "window launch $pkg mode=$wm bounds=$bounds -> ok, $verdict")
            return if (verdict == "confirmed") "intent-window-$wm" else "intent-window-$wm-$verdict"
        }
        return null
    }

    /**
     * shell 身份启动（A2）。成功返回 "shell-start-<mode>"，不适用/失败返回 null。
     *
     * 与 [runShell] 的区别：`am start` 是我们拼命令、由 ActivityManager 解析；
     * 这里是 binder 直调 `startActivityAsUser`，Intent 与 options 都由我们构造，
     * windowingMode / launchBounds 在启动那一刻就生效。
     *
     * 仅当常驻通道在线时才尝试 —— app_process 通道重建 Intent 的语义不一样，不做。
     */
    private suspend fun tryShellStart(
        context: Context,
        pkg: String,
        candidates: IntArray,
        bounds: WindowRect?,
    ): String? {
        // 闸门必须用 possible()（Shizuku 可用）而不是 available()（已绑定）。
        // available() 不会触发绑定，只反映「此刻手里有没有活 binder」——warmUp 那一次
        // 若没成功（Shizuku 后启动 / 重启过 / 用户后授权），它就永久 false，
        // 整条 A1 通道从此再也不会被尝试。11-19 09:57 日志里全程没有一行
        // `shell-start`，正是这个原因。冷却期由 PilotShellClient 内部的 downUntil
        // 保护（15s），这里再叠一层闸只会把通道彻底关死。
        if (!PilotShellClient.possible()) {
            log(context, "shell-start skipped: ${PilotShellClient.describe()}")
            return null
        }
        if (!PilotShellClient.available()) {
            // 还没绑上：后台补绑，本次立即走老路。不能在启动关键路径上等 6s 绑定，
            // 但也不能像原来那样直接放弃 —— 那样通道会永久失效。下次点击即命中。
            PilotShellClient.kickBind()
            log(context, "shell-start deferred: ${PilotShellClient.describe()}")
            return null
        }
        log(context, "shell-start channel=${PilotShellClient.describe()}")
        val launch = runCatching {
            context.packageManager.getLaunchIntentForPackage(pkg)?.component
        }.getOrNull() ?: return null
        for (wm in candidates) {
            val ok = TaskBinderBridge.startAsUser(
                context = context,
                pkg = launch.packageName,
                cls = launch.className,
                userId = currentUserId(),
                windowingMode = wm,
                bounds = bounds,
                quiet = true,
            )
            log(context, "shell-start $pkg mode=$wm bounds=$bounds -> $ok")
            if (!ok) continue
            if (bounds == null) return "shell-start-$wm"
            val verdict = confirmWindow(context, pkg, bounds)
            return if (verdict == "confirmed") "shell-start-$wm" else "shell-start-$wm-$verdict"
        }
        return null
    }

    /**
     * 启动后确认形态并补救几何。
     *
     * 轮询任务表拿 taskId 与真实 windowingMode：
     * - 已是浮窗 → confirmed（不再发任何 resize 命令）
     * - 仍是全屏 → 放开可调整 + resize，记 resized-from-fullscreen
     * - 模式字段解析不出 → 只 resize，记 unknown-mode（不误判成失败）
     * - 轮询超时没找到 task → task-not-found
     */
    private suspend fun confirmWindow(context: Context, pkg: String, bounds: WindowRect): String {
        val entry = awaitTask(context, pkg) ?: return "task-not-found"
        val freeform = WindowStateParser.isFreeform(entry.mode)
        if (freeform) {
            log(context, "confirm $pkg task=${entry.taskId} mode=${entry.mode} -> freeform")
            return "confirmed"
        }
        execRaw("am task resizeable ${entry.taskId} 2")
        applyBounds(context, pkg, entry.taskId, bounds)
        return if (entry.mode == null) "unknown-mode" else "resized-from-fullscreen"
    }

    /**
     * 75 号：**一次**快照查 task，不轮询。
     *
     * [awaitTask] 是给「刚发完启动命令、等窗口起来」用的，要轮询 9 次；转窗判定只要
     * 「此刻是否存在」这一个事实，用轮询会让冷启动白等两秒多，必须分开。
     */
    private suspend fun quickTask(context: Context, pkg: String): StackListParser.TaskEntry? {
        val dump = TaskBinderBridge.dumpTasks(context)
            ?: exec("dumpsys activity activities", 5_000L)?.stdout
            ?: return null
        return StackListParser.findTask(dump, pkg, currentUserId())
    }

    /**
     * 轮询任务表直到目标包出现 task；命中即返回，不在两次之间写死等待。
     *
     * 数据源统一走 `dumpsys activity activities`：`am stack list` 在 Android 12+
     * 已随 `am stack` 子命令一起移除（同族的 `am stack resize` 早就在 MagicOS 上
     * 报 NumberFormatException），用它做兜底等于没有兜底。
     *
     * 用户号参与匹配：分身与原体同包名，只按包名会 resize 到另一个分身。
     */
    /** 41B：PilotService 启动成功后登记 registry 也要轮询 taskId，放开给同包用 */
    internal suspend fun awaitTask(context: Context, pkg: String): StackListParser.TaskEntry? {
        val userId = currentUserId()
        POLL_WAITS_MS.forEach { wait ->
            if (wait > 0) delay(wait)
            // 优先走常驻进程的 dumpsys：省一次 priv-kit 往返
            val dump = TaskBinderBridge.dumpTasks(context)
                ?: exec("dumpsys activity activities", 5_000L)?.stdout
            StackListParser.findTask(dump.orEmpty(), pkg, userId)?.let { return it }
        }
        log(context, "awaitTask timeout: $pkg not found in ${POLL_WAITS_MS.size} polls")
        return null
    }

    /**
     * 启动前把已存在的 task 放开可调整。
     *
     * 只对「已有 task」动手：冷启动本来一次到位，不必多花一次任务表查询的等待；
     * 这里查一次是必要成本 —— 不查就无法区分冷启动与热启动。
     */
    private suspend fun preflightResizeable(context: Context, pkg: String) {
        // 同样不能依赖 `am stack list`（Android 12+ 已移除），统一走 dumpsys
        val snapshot = TaskBinderBridge.dumpTasks(context)
            ?: exec("dumpsys activity activities", 5_000L)?.stdout ?: return
        val userId = currentUserId()
        val entry = StackListParser.findTask(snapshot, pkg, userId) ?: return
        val changed = WindowBoundsApplier.markResizeable(entry.taskId)
        log(context, "preflight $pkg task=${entry.taskId} mode=${entry.mode} resizeable=$changed")
    }

    /**
     * 计算自由窗口矩形，几何规则见 [WindowGeometry]。
     * 分层错开已移除：系统小窗自带优先级与层叠管理，应用层再做偏移无效。
     *
     * 尺寸带厂商补偿（A4）：MagicOS 会把请求再打个折（实测 74% 屏宽只渲染 60%），
     * 补偿系数按品牌给默认值、可在设置页标定，细节见 [WindowGeometry.applyVendorScale]。
     */
    private fun freeformBounds(context: Context, openCount: Int = 0): WindowRect? {
        val dm = context.resources.displayMetrics
        val base = WindowGeometry.freeformBounds(
            dm.widthPixels,
            dm.heightPixels,
        )
        // 41B：多窗共存错位 —— 第 n 个窗口按 (n*40px, n*40px) 错开并 clamp 回屏内；
        // openCount=0 时与原几何完全一致，不影响单窗行为
        return FreeformLayout.nextBounds(dm.widthPixels, dm.heightPixels, openCount, base)
    }

    /**
     * MagicOS 对 freeform 窗口有尺寸钳制（实测传入 74%×屏宽只得到 60%），
     * bounds 位置生效但尺寸不生效。找到目标 task 后强制 resize。
     *
     * 命令表与「轻量打开」侧共用 [WindowBoundsApplier]：原来两边各写一套 resize 三连，
     * 一条多 `resizeable` 标记、一条多 `w h` 形态，同一台机器的成功率因此不同。
     */
    private suspend fun applyBounds(
        context: Context,
        pkg: String,
        taskId: String,
        bounds: WindowRect,
    ) {
        val report = WindowBoundsApplier.apply(context, taskId, bounds)
        log(
            context,
            "resize $pkg task=$taskId to ${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}" +
                " -> ${report.detail}",
        )
    }

    /**
     * 构造启动 options：makeBasic + setLaunchBounds（公开 API）+ 直接写 windowingMode。
     * setLaunchBounds 把矩形以 "android:activity.launchBounds" 写入 bundle，这是浮动小窗形态的关键。
     * windowingMode 直接写 bundle key（fallback 路径），不反射非 SDK API，
     * 规避 targetSDK 35+ 的 BlockedPrivateApi 限制，真机也更稳。
     * ColorOS 私有小窗另加 mZoomLaunchFlags=4。
     *
     * 额外尝试关闭 freeform 缩放动画：该开关只有隐藏方法，反射失败直接跳过，
     * 不影响后续启动（失败退化为系统默认动画，不会抛异常到调用方）。
     */
    private fun windowModeOptions(windowMode: Int, bounds: WindowRect?): Bundle {
        val options = runCatching { ActivityOptions.makeBasic() }.getOrNull()
        val bundle = if (options == null) {
            Bundle()
        } else {
            if (bounds != null) {
                runCatching {
                    options.setLaunchBounds(Rect(bounds.left, bounds.top, bounds.right, bounds.bottom))
                }
            }
            allowBackgroundLaunch(options)
            disableFreeformAnimation(options)
            runCatching { options.toBundle() }.getOrElse { Bundle() }
        }
        bundle.putInt(KEY_WINDOWING_MODE, windowMode)
        // 兜底再写一遍：部分 ROM 的 toBundle 不认 setPendingIntentBackgroundActivityStartMode，
        // 直接把 key 塞进 bundle 命中率更高（「时光」4.2 也是这么做的）
        bundle.putInt(KEY_BAL_MODE, 1)
        if (windowMode == WindowModes.VENDOR) {
            bundle.putInt(KEY_ZOOM_LAUNCH_FLAGS, 4)
        }
        return bundle
    }

    /**
     * 放行后台启动 Activity（A6）。
     *
     * Android 14 起，从后台/由 PendingIntent 触发的启动默认会被拦成一条通知 ——
     * 轮盘点图标正好属于这一类（焦点可能还在别的应用上）。显式声明 allowed 才稳。
     * 34 以下是公开 API 不存在，反射也拿不到，直接跳过。
     */
    private fun allowBackgroundLaunch(options: ActivityOptions) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        runCatching {
            options.setPendingIntentBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
            )
        }
    }

    /**
     * 关闭 freeform 启动动画。
     * `setFreeformAnimation` 是隐藏方法，必须 getDeclaredMethod + isAccessible，
     * getMethod 会被 hidden API 名单过滤成 NoSuchMethodException。
     */
    private fun disableFreeformAnimation(options: ActivityOptions) {
        runCatching {
            ActivityOptions::class.java
                .getDeclaredMethod("setFreeformAnimation", java.lang.Boolean.TYPE)
                .apply { isAccessible = true }
                .invoke(options, false)
        }
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

    /**
     * 按 Intent 以小窗形态启动（用于「打开剪贴板链接」这类没有固定包名的场景）。
     *
     * 与 [launch] 的区别：这里没有目标包，只能靠调用方给的 Intent 让系统解析。
     * 先按窗口模式候选逐个尝试 ActivityOptions，全部被拒才退回普通启动。
     */
    fun launchIntent(context: Context, intent: Intent, mode: String = Prefs.launchMode(context)): Boolean {
        // 同 [launch]：从轮盘打开链接、扫码这类动作也是用户自己点的
        LightOpenWatcher.markManualLaunch(intent.component?.packageName)
        intent.addFlags(FLAG_LAUNCH_QUIET)
        val bounds = freeformBounds(context, FreeformWindowRegistry.list().size)
        lastLaunchBounds = bounds
        val candidates = WindowModes.candidates(mode, HonorWindowLauncher.isHonor(context))
        for (wm in candidates) {
            val ok = runCatching {
                context.startActivity(intent, windowModeOptions(wm, bounds))
            }.isSuccess
            if (ok) {
                log(context, "intent-window launch mode=$wm bounds=$bounds -> ok")
                return true
            }
        }
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    /** 需要知道为什么失败时用：返回 exit code + 截断的 stdout/stderr */
    suspend fun runShellCommandDetail(commandLine: String): String {
        val r = exec(commandLine, 15_000L) ?: return "server-dead"
        return "exit=${r.exitCode} out=${r.stdout.take(300)} err=${r.stderr.take(300)}"
    }

    /**
     * 供「轻量打开」解析 dumpsys 用：返回完整 stdout（不截断）。
     * 与 [runShellCommandDetail] 分开，因为 dumpsys 输出常常几十 KB，
     * 截断到 300 字会解析不出 task 与窗口模式。
     */
    suspend fun shellStdout(commandLine: String, timeoutMillis: Long = 10_000L): String? =
        exec(commandLine, timeoutMillis)?.stdout

    /** shell 命令结果：退出码 + 标准输出 + 标准错误 */
    data class ShellResult(val exitCode: Int, val stdout: String, val stderr: String)

    /**
     * 代码去重：所有 shell 命令共用的执行入口。
     *
     * 从 private 放开到 internal：`core` 包里的 [WindowBoundsApplier] 需要复用同一条
     * priv-kit 通道，不能再各自 new 一个 PrivilegeCommand。
     */
    internal suspend fun exec(commandLine: String, timeoutMillis: Long): ShellResult? = try {
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

    /**
     * 以 shell 身份用 app_process 加载本 APK 内的类执行。
     *
     * 用途：直调 binder（见 [TaskBinderBridge]）。priv-kit 的 startCommand 能起任意
     * 进程，把 CLASSPATH 指向本 APK 就能以 uid 2000 加载我们自己的类 ——
     * 这是 MagicOS 砍掉 `am task resize` 之后仍能操作 task 的唯一通道。
     *
     * 参数做白名单校验，因为整条命令要拼进 `sh -c`：调用方只传 op、taskId、
     * 数字或 l,t,r,b 串，出现其它字符一律拒绝，不做部分执行。
     */
    suspend fun runAppProcess(
        context: Context,
        mainClass: String,
        argv: List<String>,
        timeoutMillis: Long = 8_000L,
    ): ShellResult? {
        if (!SAFE_TOKEN.matches(mainClass) || argv.any { !SAFE_TOKEN.matches(it) }) {
            Log.d(TAG, "runAppProcess rejected: unsafe argv")
            return null
        }
        val apk = context.applicationInfo.sourceDir
        val tail = if (argv.isEmpty()) "" else " " + argv.joinToString(" ")
        val cmd = "CLASSPATH=" + quoteForShell(apk) +
            " exec /system/bin/app_process /system/bin " + mainClass + tail
        return exec(cmd, timeoutMillis)
    }

    /** app_process 的参数只允许这几类字符：类名（含点）、op 名、taskId、l,t,r,b */
    private val SAFE_TOKEN = Regex("[A-Za-z0-9._,-]{1,96}")

    private fun quoteForShell(raw: String): String = "'" + raw.replace("'", "'\\''") + "'"

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

    /**
     * shell 通道的旗标必须显式给出：`am start -f` 是整体替换，
     * 缺省时由 ActivityManager 自行决定，已有任务可能停在深层页面而不是回到入口。
     */
    private suspend fun execShell(componentName: String, windowingMode: Int): String {
        val windowFlag = if (windowingMode > 0) " --windowingMode $windowingMode" else ""
        val flagArg = if (windowingMode > 0) " -f $SHELL_LAUNCH_FLAGS" else ""
        val result = exec("am start -n $componentName$flagArg$windowFlag", 20_000L)
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
