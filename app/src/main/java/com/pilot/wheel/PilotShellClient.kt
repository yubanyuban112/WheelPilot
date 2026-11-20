package com.pilot.wheel

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.pilot.wheel.core.WindowRect
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

/**
 * [IPilotShellService] 的客户端：一次绑定、常驻复用。
 *
 * 相比老的 app_process 通道，省掉的是「每次操作冷启一个 JVM」——实测那是数百毫秒量级，
 * 而这里只有一次 binder 往返（个位数毫秒）。代价是必须处理生命周期：
 * - Shizuku 重启 / 用户撤销授权 → binder 死亡，下次调用重新绑定；
 * - 绑定失败 → 进入 [DOWN_WINDOW_MS] 冷却，期间直接走老路，不做无谓等待。
 *
 * 所有方法失败都返回 false/null，绝不抛异常：调用方都带着降级链，
 * 这里一抛反而会把整条启动流程带崩。
 */
object PilotShellClient {

    /** 探测到的触摸屏设备：路径 + ABS_MT_POSITION_X/Y 的 max */
    data class RawTouchDevice(val path: String, val xMax: Int, val yMax: Int)

    private const val TAG = "WheelPilot"
    private const val TAG_SERVICE = "pilot-shell"
    private const val SUFFIX = "pilot-shell"
    /** UserService 版本：改动 shell 侧代码必须递增，否则 Shizuku 会复用旧实例（旧 dex） */
    /**
     * 常驻进程接口版本。
     *
     * **改 shell 侧代码必须 +1**：Shizuku 的 UserServiceArgs.version 不变时不会重建进程，
     * 新方法会落在旧 dex 上（53 号坑④）。
     * 4 = 58 号新增 readClipboardText（AIDL 18）；
     * 5 = 58 号修正其空剪贴板判定（**实现改动也要 +1**：124 未升版本，靠手动 kill
     *     `com.pilot.wheel:pilot-shell` 才生效，见 `57-59_*_改动清单.md` §坑）。
     */
    private const val VERSION = 5
    private const val BIND_TIMEOUT_MS = 6_000L
    private const val DOWN_WINDOW_MS = 15_000L

    /** 调用过程本身抛异常（binder 死亡等）时的内部标记，不是 AIDL 返回值 */
    private const val ERR_THREW = -99

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var service: IPilotShellService? = null

    @Volatile
    private var downUntil = 0L

    private var connection: ServiceConnection? = null
    private val bindLock = Mutex()

    /**
     * 53 号：绑定代数与绑定时刻。
     *
     * 只为了区分两种失败形态：同一代 binder 连续失败 = **stale attach**（进程活着但
     * binder 引用已过期，`pingBinder` 可能仍为 true）；代际递增后新绑定即失败 =
     * 服务端不可用。没有这两个字段，日志里两者完全同形，无法定位。
     */
    @Volatile
    private var bindGeneration = 0

    @Volatile
    private var boundAt = 0L

    /** 后台补绑用；不参与调用路径，进程退出即结束 */
    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var binding = false

    /** 由 App.onCreate 调用；也可在任意调用点惰性补上 */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** Shizuku 侧是否具备绑定条件（没授权就完全不用试） */
    fun possible(): Boolean = runCatching {
        Shizuku.pingBinder() &&
            !Shizuku.isPreV11() &&
            Shizuku.getVersion() >= 11 &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /**
     * 55.1：诊断通道（`writeTrace`）是否就绪。
     *
     * [trace] 在 service 未绑定时**静默丢弃**整行，而几何/通道自检发生在服务 init 早期，
     * 常早于 Shizuku 绑定（实测冷启 0.2~2s、重装后更久）——调用方据此决定是否补发。
     */
    fun isTraceReady(): Boolean = service != null

    /** 当前是否有一条可用的常驻通道（冷却期内恒 false） */
    fun available(): Boolean =
        service != null && SystemClock.elapsedRealtime() >= downUntil && possible()

    /** 通道状态字符串，写日志/设置页展示用 */
    fun describe(): String = when {
        !possible() -> "shizuku-unavailable"
        available() -> "bound"
        SystemClock.elapsedRealtime() < downUntil -> "cooling"
        else -> "not-bound"
    }

    suspend fun setWindowingMode(taskId: Int, mode: Int, onTop: Boolean = true): Boolean =
        call("windowing") { it.setTaskWindowingMode(taskId, mode, onTop) }

    suspend fun resize(taskId: Int, rect: WindowRect): Boolean =
        call("resize") { it.resizeTask(taskId, rect.left, rect.top, rect.right, rect.bottom) }

    suspend fun refocus(taskId: Int): Boolean = call("refocus") { it.refocusTask(taskId) }

    suspend fun removeTask(taskId: Int): Boolean = call("remove") { it.removeTask(taskId) }

    suspend fun dumpTasks(): String? = withContext(Dispatchers.IO) {
        val svc = ensure() ?: return@withContext null
        runCatching { svc.dumpTasks() }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /**
     * 58 号：以 shell 身份读剪贴板文本。
     *
     * 返回 null = 通道不可用；空串 = 剪贴板确实为空；`ERR:` 前缀 = 读取失败（供落盘归因）。
     */
    suspend fun readClipboardText(): String? = withContext(Dispatchers.IO) {
        val svc = ensure() ?: return@withContext null
        runCatching { svc.readClipboardText() }.getOrNull()
    }

    /** 探测触摸屏设备，返回 `device|xMax|yMax` 行列表；空列表 = 探测失败/无权限 */
    suspend fun probeTouchDevices(): List<RawTouchDevice> = withContext(Dispatchers.IO) {
        val svc = ensure() ?: return@withContext emptyList()
        val text = runCatching { svc.probeTouchDevices() }.getOrNull() ?: return@withContext emptyList()
        text.lineSequence().mapNotNull { line ->
            val parts = line.split('|')
            if (parts.size < 3) return@mapNotNull null
            val xMax = parts[1].toIntOrNull() ?: return@mapNotNull null
            val yMax = parts[2].toIntOrNull() ?: return@mapNotNull null
            RawTouchDevice(parts[0], xMax, yMax)
        }.toList()
    }

    /** 探测诊断字符串（主进程打日志用，避免跨进程 Log 不可见） */
    suspend fun probeTouchDiagnostics(): String = withContext(Dispatchers.IO) {
        val svc = ensure() ?: return@withContext ""
        runCatching { svc.probeTouchDiagnostics() }.getOrDefault("")
    }

    /**
     * 注册原始触点回调（start 前调用一次；null 注销）。
     *
     * 53 号：不再走通用 [call]。通用路径把 `RemoteException` 一并吞成 `ERR_THREW`，
     * 日志里只剩 `shell raw-attach threw`，无法区分「进程正常死亡」与「stale attach」
     * （UserService 被 daemon 保活、进程还在但 binder 已过期）。这里单独捕获并落盘
     * 代际与 token 存活时长，捕获后标 UNAVAILABLE 并交给上层降级。
     */
    suspend fun attachRawTouchCallback(cb: IRawTouchCallback?): Boolean {
        val svc = ensure() ?: return false
        return withContext(Dispatchers.IO) {
            try {
                svc.attachRawTouchCallback(cb)
                true
            } catch (e: android.os.RemoteException) {
                val age = if (boundAt == 0L) -1L else SystemClock.elapsedRealtime() - boundAt
                trace(
                    "SHIZUKU_UNAVAILABLE",
                    "stale_attach gen=$bindGeneration age=${age}ms err=${e.javaClass.simpleName}:${e.message}",
                )
                markDown()
                false
            } catch (e: RuntimeException) {
                val age = if (boundAt == 0L) -1L else SystemClock.elapsedRealtime() - boundAt
                trace(
                    "SHIZUKU_UNAVAILABLE",
                    "runtime gen=$bindGeneration age=${age}ms err=${e.javaClass.simpleName}",
                )
                markDown()
                false
            }
        }
    }

    /** 启动旁路采集；false = 未启动（未绑/无权限/已在跑等） */
    suspend fun startRawTouch(device: String): Boolean =
        call("raw-start") { svc ->
            svc.startRawTouch(device).let { if (it == PilotShellService.OK) PilotShellService.OK else -1 }
        }

    /** 停止旁路采集 */
    suspend fun stopRawTouch() {
        val svc = ensure() ?: return
        runCatching { svc.stopRawTouch() }
    }

    /** 触发 InputManager.monitorInput 最小可行性验证，返回结构化结果字符串 */
    suspend fun startInputMonitorProbe(): String = withContext(Dispatchers.IO) {
        val svc = ensure() ?: return@withContext "shizuku-unavailable"
        runCatching { svc.startInputMonitorProbe() }
            .getOrElse { "probe threw ${it.javaClass.simpleName}: ${it.message}" }
    }

    /** 停止 InputMonitor 探测 */
    suspend fun stopInputMonitorProbe() {
        val svc = ensure() ?: return
        runCatching { svc.stopInputMonitorProbe() }
    }

    /** 启动全局输入旁路监听（monitorInput 方案）；false = 不可用（monitorInput 被裁/无权限） */
    suspend fun startMonitorTouch(): Boolean =
        call("monitor-touch") { svc ->
            svc.startMonitorTouch().let { if (it == PilotShellService.OK) PilotShellService.OK else -1 }
        }

    /** 停止全局输入旁路监听 */
    suspend fun stopMonitorTouch() {
        val svc = ensure() ?: return
        runCatching { svc.stopMonitorTouch() }
    }

    /** 系统性触摸感知通道探测（native 直读 + 反射三签名），返回结构化纯文本 */
    suspend fun probeAllTouchChannels(): String = withContext(Dispatchers.IO) {
        val svc = ensure() ?: return@withContext "shizuku-unavailable"
        runCatching { svc.probeAllTouchChannels() }
            .getOrElse { "probe threw ${it.javaClass.simpleName}: ${it.message}" }
    }

    /**
     * 诊断落盘：仅在已有绑定时写，绝不触发新绑定（诊断不能把 6s 绑定延迟带到调用路径）。
     * 荣耀 Android 17 过滤第三方 logcat，这是唯一可靠的真机观测通道。
     * 未绑定时静默丢弃，不影响业务。
     */
    fun trace(tag: String, message: String) {
        val svc = service ?: return
        val line = "${System.currentTimeMillis()} $tag $message"
        bgScope.launch {
            runCatching { svc.writeTrace(line) }
        }
    }

    /**
     * 以 shell 身份启动，启动源头就是目标窗口模式（A2）。
     * [rect] 为空时只带窗口模式，不改几何。
     */
    suspend fun startAsUser(
        pkg: String,
        cls: String,
        userId: Int,
        windowingMode: Int,
        rect: WindowRect?,
        quiet: Boolean = true,
    ): Boolean = call("start") {
        it.startActivityAsUser(
            pkg, cls, userId, windowingMode,
            rect?.left ?: 0, rect?.top ?: 0, rect?.right ?: 0, rect?.bottom ?: 0,
            quiet,
        )
    }

    /** binder 死了或系统重启后调用：下次 ensure 会重新绑 */
    fun invalidate() {
        service = null
    }

    /**
     * 预热：应用启动时先把 UserService 绑好，第一次转窗就不必等绑定。
     * 失败不重试也不报错 —— 真正的调用点都带降级链。
     */
    suspend fun warmUp() {
        if (!possible()) return
        runCatching { ensure() }
    }

    /**
     * 后台补绑：调用方不等待，本次仍走老路。
     *
     * [warmUp] 只在 App.onCreate 试一次。若那一刻 Shizuku 还没起来（后启动、重启过、
     * 用户后授权），之后就再没人触发绑定，[available] 永久 false ——
     * 11-19 09:57 日志里全程没有一行 `shell-start` 正是这个原因。
     * 这里在关键路径外补一次，绑定耗时（最多 [BIND_TIMEOUT_MS]）不阻塞转窗。
     */
    fun kickBind() {
        if (binding || service != null || !possible()) return
        if (SystemClock.elapsedRealtime() < downUntil) return
        binding = true
        bgScope.launch {
            try {
                ensure()
            } finally {
                binding = false
            }
        }
    }

    // -----------------------------------------------------------------------

    private suspend fun call(op: String, block: (IPilotShellService) -> Int): Boolean {
        val svc = ensure() ?: return false
        val code = withContext(Dispatchers.IO) {
            try {
                block(svc)
            } catch (t: Throwable) {
                // binder 死亡 / 反射失败都会走到这里：整条通道标记降级，让调用方走老路
                Log.d(TAG, "shell $op threw ${t.javaClass.simpleName}")
                ERR_THREW
            }
        }
        if (code == ERR_THREW) {
            markDown()
            return false
        }
        if (code != 0) {
            Log.d(TAG, "shell $op code=$code")
            // 只有「服务不可用」才整条通道降级；方法不存在/参数不匹配只影响这一次
            if (code == PilotShellService.ERR_NO_SERVICE) markDown()
        }
        return code == 0
    }

    private suspend fun ensure(): IPilotShellService? {
        if (!possible()) return null
        service?.let { svc ->
            // 每次用前 ping 一次 binder：进程被杀后缓存对象还在，调用会抛 DeadObjectException
            val alive = runCatching { svc.asBinder().pingBinder() }.getOrDefault(false)
            if (alive) return svc
            Log.d(TAG, "shell binder dead, rebinding")
            service = null
        }
        if (SystemClock.elapsedRealtime() < downUntil) return null
        return bindLock.withLock {
            service ?: bindOnce()
        }
    }

    private suspend fun bindOnce(): IPilotShellService? {
        val ctx = appContext ?: return null
        val deferred = CompletableDeferred<IPilotShellService?>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val svc = if (binder == null || !binder.pingBinder()) {
                    null
                } else {
                    runCatching { IPilotShellService.Stub.asInterface(binder) }.getOrNull()
                }
                if (svc == null) Log.d(TAG, "shell bind returned invalid binder")
                deferred.complete(svc)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                Log.d(TAG, "shell service disconnected")
                // 53 号：正常死亡与 stale attach 在通用路径上同形，这里显式区分
                PilotShellClient.trace("SHIZUKU_UNAVAILABLE", "dead_object gen=$bindGeneration")
                service = null
                if (!deferred.isCompleted) deferred.complete(null)
            }
        }
        connection = conn
        val args = userServiceArgs(ctx)
        val started = runCatching { Shizuku.bindUserService(args, conn) }.isSuccess
        if (!started) {
            Log.d(TAG, "shell bind request rejected")
            markDown()
            return null
        }
        val svc = withTimeoutOrNull(BIND_TIMEOUT_MS) { deferred.await() }
        if (svc == null) {
            Log.d(TAG, "shell bind timeout")
            markDown()
            return null
        }
        service = svc
        downUntil = 0L
        bindGeneration++
        boundAt = SystemClock.elapsedRealtime()
        Log.d(TAG, "shell bound gen=$bindGeneration (${describe()})")
        return svc
    }

    private fun markDown() {
        service = null
        downUntil = SystemClock.elapsedRealtime() + DOWN_WINDOW_MS
    }

    private fun userServiceArgs(context: Context): Shizuku.UserServiceArgs {
        val args = Shizuku.UserServiceArgs(
            ComponentName(context.packageName, PilotShellService::class.java.name),
        )
            .tag(TAG_SERVICE)
            .processNameSuffix(SUFFIX)
            .debuggable(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
            .version(VERSION)
        // daemon=true 让 Shizuku 保活这个进程 —— 常驻通道的全部意义就在这一行
        runCatching { args.daemon(true) }
        return args
    }
}
