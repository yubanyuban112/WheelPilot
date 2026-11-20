package com.pilot.wheel

import android.hardware.input.InputManager
import android.os.Looper
import android.util.Log
import android.view.InputChannel
import android.view.InputMonitor
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * 基于 `InputManager.monitorInput`（隐藏 API）的全局输入监听实现。
 *
 * 跑在 Shizuku UserService（shell 身份，uid 2000）进程里：monitorInput 是 system
 * 级隐藏接口，普通应用进程无权调用，shell 身份才可能通过反射拿到全局 InputMonitor，
 * 从而收到所有触摸 MotionEvent —— 这是「热区窗口 FLAG_NOT_TOUCHABLE 后仍能感知触摸」
 * 的旁路，且**不读 /dev/input/event\***、不消费任何事件。
 *
 * 失败策略：monitorInput 在个别 ROM 上可能被裁剪 / 收紧权限，这里**任何一步失败
 * 都显式抛出 [TouchMonitorUnavailableException]**（含失败阶段与原因），绝不静默。
 *
 * 事件流：InputManager.monitorInput → InputMonitor.getInputChannel() →
 * InputEventReceiverBridge（在专用 Looper 线程收事件）→ [TouchMonitor.onTouch]。
 * finishInputEvent(false) 表示未消费，事件照常派发给下层应用。
 */
@androidx.annotation.Keep
class MonitorInputTouchMonitor(
    private val listener: TouchMonitor.Listener,
    private val monitorName: String = "pilot-edge-monitor",
) : TouchMonitor {

    companion object {
        private const val TAG = "WheelPilot"
    }

    @Volatile
    private var activeMonitor: InputMonitor? = null

    @Volatile
    private var activeReceiver: InputEventReceiverBridge? = null

    @Volatile
    private var loopThread: Thread? = null

    @Volatile
    private var loopLooper: Looper? = null

    /** monitorInput 反射方法缓存 */
    @Volatile
    private var monitorMethod: Method? = null

    @Volatile
    private var started = false

    override fun start() {
        if (started) return
        try {
            val im = inputManagerInstance()
                ?: throw TouchMonitorUnavailableException("getInstance", "InputManager.getInstance() 返回 null")
            val method = monitorMethod ?: findMonitorMethod(im).also { monitorMethod = it }
                ?: throw TouchMonitorUnavailableException("findMethod", "InputManager 上无 monitorInput 方法")
            val monitor = invokeMonitor(im, method)
                ?: throw TouchMonitorUnavailableException("invoke", "monitorInput 返回 null 或非 InputMonitor")
            val channel: InputChannel = try {
                monitor.inputChannel
            } catch (t: Throwable) {
                throw TouchMonitorUnavailableException("getChannel", "${t.javaClass.name}: ${t.message}", t)
            }
            activeMonitor = monitor

            val t = Thread({
                Looper.prepare()
                val looper = Looper.myLooper()
                    ?: throw TouchMonitorUnavailableException("looper", "Looper.myLooper() 为 null")
                loopLooper = looper
                try {
                    val receiver = InputEventReceiverBridge(channel, looper) { action, x, y, et ->
                        listener.onTouch(action, x, y, et)
                    }
                    activeReceiver = receiver
                    Log.w(TAG, "monitorInput active (name=$monitorName), listening…")
                    Looper.loop()
                } catch (e: SecurityException) {
                    Log.e(TAG, "monitorInput receiver SECURITY_EXCEPTION: ${e.message}")
                } catch (t2: Throwable) {
                    Log.e(TAG, "monitorInput receiver loop failed ${t2.javaClass.name}: ${t2.message}")
                }
            }, "input-monitor-loop").apply { isDaemon = true }
            loopThread = t
            t.start()

            started = true
        } catch (e: TouchMonitorUnavailableException) {
            Log.e(TAG, "monitorInput unavailable: $e")
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "monitorInput start failed ${e.javaClass.name}: ${e.message}")
            throw TouchMonitorUnavailableException("start", "${e.javaClass.name}: ${e.message}", e)
        }
    }

    override fun stop() {
        runCatching { activeReceiver?.dispose() }
        runCatching { activeMonitor?.dispose() }
        activeReceiver = null
        activeMonitor = null
        loopLooper?.quitSafely()
        loopLooper = null
        loopThread = null
        started = false
        Log.w(TAG, "monitorInput stopped")
    }

    override fun isActive(): Boolean = started && activeReceiver != null

    /** 反射取 InputManager.getInstance()（@hide 方法） */
    private fun inputManagerInstance(): InputManager? = try {
        val m = InputManager::class.java.getDeclaredMethod("getInstance")
        m.isAccessible = true
        m.invoke(null) as? InputManager
    } catch (t: Throwable) {
        throw TouchMonitorUnavailableException("getInstance", "${t.javaClass.name}: ${t.message}", t)
    }

    /** 在 InputManager 上找 monitorInput，优先返回 InputMonitor 的重载 */
    private fun findMonitorMethod(im: InputManager): Method? {
        val candidates = im.javaClass.methods
            .filter { it.name == "monitorInput" }
            .sortedBy { it.parameterTypes.size }
        if (candidates.isEmpty()) return null
        return candidates.firstOrNull { it.returnType.name == "android.view.InputMonitor" }
            ?: candidates.first()
    }

    /** 反射调用 monitorInput，按参数表填 name + displayId(0) */
    private fun invokeMonitor(im: InputManager, method: Method): InputMonitor? {
        val ps = method.parameterTypes
        val args = arrayOfNulls<Any>(ps.size)
        var strSeen = false
        for (i in ps.indices) {
            when {
                ps[i] == String::class.java && !strSeen -> {
                    args[i] = monitorName
                    strSeen = true
                }
                ps[i] == Int::class.javaPrimitiveType -> args[i] = 0
                else -> args[i] = null
            }
        }
        try {
            method.isAccessible = true
            val ret = method.invoke(im, *args) ?: return null
            return ret as? InputMonitor
        } catch (e: InvocationTargetException) {
            val cause = e.cause
            throw TouchMonitorUnavailableException(
                "invoke", "${cause?.javaClass?.name}: ${cause?.message}", cause,
            )
        }
    }
}
