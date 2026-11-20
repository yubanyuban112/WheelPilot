package com.pilot.wheel

import android.hardware.input.InputManager
import android.os.Looper
import android.util.Log
import android.view.InputChannel
import android.view.InputMonitor
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * 最小可行性验证：在 Shizuku UserService（shell 身份，uid 2000）进程里，
 * 通过反射调用隐藏 API `InputManager.monitorInput(String, int)`，
 * 验证能否以全局输入监听（InputMonitor）的方式收到 MotionEvent。
 *
 * 背景：热区窗口用 FLAG_NOT_TOUCHABLE 后无法再自行接收触摸，getevent 读 /dev/input
 * 又被 SELinux / 组权限收紧。本类只验证「monitorInput 是否可用」，不改任何业务逻辑。
 *
 * 编译期：InputMonitor / InputChannel / InputEventReceiver 由 src/main/java/android/view/
 * 下的桩类提供签名（运行时由真机 framework 真实类顶替）；InputManager 是公开类可直接
 * import，只有 monitorInput 走反射。
 *
 * 事件消费：InputMonitor.getInputChannel() → InputEventReceiverBridge 子类收事件，
 * onInputEvent 里反射解析 MotionEvent 坐标并打印，finishInputEvent(event,false)
 * 表示未消费（纯旁路监听，不拦截下层点击）。
 *
 * 结果以纯文本返回（跨进程回传给主进程打印）。
 */
@androidx.annotation.Keep
object InputMonitorProbe {

    private const val TAG = "WheelPilot"

    @Volatile
    private var activeMonitor: InputMonitor? = null

    @Volatile
    private var activeReceiver: com.pilot.wheel.InputEventReceiverBridge? = null

    @Volatile
    private var probeLooper: Looper? = null

    @Volatile
    private var loopThread: Thread? = null

    /** monitorInput 反射方法缓存 */
    @Volatile
    private var monitorMethod: Method? = null

    /**
     * 启动验证。返回结构化结果字符串，供主进程打日志。
     * 不抛异常 —— 所有失败都转成可读文本并标注失败层级。
     */
    fun startProbe(): String {
        val sb = StringBuilder()
        sb.append("== InputMonitorProbe start (uid=").append(currentUid()).append(") ==\n")
        try {
            // 1) InputManager 单例（getInstance 是 @hide，反射取）
            val im = getInputManagerInstance()
            sb.append("InputManager.getInstance() ok\n")

            // 2) 定位 monitorInput 重载，优先返回 InputMonitor 的 (String, int)
            val method = monitorMethod ?: findMonitorMethod(im).also { monitorMethod = it }
            if (method == null) {
                sb.append("RESULT=NO_SUCH_METHOD (InputManager 上无 monitorInput)\n")
                return sb.toString()
            }
            sb.append("monitorInput(")
                .append(method.parameterTypes.joinToString(",") { it.simpleName })
                .append(") -> ").append(method.returnType.simpleName).append('\n')

            // 3) 反射调用
            val monitor = invokeMonitor(im, method, sb) ?: return sb.toString()
            activeMonitor = monitor
            sb.append("InputMonitor obtained\n")

            // 4) 取 channel
            val channel: InputChannel = try {
                monitor.inputChannel
            } catch (t: Throwable) {
                sb.append("RESULT=GET_CHANNEL_FAILED ").append(t.javaClass.name)
                    .append(": ").append(t.message).append('\n')
                return sb.toString()
            }
            sb.append("InputMonitor.getInputChannel() ok: ").append(channel).append('\n')

            // 5) 起 Looper 线程，用 InputEventReceiver 子类收事件
            val t = Thread({ runLoop(channel) }, "input-monitor-loop")
            t.isDaemon = true
            loopThread = t
            t.start()

            sb.append("RESULT=STARTED (receiver running, touch the screen now)\n")
        } catch (e: ClassNotFoundException) {
            sb.append("RESULT=CLASS_NOT_FOUND ").append(e.message).append('\n')
        } catch (e: NoSuchMethodException) {
            sb.append("RESULT=NO_SUCH_METHOD ").append(e.message).append('\n')
        } catch (e: SecurityException) {
            sb.append("RESULT=SECURITY_EXCEPTION ").append(e.message).append('\n')
        } catch (e: InvocationTargetException) {
            val cause = e.cause
            sb.append("RESULT=INVOCATION_TARGET cause=")
                .append(cause?.javaClass?.name).append(": ").append(cause?.message).append('\n')
        } catch (t: Throwable) {
            sb.append("RESULT=OTHER ").append(t.javaClass.name).append(": ").append(t.message).append('\n')
        }
        return sb.toString()
    }

    /** 反射取 InputManager.getInstance()（@hide 方法） */
    private fun getInputManagerInstance(): InputManager {
        val m = InputManager::class.java.getDeclaredMethod("getInstance")
        m.isAccessible = true
        return m.invoke(null) as InputManager
    }

    /**
     * 53 号 · 第 9 项：Spy（monitorGestureInput）可行性探测。
     *
     * 为什么不能复用 [startProbe]：它靠 `InputManager.getInstance()` 取单例，
     * 而 Android 17 已移除该隐藏方法（与 monitorInput 失效同源），
     * 于是每次探测都得到 `NO_SUCH_METHOD getInstance` —— 这是**探针自身**的假阴性，
     * 不能据此断言 Spy 通道不可用。
     *
     * 这里改走 binder：ServiceManager 取 "input" → IInputManager$Stub.asInterface →
     * 直接调 `monitorGestureInput(token=null, name, displayId=0)`。
     * 只创建 InputMonitor 并取一次 InputChannel 即销毁（不注册 receiver、不消费事件），
     * 因此不会影响业务通道，也不会改变任何窗口行为。
     *
     * 返回值：`SPY_RESULT=OK ...` 才说明本机可创建；其余均为不可用及阶段原因。
     */
    fun probeSpy(): String {
        return try {
            val sm = Class.forName("android.os.ServiceManager")
            val getService = sm.getDeclaredMethod("getService", String::class.java)
            getService.isAccessible = true
            val binder = getService.invoke(null, "input") as? android.os.IBinder
                ?: return "SPY_RESULT=NO_SERVICE"
            val stub = Class.forName("android.hardware.input.IInputManager\$Stub")
            val asInterface = stub.getDeclaredMethod("asInterface", android.os.IBinder::class.java)
            asInterface.isAccessible = true
            val proxy = asInterface.invoke(null, binder) ?: return "SPY_RESULT=NO_PROXY"
            val method = proxy.javaClass.methods.firstOrNull { it.name == "monitorGestureInput" }
                ?: return "SPY_RESULT=NO_METHOD"
            method.isAccessible = true
            val callArgs = arrayOfNulls<Any>(method.parameterTypes.size)
            for (i in method.parameterTypes.indices) {
                val p = method.parameterTypes[i]
                callArgs[i] = when {
                    p == String::class.java -> "pilot-spy-probe"
                    p == Int::class.javaPrimitiveType -> 0
                    else -> null
                }
            }
            val monitor = method.invoke(proxy, *callArgs) as? InputMonitor
                ?: return "SPY_RESULT=NULL_MONITOR params=${method.parameterCount}"
            val channel = runCatching { monitor.inputChannel }.getOrNull()
            runCatching { channel?.dispose() }
            runCatching { monitor.dispose() }
            if (channel != null) "SPY_RESULT=OK params=${method.parameterCount}" else "SPY_RESULT=NO_CHANNEL"
        } catch (t: Throwable) {
            // 反射调用失败时真正原因在 cause 里：要把异常类打出来，
            // 才能区分「接口不存在」「无权限（SecurityException）」「服务端实现拒绝」
            val root = if (t is java.lang.reflect.InvocationTargetException) (t.cause ?: t) else t
            "SPY_RESULT=FAIL ${root.javaClass.name}: ${root.message}"
        }
    }

    /** 当前进程 uid（Process.myUid 是 @hide，反射取） */
    private fun currentUid(): Int = try {
        val m = Class.forName("android.os.Process").getDeclaredMethod("myUid")
        m.isAccessible = true
        m.invoke(null) as Int
    } catch (t: Throwable) {
        -1
    }

    /** 在 InputManager 上找 monitorInput，优先返回 InputMonitor 的重载 */
    private fun findMonitorMethod(im: InputManager): Method? {
        val candidates = im.javaClass.methods
            .filter { it.name == "monitorInput" }
            .sortedBy { it.parameterTypes.size }
        if (candidates.isEmpty()) return null
        // 优先返回 InputMonitor 的
        return candidates.firstOrNull { it.returnType.name == "android.view.InputMonitor" }
            ?: candidates.first()
    }

    /** 反射调用 monitorInput，按参数表填 name + displayId(0) */
    private fun invokeMonitor(im: InputManager, method: Method, sb: StringBuilder): InputMonitor? {
        val ps = method.parameterTypes
        val args = arrayOfNulls<Any>(ps.size)
        var strSeen = false
        for (i in ps.indices) {
            when {
                ps[i] == String::class.java && !strSeen -> {
                    args[i] = "pilot-monitor-probe"
                    strSeen = true
                }
                ps[i] == Int::class.javaPrimitiveType -> args[i] = 0
                else -> args[i] = null
            }
        }
        method.isAccessible = true
        val ret = method.invoke(im, *args)
        if (ret == null) {
            sb.append("RESULT=NULL_RETURN (monitorInput 返回 null)\n")
            return null
        }
        return ret as? InputMonitor ?: run {
            sb.append("RESULT=UNEXPECTED_RETURN (返回类型 ").append(ret.javaClass.name)
                .append("，非 InputMonitor)\n")
            null
        }
    }

    private fun runLoop(channel: InputChannel) {
        Looper.prepare()
        val looper = Looper.myLooper()!!
        probeLooper = looper
        try {
            // 探测用途：打印收到的 MotionEvent（诊断 monitorInput 是否真能收事件）
            val receiver = InputEventReceiverBridge(channel, looper) { action, x, y, _ ->
                Log.w(TAG, "probe: event action=$action x=$x y=$y")
            }
            activeReceiver = receiver
            Log.w(TAG, "probe: InputEventReceiver registered, entering loop")
            Looper.loop()
        } catch (e: SecurityException) {
            Log.w(TAG, "probe: SECURITY_EXCEPTION ${e.message}")
        } catch (e: Throwable) {
            Log.w(TAG, "probe: receiver loop failed ${e.javaClass.name}: ${e.message}")
        }
    }

    fun stop() {
        runCatching { activeReceiver?.dispose() }
        runCatching { activeMonitor?.dispose() }
        activeReceiver = null
        activeMonitor = null
        probeLooper?.quitSafely()
        probeLooper = null
        loopThread = null
        Log.w(TAG, "probe stopped")
    }
}
