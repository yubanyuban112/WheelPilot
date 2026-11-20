package com.pilot.wheel

import android.content.Context
import android.content.Intent
import android.util.Log
import com.pilot.wheel.core.LightOpenOutcome
import com.pilot.wheel.core.LightOpenPolicy
import com.pilot.wheel.core.TaskBinderBridge
import com.pilot.wheel.core.WindowBoundsApplier
import com.pilot.wheel.core.WindowGeometry
import com.pilot.wheel.core.WindowModes
import com.pilot.wheel.core.WindowRect
import com.pilot.wheel.core.WindowStateParser
import kotlinx.coroutines.delay
import priv.kit.core.Privilege
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 「轻量打开」的转窗执行器。
 *
 * 目标应用此时已经在前台全屏显示 —— 这里要把它变成浮窗。
 *
 * 降级顺序（每级都实际校验 windowingMode，不靠 exit code 判断成功）：
 *
 * | 级别 | 手段 | 是否重投 Intent | 适用策略 |
 * |---|---|---|---|
 * | 快路径 | 荣耀 oneStep / ActivityOptions（仅冷启动、且无存活 task） | 新建 task | FULL |
 * | 0 | binder 直调 `setTaskWindowingMode`（task 级，不过 exported 检查） | 否 | 全部 |
 * | 1 | 只改几何（`am task resize` 系列） | 否 | 全部 |
 * | 2 | 同组件 + SINGLE_TOP 推送窗口模式（命中已有实例走 onNewIntent） | 否 | 全部 |
 * | 2.5 | 分屏兜底（`LAUNCH_ADJACENT`） | 否 | FULL |
 * | 3 | 退回入口 Activity 重新拉起 | 是 | FULL |
 * | 4 | 先把 task 转到后台，再以浮窗重新拉起 | 是 | FULL |
 * | 兜底 | [AppLauncher.launch] 的 ActivityOptions 通道 | 新建 task | FULL |
 *
 * 为什么 3 / 4 级只对 FULL 开放：分享与登录授权靠 `resultTo` 链回传结果，
 * 重投一次 Intent 就等于把流程掐断（表现是「分享出去的内容对方收不到」）。
 * 全部失败时退回全屏，绝不为了窗口形态牺牲功能。
 *
 * 失败不弹提示：用户看到的就是「正常全屏打开」，等同于没有这个功能。
 */
object LightOpenController {

    private const val TAG = "WheelPilot"

    /** WINDOWING_MODE_SPLIT_SCREEN_PRIMARY */
    private const val SPLIT_MODE = 3

    /** 窗口事务提交需要时间：首轮多等一点，之后按固定间隔复查 */
    private const val VERIFY_FIRST_MS = 350L
    private const val VERIFY_NEXT_MS = 300L

    /** 几何改动一轮就够；模式推送要等窗口事务提交，给两轮 */
    private const val VERIFY_ROUNDS_FAST = 1
    private const val VERIFY_ROUNDS_NORMAL = 2

    /** 分享/授权冷启动：等目标应用自己建出 task 的上限与轮询间隔 */
    private const val WAIT_TASK_TIMEOUT_MS = 2_500L
    private const val WAIT_TASK_POLL_MS = 250L

    /** 移后台后到重新拉起之间的稳定期 */
    private const val BACK_SETTLE_MS = 150L

    /** dumpsys 的输出常达几十 KB，超时给足，且不能截断（截断就解析不出 task） */
    private const val DUMP_TIMEOUT_MS = 10_000L

    private const val DUMP_COMMAND = "dumpsys activity activities"

    /** 让已存在的栈顶实例复用，走 onNewIntent 而不是新建 Activity */
    private const val FLAG_SINGLE_TOP = Intent.FLAG_ACTIVITY_SINGLE_TOP

    /** 与现有任务并排（分屏兜底用） */
    private const val FLAG_LAUNCH_ADJACENT = Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT

    /** 在飞标记：同一时刻只允许一次转窗（协程调度在多线程，用原子量而不是 volatile 布尔） */
    private val busy = AtomicBoolean(false)

    /** 转窗；返回值供日志与统计使用，[LightOpenOutcome.windowed] 决定是否推实况通知 */
    suspend fun convert(
        context: Context,
        pkg: String,
        policy: LightOpenPolicy = LightOpenPolicy.FULL,
    ): LightOpenOutcome {
        if (!busy.compareAndSet(false, true)) return LightOpenOutcome.BUSY
        return try {
            doConvert(context, pkg, policy)
        } finally {
            busy.set(false)
        }
    }

    // -----------------------------------------------------------------------
    // 主流程：一级一个函数，任何一级成功即 return，不留深层嵌套
    // -----------------------------------------------------------------------

    private suspend fun doConvert(
        context: Context,
        pkg: String,
        policy: LightOpenPolicy,
    ): LightOpenOutcome {
        val hasShell = Privilege.serverState.value != null
        val launchMode = Prefs.launchMode(context)
        val honor = HonorWindowLauncher.isHonor(context)
        val dump = if (hasShell) dump() else ""

        // 快路径只用于「目标确实没有 task」的冷启动：MagicOS 不认 AOSP freeform(5)，
        // shell 四级每级 verify 都要白等约 950ms，实测 4.5s 才 fallback 到 oneStep(102)。
        // 已有 task 时若走 oneStep（NEW_TASK 拉 launcher），会新建第二个窗口
        // —— 用户报障「全屏微信上又浮一个小窗微信」。
        honorColdFastPath(context, pkg, policy, launchMode, honor, dump)?.let { return it }

        // 「任意一个 task 已是浮窗」就算完成：同包多 task 时浮起来的那个可能排在后面，
        // 只看第一个会把已转好的判成失败，再转一次就叠出第二个窗口。
        if (hasShell && WindowStateParser.isAnyFreeform(dump, pkg)) {
            log(context, "convert $pkg: already freeform")
            return LightOpenOutcome.ALREADY
        }

        if (!hasShell) return fallback(context, pkg, policy, hasShell)

        enableFreeformSupport()
        // 分享/授权永远是 GEOMETRY_ONLY，绝不因为「冷启动」升级成重投：重投会把微信首页
        // 拉到前台，分享面板选中的「选择聊天」被堆成全屏，结果回传链断裂。
        // 冷启动时改为等目标 task 自己起来，再只改几何。
        val taskIds = resolveTaskIds(policy, dump, pkg)
        val bounds = freeformBounds(context, policy)
        val attempt = Conversion(
            context = context,
            pkg = pkg,
            policy = policy,
            launchMode = launchMode,
            honor = honor,
            taskId = taskIds.firstOrNull(),
            taskCount = taskIds.size,
            bounds = bounds,
        )

        step0BinderMode(attempt)?.let { return it }
        step1Resize(attempt)?.let { return it }
        step2SameInstance(attempt)?.let { return it }

        // 只改几何 + 同实例推送都不成立时：分享/授权到此为止，保持全屏。
        // 再往下都要重投 Intent，会让接收方的分享页变成「普通打开」、结果回传失败。
        if (policy == LightOpenPolicy.GEOMETRY_ONLY) {
            log(context, "convert $pkg keep fullscreen: resize+same-instance push failed task=${attempt.taskId}")
            return LightOpenOutcome.KEPT
        }

        step25Split(attempt)?.let { return it }
        step3LaunchEntry(attempt)?.let { return it }
        step4Relaunch(attempt)?.let { return it }
        return fallback(context, pkg, policy, hasShell)
    }

    // -----------------------------------------------------------------------
    // 各级转窗手段
    // -----------------------------------------------------------------------

    /**
     * 荣耀冷启动快路径：oneStep(102) 或 ActivityOptions 直接以浮窗新建 task。
     *
     * 仅在「非几何受限策略 + 非 freeform 调试档 + 荣耀 + 目标无存活 task」时启用。
     */
    private suspend fun honorColdFastPath(
        context: Context,
        pkg: String,
        policy: LightOpenPolicy,
        launchMode: String,
        honor: Boolean,
        dump: String,
    ): LightOpenOutcome? {
        if (policy == LightOpenPolicy.GEOMETRY_ONLY) return null
        if (launchMode == Prefs.LAUNCH_FREEFORM) return null
        if (!honor) return null
        if (WindowStateParser.findTaskIds(dump, pkg).isNotEmpty()) return null

        val result = AppLauncher.launch(context, pkg, launchMode)
        log(context, "convert $pkg honor fastpath(cold) -> $result")
        return if (isWindowLaunch(result)) LightOpenOutcome.INTENT else LightOpenOutcome.FAILED
    }

    /**
     * 第 0 级：binder 直改窗口模式。
     *
     * 唯一能救「分享 / 授权」的手段：`setTaskWindowingMode` 是 task 级操作，不经过
     * Activity 的 exported 检查；微信 `SendAppMessageWrapperUI`、小红书 `NoteDetailActivity`
     * 都是未导出的内部页，从 shell 用 `am start` 重投必然 SecurityException（真机已实证）。
     * 它也不重投 Intent、不动 Activity 栈，`resultTo` 与「已选好友」状态都保留。
     */
    private suspend fun step0BinderMode(a: Conversion): LightOpenOutcome? {
        val taskId = a.taskId ?: return null
        val bounds = a.bounds ?: return null
        for (mode in WindowModes.candidates(a.launchMode, a.honor)) {
            val ok = TaskBinderBridge.setWindowingMode(a.context, taskId, mode)
            a.log("convert ${a.pkg} step0 windowing task=$taskId mode=$mode -> $ok")
            if (ok && verifyForm(a.pkg, VERIFY_ROUNDS_NORMAL, WindowStateParser::isAnyWindowed)) {
                // 模式改了但几何未必按预期，补一次矩形
                TaskBinderBridge.resize(a.context, taskId, bounds)
                // 改完模式再走一次「失焦→设焦点」：不做这一步，系统可能把已存在的 task
                // 当成重新前台化，栈被重置回入口页。
                TaskBinderBridge.refocus(a.context, taskId)
                a.log("convert ${a.pkg} step0 bounds task=$taskId rect=${a.rect}")
                return LightOpenOutcome.TASK_MODE
            }
            // 通道本身不可用（app_process 起不来 / 没权限），换模式也没意义
            if (!TaskBinderBridge.usable()) break
        }
        return null
    }

    /**
     * 第 1 级：只改几何。
     *
     * 不动 Activity 栈、不重投 Intent —— 分享与登录授权的 `resultTo` 链完全不受影响，
     * 通知点进来的聊天页也不会被带回首页。
     */
    private suspend fun step1Resize(a: Conversion): LightOpenOutcome? {
        val taskId = a.taskId ?: return null
        val bounds = a.bounds ?: return null
        val report = WindowBoundsApplier.apply(a.context, taskId, bounds)
        a.log(
            "convert ${a.pkg} step1 resize task=$taskId tasks=${a.taskCount} " +
                "rect=${a.rect} -> ${report.detail}",
        )
        return if (verifyForm(a.pkg, VERIFY_ROUNDS_FAST, WindowStateParser::isAnyFreeform)) {
            LightOpenOutcome.RESIZE
        } else {
            null
        }
    }

    /**
     * 第 2 级：同实例模式推送。
     *
     * 关键在「同组件 + SINGLE_TOP」：`am start` 命中 task 栈顶那个已有实例，走 onNewIntent，
     * 不新建 Activity、不动 task、ActivityRecord 上的 `resultTo` 都保留，所以分享/授权
     * 也可以安全尝试 —— 这与「重投 launcher 拉起应用首页」是两件不同的事。
     */
    private suspend fun step2SameInstance(a: Conversion): LightOpenOutcome? {
        val top = WindowStateParser.findTopActivity(dump(), a.pkg)
        if (top == null) {
            a.log("convert ${a.pkg} step2 skip: top activity not found")
            return null
        }
        for (mode in WindowModes.candidates(a.launchMode, a.honor)) {
            val detail = AppLauncher.runShellCommandDetail(
                "am start -n $top -f $FLAG_SINGLE_TOP --windowingMode $mode",
            )
            a.log("convert ${a.pkg} step2 top=$top mode=$mode -> $detail")
            if (!verifyForm(a.pkg, VERIFY_ROUNDS_NORMAL, WindowStateParser::isAnyFreeform)) continue
            // 模式推送只带窗口形态，不带矩形：进窗后补一次几何
            val taskId = a.taskId
            val bounds = a.bounds
            if (taskId != null && bounds != null) {
                WindowBoundsApplier.apply(a.context, taskId, bounds)
                a.log("convert ${a.pkg} step2 apply bounds task=$taskId rect=${a.rect}")
            }
            return LightOpenOutcome.TOP
        }
        return null
    }

    /**
     * 第 2.5 级：分屏兜底。
     *
     * 有些 ROM 干脆不给 freeform（无论 5 还是 100 都被拒），但允许把应用塞进分屏下半屏。
     * 形态不如浮窗，但比「整屏铺开」打断感小得多，且保住 `resultTo` 链的前提与第 2 级相同
     * （同组件 + 相邻启动旗标），失败再往下走重投。
     */
    private suspend fun step25Split(a: Conversion): LightOpenOutcome? {
        val top = WindowStateParser.findTopActivity(dump(), a.pkg) ?: return null
        val cmd = "am start -n $top -f $FLAG_LAUNCH_ADJACENT --windowingMode $SPLIT_MODE"
        val detail = AppLauncher.runShellCommandDetail(cmd)
        a.log("convert ${a.pkg} step2.5 split top=$top -> ${detail.take(120)}")
        // 分屏容器里的窗口模式未必是小窗值，这里只确认「已脱离全屏」即可
        if (!WindowStateParser.isAnyWindowed(dump(), a.pkg)) return null
        return LightOpenOutcome.SPLIT
    }

    /** 第 3 级：退回应用入口 Activity 重新拉起（可能回到应用首页） */
    private suspend fun step3LaunchEntry(a: Conversion): LightOpenOutcome? {
        val launch = launchComponent(a) ?: return null
        val detail = AppLauncher.runShellCommandDetail(
            "am start -n $launch --windowingMode ${WindowModes.FREEFORM}",
        )
        a.log("convert ${a.pkg} step3 launch=$launch -> $detail")
        return if (verifyForm(a.pkg, VERIFY_ROUNDS_NORMAL, WindowStateParser::isAnyFreeform)) {
            LightOpenOutcome.LAUNCH
        } else {
            null
        }
    }

    /**
     * 第 4 级：先把已有 task 转到后台，再以浮窗重新拉起。
     * 部分 ROM 上 task 在后台时 `am start --windowingMode 5` 会新建浮窗而不是复用旧的全屏 task。
     * 代价是可能回到应用首页，只在允许重投的策略下尝试。
     */
    private suspend fun step4Relaunch(a: Conversion): LightOpenOutcome? {
        val taskId = a.taskId ?: return null
        val launch = launchComponent(a) ?: return null
        AppLauncher.shellStdout("am task move-to-back $taskId")
        delay(BACK_SETTLE_MS)
        val detail = AppLauncher.runShellCommandDetail(
            "am start -n $launch --windowingMode ${WindowModes.FREEFORM}",
        )
        a.log("convert ${a.pkg} step4 relaunch task=$taskId -> $detail")
        return if (verifyForm(a.pkg, VERIFY_ROUNDS_NORMAL, WindowStateParser::isAnyFreeform)) {
            LightOpenOutcome.RELAUNCH
        } else {
            null
        }
    }

    /**
     * 兜底：ActivityOptions / 荣耀 oneStep 通道（无特权时唯一路径）。
     *
     * 两个调用点（主流程无特权、shell 各级全失败）共用这一段，避免同一套
     * `when(result)` 判定写两遍 —— 之前两处一字不差，改一处必漏一处。
     */
    private suspend fun fallback(
        context: Context,
        pkg: String,
        policy: LightOpenPolicy,
        hasShell: Boolean,
    ): LightOpenOutcome {
        if (policy == LightOpenPolicy.GEOMETRY_ONLY) {
            // 重投会打断分享/授权，宁可不转
            log(context, "convert $pkg keep fullscreen: geometry-only, no relaunch")
            return if (hasShell) LightOpenOutcome.KEPT else LightOpenOutcome.NO_PRIVILEGE
        }
        val result = AppLauncher.launch(context, pkg, Prefs.launchMode(context))
        log(context, "convert $pkg fallback launch -> $result")
        return when {
            isWindowLaunch(result) -> LightOpenOutcome.INTENT
            hasShell -> LightOpenOutcome.FAILED
            else -> LightOpenOutcome.NO_PRIVILEGE
        }
    }

    // -----------------------------------------------------------------------
    // 公共小工具
    // -----------------------------------------------------------------------

    /**
     * 一次转窗的上下文，避免每级函数都排一长串相同参数。
     * [taskId] / [bounds] 在冷启动场景可能为 null，各级自行判空后返回 null 交给下一级。
     */
    private class Conversion(
        val context: Context,
        val pkg: String,
        val policy: LightOpenPolicy,
        val launchMode: String,
        val honor: Boolean,
        val taskId: String?,
        /** 同包 task 数量：只进日志，不再当闸门 */
        val taskCount: Int,
        val bounds: WindowRect?,
    ) {

        val rect: String?
            get() = bounds?.let { "${it.left},${it.top},${it.right},${it.bottom}" }

        fun log(message: String) {
            Log.d(TAG, message)
            PilotLog.d(context, TAG, message)
        }
    }

    /**
     * 解析目标 task id 列表（最上层在前）。
     *
     * 取 dump 顺序里的第一个 —— 它就是最上层那个，也是用户正在看的那个。
     * 旧版 GEOMETRY_ONLY 要求「同包只有一个 task」才动手，应用带后台残留 task 时
     * taskId 恒为 null，连 resize 都不发（真机日志：`convert com.xingin.xhs … task=null`）。
     * task 数量只进日志，不再当闸门。
     */
    private suspend fun resolveTaskIds(
        policy: LightOpenPolicy,
        dump: String,
        pkg: String,
    ): List<String> {
        val ids = WindowStateParser.findTaskIds(dump, pkg)
        if (ids.isNotEmpty() || policy != LightOpenPolicy.GEOMETRY_ONLY) return ids
        // 冷启动：等目标 task 自己起来（微信这类实测 2–3 秒），只改几何不重投
        val waited = waitForTask(pkg, WAIT_TASK_TIMEOUT_MS) ?: return emptyList()
        return listOf(waited)
    }

    /**
     * 轮询等待目标 task 出现。
     *
     * 直接 `am start <launcher> --windowingMode 5` 会把应用首页拉起来，
     * 而分享面板选中的 Activity 会再建一个全屏 task —— 两个窗口叠在一起，分享链断。
     */
    private suspend fun waitForTask(pkg: String, timeoutMs: Long): String? {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            delay(WAIT_TASK_POLL_MS)
            val snapshot = AppLauncher.shellStdout(DUMP_COMMAND, DUMP_TIMEOUT_MS) ?: continue
            WindowStateParser.findTaskId(snapshot, pkg)?.let { return it }
        }
        return null
    }

    /**
     * 校验真实窗口形态：`am start` 的退出码不可靠（被系统拦截时也可能返回 0），
     * 必须读 windowingMode。首轮等 350 ms 让窗口事务提交。
     *
     * [checker] 收 dump 与包名，返回「是否已达到目标形态」—— 原来 [verify] 与
     * `verifyWindowed` 两个函数循环体逐字相同、只差一个判据，合并为此处的参数。
     */
    private suspend fun verifyForm(
        pkg: String,
        rounds: Int,
        checker: (String, String) -> Boolean,
    ): Boolean {
        repeat(rounds) { round ->
            delay(if (round == 0) VERIFY_FIRST_MS else VERIFY_NEXT_MS)
            val dump = AppLauncher.shellStdout(DUMP_COMMAND, DUMP_TIMEOUT_MS) ?: return@repeat
            if (checker(dump, pkg)) return true
        }
        return false
    }

    /** 读一次任务表；拿不到返回空串，各解析器对空串一律返回「无 task」 */
    private suspend fun dump(): String = AppLauncher.shellStdout(DUMP_COMMAND, DUMP_TIMEOUT_MS).orEmpty()

    private suspend fun enableFreeformSupport() {
        // freeform 与可调整尺寸两项系统开关，缺任一项很多应用进不了浮窗
        AppLauncher.shellStdout("settings put global enable_freeform_support 1")
        AppLauncher.shellStdout("settings put secure force_resizable_activities 1")
    }

    private fun launchComponent(a: Conversion): String? = runCatching {
        a.context.packageManager.getLaunchIntentForPackage(a.pkg)?.component?.flattenToShortString()
    }.getOrNull()

    /** [AppLauncher.launch] 的返回值是否代表「已进浮窗」 */
    private fun isWindowLaunch(result: String): Boolean =
        result.startsWith("intent-window") || result == "honor-onestep" || result == "shell-ok"

    /**
     * 本次转窗该用多大窗口。
     *
     * 尺寸标定（设置页滑杆）必须与 [AppLauncher] 走同一条路径：重构前这里不传
     * `windowScalePercent`，于是同一台 MagicOS 上「轮盘启动」与「轻量打开」得到的
     * 窗口大小不一致（轻量打开那侧恒用 100%，比厂商折算后偏小约 17%）。
     *
     * 分享/授权要在浮窗里完成「选联系人 + 打字」，原生 58% 屏高会把内容裁掉，
     * 因此改用接近全屏可用区的几何。
     */
    private fun freeformBounds(context: Context, policy: LightOpenPolicy): WindowRect? {
        val dm = context.resources.displayMetrics
        return if (policy == LightOpenPolicy.GEOMETRY_ONLY) {
            WindowGeometry.contentBounds(dm.widthPixels, dm.heightPixels)
        } else {
            WindowGeometry.freeformBounds(dm.widthPixels, dm.heightPixels)
        }
    }

    private fun log(context: Context, message: String) {
        Log.d(TAG, message)
        PilotLog.d(context, TAG, message)
    }
}
