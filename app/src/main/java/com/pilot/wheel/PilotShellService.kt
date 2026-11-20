package com.pilot.wheel

import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.annotation.Keep
import java.lang.reflect.Method

/**
 * 常驻 shell 身份的 task 操作服务，由 Shizuku 以 uid 2000 拉起。
 *
 * 与 shell/AtmCli 的关系：AtmCli 是「每次 app_process 冷启一个 JVM」的一次性入口
 * （保留为兜底），本类是常驻版本 —— 进程只起一次，之后每次操作只是一次 binder 往返。
 *
 * 只依赖反射，不 import 任何需要 Context 的东西：本进程没有 Application，
 * 拿不到 Context，也不需要。
 *
 * 为什么不直接把 IActivityTaskManager 缓存成字段就完事：
 * system_server 重启（或 ROM 热更新）后旧 binder 会变成 dead object，
 * 每次调用前用 pingBinder 验活，死了就重新取，避免整个通道长期假死。
 */
@Keep
class PilotShellService : IPilotShellService.Stub() {

    @Volatile
    private var atm: Any? = null

    /** 记住 startActivityAsUser 的签名，避免每次调用都遍历一遍方法表 */
    @Volatile
    private var startAsUser: Method? = null

    init {
        // UserService 进程不走 Application.attachBaseContext，hidden API 豁免要在这里补。
        // 失败不致命：AtmCli 用到的 ServiceManager / Stub.asInterface 本来就在灰名单里。
        runCatching {
            Class.forName("org.lsposed.hiddenapibypass.HiddenApiBypass")
                .getMethod("addHiddenApiExemptions", Array<String>::class.java)
                .invoke(null, arrayOf("L"))
        }.onFailure { Log.d(TAG, "hidden api exemption skipped: ${it.javaClass.simpleName}") }
    }

    // -----------------------------------------------------------------------
    // AIDL 实现
    // -----------------------------------------------------------------------

    override fun setTaskWindowingMode(taskId: Int, mode: Int, onTop: Boolean): Int = guarded("windowing") {
        val svc = requireAtm()
        val m = Class.forName(IFACE).getMethod(
            "setTaskWindowingMode", Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
        )
        m.isAccessible = true
        val ret = m.invoke(svc, taskId, mode, onTop)
        Log.d(TAG, "windowing task=$taskId mode=$mode onTop=$onTop ret=$ret")
        OK
    }

    override fun resizeTask(taskId: Int, left: Int, top: Int, right: Int, bottom: Int): Int =
        guarded("resize") {
            val svc = requireAtm()
            val rectCls = Class.forName(RECT)
            val rect = rectCls
                .getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .newInstance(left, top, right, bottom)
            val m = findResizeMethod(rectCls) ?: return@guarded ERR_NO_METHOD
            val ps = m.parameterTypes
            val vals = arrayOfNulls<Any>(ps.size)
            vals[0] = taskId
            vals[1] = rect
            for (i in 2 until ps.size) {
                vals[i] = if (ps[i] == Boolean::class.javaPrimitiveType) false else 0
            }
            val ret = m.invoke(svc, *vals)
            Log.d(TAG, "resize task=$taskId rect=$left,$top,$right,$bottom ret=$ret")
            OK
        }

    /**
     * 重聚焦：先失焦再设焦点。
     *
     * 直接 setFocusedTask(taskId) 在 task 已经是「栈顶但失焦」时不会触发重新布局，
     * 窗口维持旧尺寸；中间插一次 -1 才会走完整的 focus 变更流程。
     */
    override fun refocusTask(taskId: Int): Int = guarded("refocus") {
        val svc = requireAtm()
        val intT = Int::class.javaPrimitiveType!!
        val focus = Class.forName(IFACE).getMethod("setFocusedTask", intT)
        focus.isAccessible = true
        runCatching { focus.invoke(svc, -1) }
        SystemClock.sleep(70L)
        val ret = focus.invoke(svc, taskId)
        Log.d(TAG, "refocus task=$taskId ret=$ret")
        OK
    }

    override fun startActivityAsUser(
        pkg: String,
        cls: String,
        userId: Int,
        windowingMode: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        quiet: Boolean,
    ): Int = guarded("start") {
        val svc = requireAtm()
        val m = startAsUser ?: findStartAsUser().also { startAsUser = it }
            ?: return@guarded ERR_NO_METHOD
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = ComponentName(pkg, cls)
            // 与 `am start -f 0x10200000` 对齐：NEW_TASK | RESET_TASK_IF_NEEDED
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                (if (quiet) Intent.FLAG_ACTIVITY_NO_ANIMATION else 0)
        }
        val bundle = Bundle().apply {
            putInt(KEY_WINDOWING_MODE, windowingMode)
            if (right > left && bottom > top) {
                putParcelable(KEY_LAUNCH_BOUNDS, Rect(left, top, right, bottom))
            }
            // Android 14+ 的后台启动放行：shell 身份本来就允许，这里显式写一次更稳
            putInt(KEY_BAL_MODE, 1)
            if (isXiaomi()) putInt(KEY_LAUNCH_STACK_ID, 2)
        }
        val ps = m.parameterTypes
        val args = arrayOfNulls<Any>(ps.size)
        var bundleIndex = -1
        var firstStringUsed = false
        for (i in ps.indices) {
            when {
                ps[i] == Intent::class.java -> args[i] = intent
                ps[i] == Bundle::class.java -> {
                    args[i] = bundle
                    bundleIndex = i
                }
                ps[i] == String::class.java -> {
                    // 第 1 个 String 是 callingPackage，必须给 shell；其余（featureId、
                    // resolvedType）给 null 让系统自己解析
                    args[i] = if (!firstStringUsed) {
                        firstStringUsed = true
                        "com.android.shell"
                    } else null
                }
                ps[i] == Int::class.javaPrimitiveType -> args[i] = 0
                ps[i] == Boolean::class.javaPrimitiveType -> args[i] = false
                ps[i].isPrimitive -> args[i] = 0
                else -> args[i] = null
            }
        }
        // userId 的位置：AOSP 一直是紧跟 Bundle 的那个 int；找不到就用最后一个 int
        val userIdIndex = when {
            bundleIndex >= 0 && bundleIndex + 1 < ps.size &&
                ps[bundleIndex + 1] == Int::class.javaPrimitiveType -> bundleIndex + 1
            ps.last() == Int::class.javaPrimitiveType -> ps.size - 1
            else -> -1
        }
        if (userIdIndex >= 0) args[userIdIndex] = userId
        val ret = m.invoke(svc, *args)
        Log.d(TAG, "startAsUser $pkg/$cls userId=$userId mode=$windowingMode ret=$ret")
        if (ret is Int && ret < 0) ret else OK
    }

    override fun removeTask(taskId: Int): Int = guarded("remove") {
        val svc = requireAtm()
        val m = Class.forName(IFACE).getMethod("removeTask", Int::class.javaPrimitiveType)
        m.isAccessible = true
        val ret = runCatching { m.invoke(svc, taskId) }.getOrNull()
        Log.d(TAG, "removeTask $taskId ret=$ret")
        OK
    }

    override fun dumpTasks(): String = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("/system/bin/sh", "-c", "dumpsys activity activities"))
        val text = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor()
        text
    }.getOrElse {
        Log.d(TAG, "dumpTasks failed: ${it.javaClass.simpleName}")
        ""
    }

    // -----------------------------------------------------------------------
    // 旁路原始触摸采集（getevent 只读，不消费，触摸照常发给下层）
    // -----------------------------------------------------------------------

    /**
     * 探测触摸屏设备。getevent -lp 列出每个设备支持的事件位图，
     * 这里筛选「同时支持 ABS_MT_POSITION_X(0x35) 与 ABS_MT_POSITION_Y(0x36)」的
     * 设备，并解析出它们的 max 值。
     *
     * 返回一行一个设备：`/dev/input/eventN|xMax|yMax`，无设备返回空串。
     * 注意 shell 身份（uid 2000）未必在 input 组：个别 ROM 收紧后这里返回空，
     * 用户态据此降级回无障碍路径。
     */
    override fun probeTouchDevices(): String {
        return try {
            val p = Runtime.getRuntime().exec(
                arrayOf("/system/bin/sh", "-c", "getevent -lp"),
            )
            val text = p.inputStream.bufferedReader().use { it.readText() }
            p.waitFor()
            parseTouchDevices(text)
        } catch (t: Throwable) {
            Log.d(TAG, "probeTouchDevices failed: ${t.javaClass.simpleName}: ${t.message}")
            ""
        }
    }

    /**
     * 探测诊断：把 getevent -lp 的原始输出与 /dev/input 权限信息打包返回，
     * 由主进程打印。返回三段用 `\n` 分隔：
     *   line0: getevent -lp 的 stdout 字节数与 stderr（截断）
     *   line1: getevent -lp 的 stdout 前 800 字（截断）
     *   line2: ls -l /dev/input/ 前 500 字
     */
    override fun probeTouchDiagnostics(): String {
        return try {
            val p = Runtime.getRuntime().exec(
                arrayOf("/system/bin/sh", "-c", "getevent -lp"),
            )
            val stdout = p.inputStream.bufferedReader().use { it.readText() }
            val stderr = p.errorStream.bufferedReader().use { it.readText() }
            p.waitFor()
            val ls = Runtime.getRuntime().exec(
                arrayOf("/system/bin/sh", "-c", "ls -l /dev/input/ 2>&1 | head -20"),
            )
            val lsText = ls.inputStream.bufferedReader().use { it.readText() }
            ls.waitFor()
            val sb = StringBuilder()
            sb.append("getevent-lp stdout=").append(stdout.length).append("B stderr=")
                .append(stderr.trim().take(200)).append('\n')
            sb.append("getevent-lp head: ").append(stdout.trim().take(800)).append('\n')
            sb.append("ls /dev/input: ").append(lsText.trim().take(500))
            sb.toString()
        } catch (t: Throwable) {
            "probeTouchDiagnostics failed: ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    /** 解析 getevent -lp 输出：返回触摸屏设备及其 X/Y max */
    private fun parseTouchDevices(text: String): String {
        val candidates = mutableListOf<Triple<String, Int, Int>>() // (dev, xMax, yMax)
        // 每个设备块以 "add device N: /dev/input/eventX" 开头
        val blocks = text.split(Regex("(?m)^add device"))
        for (block in blocks) {
            val devMatch = Regex("/dev/input/event\\d+").find(block) ?: continue
            val dev = devMatch.value
            val hasX = block.contains("ABS_MT_POSITION_X")
            val hasY = block.contains("ABS_MT_POSITION_Y")
            if (!hasX || !hasY) continue
            // 排除蓝牙外设/手环：名称含这些关键词的不是手机触摸屏，会抢在真触摸屏之前
            // 被 devices.first() 选中（HUAWEI Band 手环就同时带 ABS_MT_POSITION_X/Y）。
            val nameMatch = Regex("name:\\s*\"([^\"]+)\"").find(block)
            val name = nameMatch?.groupValues?.get(1)?.lowercase() ?: ""
            val isPeripheral = listOf("band", "bluetooth", "hid", "keyboard", "mouse",
                "consumer", "stylus", "pen", "wacom").any { name.contains(it) }
            if (isPeripheral) continue
            // max 行形如：ABS_MT_POSITION_X  : value 0, min 0, max 1439, fuzz 0...
            val xMax = axisMax(block, "ABS_MT_POSITION_X")
            val yMax = axisMax(block, "ABS_MT_POSITION_Y")
            if (xMax <= 0 || yMax <= 0) continue
            candidates.add(Triple(dev, xMax, yMax))
        }
        // 手机触摸屏分辨率最大；按 (xMax*yMax) 降序，最大的排最前（devices.first() 取到真屏）
        candidates.sortByDescending { it.second.toLong() * it.third.toLong() }
        return candidates.joinToString("\n") { "${it.first}|${it.second}|${it.third}" }
    }

    private fun axisMax(block: String, axis: String): Int {
        val line = block.lineSequence().firstOrNull { it.contains(axis) } ?: return 0
        val max = Regex("max\\s+(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return max
    }

    @Volatile
    private var collectorThread: Thread? = null

    @Volatile
    private var collectorProcess: Process? = null

    @Volatile
    private var rawTouchCallback: IRawTouchCallback? = null

    /** 用户态通过 attachRawTouchCallback 注册；本进程无 Context，回调靠 binder 反调 */
    override fun attachRawTouchCallback(cb: IRawTouchCallback?) {
        rawTouchCallback = cb
    }

    override fun startRawTouch(device: String): Int {
        val cb = rawTouchCallback ?: return ERR_FAILED
        if (collectorThread != null || nativeCollecting) return OK // 已在跑，幂等
        // 优先 native 直读（零子进程、低延迟）；.so 未编译/打开失败时退回 getevent 子进程
        if (tryStartNativeCollect(device, cb)) {
            return OK
        }
        return try {
            val proc = Runtime.getRuntime().exec(
                arrayOf("/system/bin/sh", "-c", "getevent -lt $device"),
            )
            collectorProcess = proc
            val thread = Thread({ pumpEvents(proc, cb) }, "raw-touch-pump").apply {
                isDaemon = true
                start()
            }
            collectorThread = thread
            OK
        } catch (t: Throwable) {
            Log.d(TAG, "startRawTouch failed: ${t.javaClass.simpleName}")
            ERR_FAILED
        }
    }

    /** native 直读采集是否在跑（与 collectorThread 互斥） */
    @Volatile
    private var nativeCollecting = false

    /** 尝试走 native 直读采集。成功注入 emitter 并启动采集线程，返回 true；否则 false 由调用方降级 */
    private fun tryStartNativeCollect(device: String, cb: IRawTouchCallback): Boolean {
        if (!NativeInputProbe.isNativeAvailable()) {
            Log.d(TAG, "native collect unavailable (.so 未编译), fallback getevent")
            return false
        }
        NativeInputProbe.setEmitter { type, x, y, slot ->
            try { cb.onRawTouch(type, x, y, slot) } catch (_: Throwable) {}
        }
        val rc = NativeInputProbe.startCollect(device)
        if (rc != 0) {
            Log.d(TAG, "native collect start failed rc=$rc, fallback getevent")
            NativeInputProbe.setEmitter(null)
            return false
        }
        nativeCollecting = true
        Log.w(TAG, "raw-hotspot: active via native direct read $device (no subprocess)")
        return true
    }

    override fun stopRawTouch() {
        // 停 native 采集
        if (nativeCollecting) {
            NativeInputProbe.stopCollect()
            NativeInputProbe.setEmitter(null)
            nativeCollecting = false
        }
        // 停 getevent 子进程（幂等）
        runCatching { collectorProcess?.destroy() }
        collectorProcess = null
        collectorThread = null
    }

    /**
     * 逐行解析 getevent -lt 输出，提取 ABS_MT_POSITION_X/Y、ABS_MT_SLOT、
     * ABS_MT_TRACKING_ID、BTN_TOUCH，合成「DOWN/MOVE/UP」三级事件回传。
     *
     * getevent -lt 行样例：
     *   [  1234.567890] /dev/input/eventX: 0003 0035 000005a0   (EV_ABS, ABS_MT_POSITION_X)
     *   [  1234.567890] /dev/input/eventX: 0001 014a 00000001   (EV_KEY, BTN_TOUCH, down)
     *
     * 这里只在采集线程里做轻量解析，绝不在 binder 线程上阻塞；坐标是 raw 值，
     * 归一化与方向映射交给用户态的 HotspotHitRules。
     */
    private fun pumpEvents(proc: Process, cb: IRawTouchCallback) {
        var curX = 0f
        var curY = 0f
        var curSlot = 0
        var tracking = false
        val reader = proc.inputStream.bufferedReader()
        try {
            reader.forEachLine { line ->
                val ev = parseEventLine(line) ?: return@forEachLine
                when {
                    ev.code == CODE_MT_SLOT -> curSlot = ev.value
                    ev.code == CODE_MT_POS_X -> {
                        curX = ev.value.toFloat()
                        if (tracking) emit(cb, TYPE_MOVE, curX, curY, curSlot)
                    }
                    ev.code == CODE_MT_POS_Y -> {
                        curY = ev.value.toFloat()
                        if (tracking) emit(cb, TYPE_MOVE, curX, curY, curSlot)
                    }
                    ev.code == CODE_MT_TRACKING_ID -> {
                        if (ev.value >= 0) {
                            tracking = true
                            emit(cb, TYPE_DOWN, curX, curY, curSlot)
                        } else {
                            emit(cb, TYPE_UP, curX, curY, curSlot)
                            tracking = false
                        }
                    }
                    ev.code == CODE_BTN_TOUCH -> {
                        if (ev.value == 1) {
                            tracking = true
                            emit(cb, TYPE_DOWN, curX, curY, curSlot)
                        } else {
                            emit(cb, TYPE_UP, curX, curY, curSlot)
                            tracking = false
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.d(TAG, "raw touch pump exit: ${t.javaClass.simpleName}")
            try { cb.onCollectorState(STATE_THREAD_EXIT, t.javaClass.simpleName) } catch (_: Throwable) {}
        }
    }

    /** getevent -lt 单行 → (type, code, value)。非 EV_ABS/EV_KEY 行或无法解析返回 null */
    private fun parseEventLine(line: String): Ev? {
        // 形如：... eventX: 0003 0035 000005a0
        val m = Regex(":\\s*([0-9a-fA-F]{4})\\s+([0-9a-fA-F]{4})\\s+(-?[0-9a-fA-F]+)").find(line)
            ?: return null
        val type = m.groupValues[1].toInt(16)
        val code = m.groupValues[2].toInt(16)
        val value = m.groupValues[3].toLong(16)
        if (type != 0x03 && type != 0x01) return null
        return Ev(type, code, value.toInt())
    }

    private fun emit(cb: IRawTouchCallback, type: Int, x: Float, y: Float, slot: Int) {
        try { cb.onRawTouch(type, x, y, slot) } catch (_: Throwable) {}
    }

    private data class Ev(val type: Int, val code: Int, val value: Int)

    override fun destroy() {
        Log.d(TAG, "destroy requested")
        kotlin.system.exitProcess(0)
    }

    // -----------------------------------------------------------------------
    // InputManager.monitorInput 最小可行性验证（shell 身份，只探测不改业务）
    // -----------------------------------------------------------------------

    /** 反射调用 InputManager.monitorInput，结果字符串回传主进程打日志 */
    override fun startInputMonitorProbe(): String {
        return try {
            val legacy = InputMonitorProbe.startProbe()
            // 53 号：Android 17 移除了 InputManager.getInstance，旧探测必然 NO_SUCH_METHOD。
            // 此时补一次 binder 路径的 Spy 探测，否则「Spy 不可用」是探针造成的假阴性。
            if (legacy.contains("NO_SUCH_METHOD") && legacy.contains("getInstance")) {
                legacy + InputMonitorProbe.probeSpy() + "\n"
            } else {
                legacy
            }
        } catch (t: Throwable) {
            Log.d(TAG, "input monitor probe threw ${t.javaClass.simpleName}: ${t.message}")
            "RESULT=OTHER ${t.javaClass.name}: ${t.message}"
        }
    }

    override fun stopInputMonitorProbe() {
        runCatching { InputMonitorProbe.stop() }
    }

    // -----------------------------------------------------------------------
    // 全局输入旁路监听（monitorInput 方案，不读 /dev/input，正式业务路径）
    // -----------------------------------------------------------------------

    /** monitorInput 旁路监听器（单例，startMonitorTouch 时创建） */
    @Volatile
    private var monitorTouch: MonitorInputTouchMonitor? = null

    /**
     * 启动全局输入旁路监听：在 shell 进程里跑 [MonitorInputTouchMonitor]，
     * 收到 MotionEvent 后通过 [rawTouchCallback] 的 onMotionEvent 回传。
     *
     * 与 getevent 旁路的区别：不读 /dev/input/event*，坐标由 framework 直接给出
     * 屏幕逻辑坐标，免去归一化与方向旋转，且不受 shell 读 /dev/input 权限收紧影响。
     */
    override fun startMonitorTouch(): Int {
        val cb = rawTouchCallback ?: return ERR_FAILED
        if (monitorTouch?.isActive() == true) return OK // 已在跑，幂等
        return try {
            val monitor = MonitorInputTouchMonitor(
                TouchMonitor.Listener { action, x, y, eventTime ->
                    try { cb.onMotionEvent(action, x, y, eventTime) } catch (_: Throwable) {}
                },
            )
            monitor.start()
            monitorTouch = monitor
            Log.w(TAG, "startMonitorTouch: active")
            OK
        } catch (e: TouchMonitorUnavailableException) {
            // 显式报错：monitorInput 不可用，回传原因供主进程降级，绝不静默。
            // 注意：这里绝不能发 STATE_THREAD_EXIT —— monitorInput「从未启动成功」不等于「采集线程退出」。
            // 发该信号会把主进程 running 误置 false，随后 getevent 成功的 onReady 又会经 start()
            // 把 running 拉回 true 并重新 setup()，形成 12ms/轮的无限重试死循环（11-19 事故根因）。
            Log.e(TAG, "startMonitorTouch unavailable at ${e.stage}: ${e.message}")
            ERR_FAILED
        } catch (t: Throwable) {
            Log.e(TAG, "startMonitorTouch failed ${t.javaClass.simpleName}: ${t.message}")
            ERR_FAILED
        }
    }

    override fun stopMonitorTouch() {
        runCatching { monitorTouch?.stop() }
        monitorTouch = null
    }

    // -----------------------------------------------------------------------
    // 系统性触摸感知通道探测（探测项 1 + 2，纯诊断，不改业务）
    // -----------------------------------------------------------------------

    /**
     * 依次执行：
     *   1) NativeInputProbe —— native 直读 /dev/input/event*（未编译 .so 时标注 NOT_COMPILED）；
     *   2) InputManagerProbe —— 反射逐一探测 monitorGestureInput / monitorInput(3参) / getInputMonitor。
     * 另附一段 getevent -lp 与 /dev/input 权限的现状，供对照 SELinux/组权限。
     */
    override fun probeAllTouchChannels(): String {
        val sb = StringBuilder()
        sb.append("===== probeAllTouchChannels (uid 2000 shell) =====\n")
        sb.append("时间=").append(System.currentTimeMillis()).append('\n')
        sb.append('\n')
        // 落盘标记：荣耀 Android 17 过滤第三方应用 logcat，探测结果改走文件通道。
        // shell uid 2000 可写 /data/local/tmp；start 标记用于让调用方（adb）知道自测窗口已开始。
        runCatching { java.io.File("/data/local/tmp/wheelpilot_probe_start.txt")
            .writeText("started @ ${System.currentTimeMillis()}\n") }

        // 1) Native 直读
        sb.append("【探测项 1】Native 直读 /dev/input\n")
        sb.append(NativeInputProbe.probe()).append('\n')
        sb.append('\n')

        // 1.5) Native 采集自测：捕获首个 DOWN 即停（最长 15 秒），验证 native read→解析→JNI→Java 全链路。
        //      触摸一下即抓到坐标并停止；15 秒内未触摸则返回 0 计数（仍能证明链路不崩）。
        sb.append("【自测】Native 采集（捕获首个 DOWN 即停，最长 15 秒，请触摸屏幕）\n")
        val touchDev = probeTouchDevices().lineSequence().firstOrNull()?.substringBefore('|')
        if (touchDev == null) {
            sb.append("RESULT=NO_TOUCH_DEVICE（getevent -lp 未识别到触摸屏，跳过自测）\n")
        } else {
            sb.append(NativeInputProbe.selfTestCollect(touchDev, 15000)).append('\n')
        }
        sb.append('\n')

        // 2) 反射探测
        sb.append("【探测项 2】反射 InputManager 隐藏方法\n")
        sb.append(InputManagerProbe.runAll()).append('\n')
        sb.append('\n')

        // 附：getevent 现状对照（与 native 同源，帮助定位 SELinux/组权限）
        sb.append("【对照】getevent -lp 与 /dev/input 权限\n")
        runCatching { sb.append(probeTouchDiagnostics()).append('\n') }
        sb.append("===== probeAllTouchChannels end =====\n")

        // 完整结果落盘（供 adb cat 读取）
        runCatching { java.io.File("/data/local/tmp/wheelpilot_probe.txt").writeText(sb.toString()) }
        return sb.toString()
    }

    // -----------------------------------------------------------------------
    // binder 与反射细节
    // -----------------------------------------------------------------------

    /** 每次取用前验活：system_server 重启后旧 binder 是 dead object，不验会一直假死 */
    private fun requireAtm(): Any {
        val cached = atm
        if (cached != null && isAlive(cached)) return cached
        val fresh = service() ?: throw IllegalStateException("activity_task unavailable")
        atm = fresh
        return fresh
    }

    private fun isAlive(svc: Any): Boolean = runCatching {
        val binder = svc.javaClass.getMethod("asBinder").invoke(svc) as? IBinder
        binder?.isBinderAlive ?: true
    }.getOrDefault(true)

    /** ATMS.getService() 优先；部分 ROM 改了签名，退回 ServiceManager + Stub.asInterface */
    private fun service(): Any? {
        runCatching {
            val m = Class.forName(ATM).getDeclaredMethod("getService")
            m.isAccessible = true
            m.invoke(null)?.let { return it }
        }
        return runCatching {
            val sm = Class.forName("android.os.ServiceManager")
            val get = sm.getDeclaredMethod("getService", String::class.java)
            get.isAccessible = true
            val binder = get.invoke(null, "activity_task") ?: return null
            val stub = Class.forName("$IFACE\$Stub")
            val asInterface = stub.getDeclaredMethod("asInterface", Class.forName("android.os.IBinder"))
            asInterface.isAccessible = true
            asInterface.invoke(null, binder)
        }.getOrNull()
    }

    /** resizeTask 有 3 参 / 4 参两种重载，优先 3 参 */
    private fun findResizeMethod(rectCls: Class<*>): Method? {
        val exact = Class.forName(IFACE).methods.firstOrNull { m ->
            m.name == "resizeTask" && m.parameterTypes.size == 3 &&
                m.parameterTypes[0] == Int::class.javaPrimitiveType &&
                m.parameterTypes[1].isAssignableFrom(rectCls) &&
                m.parameterTypes[2] == Int::class.javaPrimitiveType
        }
        if (exact != null) return exact
        return Class.forName(IFACE).methods.firstOrNull { m ->
            m.name == "resizeTask" && m.parameterTypes.size > 3 &&
                m.parameterTypes[0] == Int::class.javaPrimitiveType &&
                m.parameterTypes[1].isAssignableFrom(rectCls)
        }
    }

    /**
     * startActivityAsUser 的参数表随 API 版本变化（Android 14 加 callingFeatureId、
     * 15 又调整了尾部），所以按「名字 + 参数个数下限」找，具体槽位在调用处按类型填。
     */
    private fun findStartAsUser(): Method? {
        val candidates = Class.forName(IFACE).methods.filter {
            it.name == "startActivityAsUser" && it.parameterTypes.size >= 10
        }
        // 参数最多的那个 = 最新的完整签名；老签名是它的子集
        return candidates.maxByOrNull { it.parameterTypes.size }
    }

    private fun isXiaomi(): Boolean = runCatching {
        val sp = Class.forName("android.os.SystemProperties")
        val get = sp.getMethod("get", String::class.java)
        val brand = (get.invoke(null, "ro.product.brand") as? String).orEmpty()
        brand.contains("xiaomi", true) || brand.contains("redmi", true) || brand.contains("poco", true)
    }.getOrDefault(false)

    /** 统一异常出口：任何反射失败都转成负数错误码，绝不把异常抛回 binder 客户端 */
    private fun guarded(tag: String, block: () -> Int): Int = try {
        block()
    } catch (t: Throwable) {
        val code = when {
            t is NoSuchMethodException -> ERR_NO_METHOD
            t is ClassNotFoundException -> ERR_NO_SERVICE
            else -> ERR_FAILED
        }
        Log.d(TAG, "$tag failed code=$code ${t.javaClass.simpleName}: ${t.message}")
        code
    }

    /**
     * 落盘诊断日志：荣耀 Android 17 过滤第三方 logcat，且主进程 untrusted_app_34
     * 无法在 /data/local/tmp 创建文件；只有 uid 2000 的 UserService 能写。
     * 用追加模式 + 单一文件，adb shell cat 可读。新会话首行写 BOOT 便于区分新旧日志。
     * 每行限长，防止异常堆栈把文件撑爆。
     */
    override fun writeTrace(line: String): Int = runCatching {
        val target = java.io.File(TRACE_PATH)
        if (!target.exists()) target.appendText("===== BOOT ${System.currentTimeMillis()} =====\n")
        val safe = if (line.length > TRACE_LINE_MAX) line.take(TRACE_LINE_MAX) + "…" else line
        target.appendText(safe.replace('\n', ' ') + "\n")
        OK
    }.getOrDefault(ERR_FAILED)

    /**
     * 58 号：以 shell 身份读剪贴板文本。
     *
     * 路径：`ServiceManager.getService("clipboard")` → `IClipboard$Stub.asInterface`
     * → `getPrimaryClip(...)`。三者都是隐藏 API，走反射直连——本进程是 uid 2000 的
     * app_process，不受 app 侧隐藏 API 黑名单策略约束（同进程里 ActivityTaskManager
     * 的反射调用一直在用，方法学一致）。
     *
     * 参数按类型回填而不是按签名写死：AOSP 各版本从 `getPrimaryClip(pkg, tag, userId)`
     * 演变到带 `deviceId` 的重载，按类型填可以同时兼容而不用维护版本表。
     */
    override fun readClipboardText(): String {
        val binder = runCatching {
            Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, "clipboard") as? android.os.IBinder
        }.getOrNull() ?: return "ERR:no_service"

        val iface = runCatching {
            Class.forName("android.content.IClipboard\$Stub")
                .getMethod("asInterface", android.os.IBinder::class.java)
                .invoke(null, binder)
        }.getOrNull() ?: return "ERR:no_iface"

        val methods = runCatching {
            iface.javaClass.methods.filter { it.name == "getPrimaryClip" }
        }.getOrDefault(emptyList())
        if (methods.isEmpty()) return "ERR:no_method"

        var sawNull = false
        val errs = StringBuilder()
        for (m in methods) {
            // 显式 Array<Any?>：让 when 分支（String/Int/Boolean/null）落到同一类型，
            // 否则 Kotlin 会推成 Comparable&Serializable 交集，报 TYPE_INTERSECTION_AS_REIFIED_ERROR
            val args: Array<Any?> = arrayOfNulls(m.parameterTypes.size)
            m.parameterTypes.forEachIndexed { idx, t ->
                args[idx] = when {
                    t == String::class.java -> if (idx == 0) "com.android.shell" else null
                    t == Int::class.javaPrimitiveType -> 0   // userId / deviceId
                    t == Boolean::class.javaPrimitiveType -> false
                    else -> null
                }
            }
            val outcome = runCatching {
                m.isAccessible = true
                m.invoke(iface, *args)
            }
            if (outcome.isSuccess) {
                val clip = outcome.getOrNull()
                // null = 剪贴板本来就是空的（不是错误，必须与「读失败」区分开）
                if (clip == null) sawNull = true else return extractClipText(clip)
            } else {
                val e = outcome.exceptionOrNull()
                errs.append(signatureOf(m)).append('=')
                    .append(e?.javaClass?.simpleName ?: "?").append(':')
                    .append((e?.message ?: "").take(60)).append(" | ")
            }
        }
        // 有任一重载「成功返回 null」即视为空剪贴板；全部抛异常才判失败并带上原因
        return if (sawNull) "" else "ERR:" + errs.toString().take(240)
    }

    /** `getPrimaryClip(String,String,int)` 形式的短签名，供失败诊断定位到具体重载 */
    private fun signatureOf(m: java.lang.reflect.Method): String =
        m.name + "(" + m.parameterTypes.joinToString(",") { it.simpleName } + ")"

    /** ClipData → 文本；非文本返回 `URI:<uri>`；空返回空串 */
    private fun extractClipText(clip: Any): String {
        val cls = clip.javaClass
        val count = runCatching {
            cls.getMethod("getItemCount").invoke(clip) as Int
        }.getOrDefault(0)
        if (count <= 0) return ""
        val item = runCatching {
            cls.getMethod("getItemAt", Int::class.javaPrimitiveType).invoke(clip, 0)
        }.getOrNull() ?: return ""
        val ics = item.javaClass
        runCatching { ics.getMethod("getText").invoke(item) as? CharSequence }
            .getOrNull()?.let { return it.toString() }
        runCatching { ics.getMethod("getUri").invoke(item) as? android.net.Uri }
            .getOrNull()?.let { return "URI:$it" }
        return ""
    }

    companion object {
        private const val TAG = "WheelPilot"
        private const val ATM = "android.app.ActivityTaskManager"
        private const val IFACE = "android.app.IActivityTaskManager"
        private const val RECT = "android.graphics.Rect"

        const val KEY_WINDOWING_MODE = "android.activity.windowingMode"
        const val KEY_LAUNCH_BOUNDS = "android:activity.launchBounds"
        const val KEY_BAL_MODE = "android.activity.pendingIntentBackgroundActivityStartMode"
        const val KEY_LAUNCH_STACK_ID = "android.activity.launchStackId"

        const val OK = 0
        const val ERR_FAILED = -1
        const val ERR_NO_METHOD = -2
        const val ERR_NO_SERVICE = -3

        // 诊断落盘路径（uid 2000 可写 /data/local/tmp；主进程不可写）
        const val TRACE_PATH = "/data/local/tmp/wheelpilot_trace.log"
        private const val TRACE_LINE_MAX = 400

        // getevent 事件码（EV_ABS 0x03 / EV_KEY 0x01 下的 code）
        private const val CODE_MT_SLOT = 0x2f
        private const val CODE_MT_POS_X = 0x35
        private const val CODE_MT_POS_Y = 0x36
        private const val CODE_MT_TRACKING_ID = 0x39
        private const val CODE_BTN_TOUCH = 0x14a

        // 回传触点类型
        const val TYPE_DOWN = 0
        const val TYPE_MOVE = 1
        const val TYPE_UP = 2

        // onCollectorState 状态
        const val STATE_THREAD_EXIT = 1
    }
}
