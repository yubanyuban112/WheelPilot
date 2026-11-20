package com.pilot.wheel

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.pilot.wheel.diag.TouchStats

/**
 * 注入调度器（53 号 · 维度二 · 第 4 项）。
 *
 * 为什么必须有专属线程（三条理由，与「handler=null 会卡死链路」的旧说法不同）：
 *  1. **回调投递隔离**：`handler=null` 时 `onCompleted` 投递到无障碍服务主线程，
 *     与 Compose 重组 / 窗口 add-remove / 无障碍事件共用一条消息队列。高刷下主线程
 *     被 MOVE 洪流占据时，完成回调迟到 → 门控误判超时 → 后续穿透被丢弃。
 *  2. **门控竞态**：并发入口（tap / swipe / 双指识屏）必须共用**一个**状态机，
 *     否则各自持布尔标志必然出现「另一个 in flight」误丢。
 *  3. **超时兜底**：`dispatchGesture` 在个别 ROM 上既不回调 completed 也不回调
 *     cancelled，必须有强制释放，否则窗口 flags 永久停在穿透态。
 *
 * 系统侧手势派发线程不受本 Handler 影响 —— 这里只解决回调与门控，不顺带承诺别的。
 *
 * 线程归属：所有状态读写都在 `inject-dispatch` 线程（`state` 另加 @Volatile 供快照读）。
 * 回调约束：`done` 在 **inject-dispatch 线程**执行，调用方若需主线程须自行 `main.post`；
 *          `done` 内禁止同步 I/O、禁止 `Thread.sleep`、禁止 `runBlocking`。
 */
class InjectDispatcher(private val service: AccessibilityService) {

    private companion object {
        const val TAG = "WheelPilot"

        /** 单次注入的硬上限：GestureDescription 最长 60s，业务侧取 1.2s 足够覆盖 swipe */
        const val TIMEOUT_MS = 1200L

        /** 相邻两次注入的冷却：给 WMS 一点时间回收 input channel */
        const val COOL_MS = 40L
    }

    private enum class State { IDLE, IN_FLIGHT, COOLING }

    private val thread = HandlerThread("inject-dispatch").apply { start() }
    private val handler = Handler(thread.looper)

    @Volatile
    private var state = State.IDLE

    /**
     * 提交一次注入。
     *
     * @param label 日志标签（tap / swipe / two-finger）
     * @param build 在 inject-dispatch 线程构造手势；返回 null 表示放弃，不计数
     * @param done  完成/取消/超时/拒绝后的统一回收（在 inject-dispatch 线程执行）
     * @return false 仅表示「能力缺失，直接不可用」；true 表示已入队（是否真的注入看日志）
     */
    fun submit(label: String, build: () -> GestureDescription?, done: () -> Unit): Boolean {
        val caps = runCatching { service.serviceInfo.capabilities }.getOrDefault(0)
        if (caps and AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES == 0) {
            TouchStats.replayIncapable++
            Log.w(TAG, "inject $label INCAPABLE caps=$caps")
            PilotShellClient.trace("INJECT", "$label INCAPABLE caps=$caps")
            return false
        }
        handler.post { run(label, build, done) }
        return true
    }

    private fun run(label: String, build: () -> GestureDescription?, done: () -> Unit) {
        if (state != State.IDLE) {
            // 有界队列 = 1：第二次请求直接丢弃并计数，不排队堆积（堆积只会让手感更差）
            TouchStats.replayDropped++
            PilotShellClient.trace("INJECT", "$label DROPPED state=$state")
            return
        }
        val gesture = runCatching { build() }.getOrNull() ?: return
        state = State.IN_FLIGHT
        TouchStats.injectThreadState = "IN_FLIGHT"
        TouchStats.replayAttempt++

        var settled = false
        val timeout = Runnable {
            if (!settled) {
                settled = true
                TouchStats.replayTimeout++
                Log.w(TAG, "inject $label TIMEOUT after ${TIMEOUT_MS}ms")
                PilotShellClient.trace("INJECT", "$label TIMEOUT waited=${TIMEOUT_MS}ms")
                release(done)
            }
        }
        handler.postDelayed(timeout, TIMEOUT_MS)

        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (settled) return
                settled = true
                handler.removeCallbacks(timeout)
                TouchStats.replaySuccess++
                // completed=true 只代表「系统执行完这条合成序列」，不代表下层一定消费；
                // 这是平台既有语义，不要在此做「送达」断言。
                PilotShellClient.trace("INJECT", "$label completed=true")
                release(done)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (settled) return
                settled = true
                handler.removeCallbacks(timeout)
                PilotShellClient.trace("INJECT", "$label completed=false(cancelled)")
                release(done)
            }
        }

        val accepted = runCatching { service.dispatchGesture(gesture, callback, handler) }
            .getOrElse {
                Log.w(TAG, "inject $label threw ${it.javaClass.simpleName}: ${it.message}")
                false
            }
        if (!accepted) {
            if (!settled) {
                settled = true
                handler.removeCallbacks(timeout)
                PilotShellClient.trace("INJECT", "$label rejected")
                release(done)
            }
        }
    }

    /** 统一释放：先置 COOLING 再进 IDLE，避免连续注入在同一个 frame 内挤在一起 */
    private fun release(done: () -> Unit) {
        state = State.COOLING
        TouchStats.injectThreadState = "COOLING"
        handler.postDelayed({
            state = State.IDLE
            TouchStats.injectThreadState = "IDLE"
        }, COOL_MS)
        runCatching { done() }
    }

    /** 服务销毁时调用：退出 Looper，避免线程泄漏 */
    fun shutdown() {
        runCatching { handler.removeCallbacksAndMessages(null) }
        runCatching { thread.quitSafely() }
    }
}
