package com.pilot.wheel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.AsyncTask
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * 最小可行性验证的触发入口。
 *
 * 通过 adb 发广播触发，避免改动任何 UI / 业务逻辑：
 *   adb shell am broadcast -a com.pilot.wheel.INPUT_MONITOR_PROBE
 *
 * 收到广播后同步（goAsync 保持进程存活）调用 [PilotShellClient.startInputMonitorProbe]，
 * 把 shell 进程反射 monitorInput 的结果打回本进程日志（tag WheelPilot）。
 *
 * 注意：广播 receiver 的 onReceive 返回后进程会被系统回收，异步协程来不及跑完。
 * 这里用 goAsync() + 阻塞等待，确保 binder 往返 + 日志在进程存活期间完成。
 */
@androidx.annotation.Keep
class InputMonitorProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action != ACTION && action != ACTION_ALL_CHANNELS && action != ACTION_A11Y_MOTION) return
        // 进入异步处理：拿到 PendingResult，保持进程存活直到 finish()
        val pending = goAsync()
        // 44-B：广播 resultCode/resultData 是 adb 唯一稳定可见的诊断通道——
        // 荣耀过滤第三方 logcat、主进程非 debuggable 无法写 /data/local/tmp，
        // 而 `am broadcast` 会打印 "Broadcast completed: result=N, data=..."。
        // 用 resultCode 编码阶段、resultData 携带摘要，判定 receiver 是否执行/binder 是否绑上。
        val stage = { code: Int, msg: String ->
            resultCode = code
            resultData = msg
            PilotShellClient.trace("PROBE", "stage=$code $msg")
        }
        thread(name = "touch-channel-probe") {
            try {
                PilotShellClient.init(context)
                stage(10, "entry action=$action possible=${PilotShellClient.possible()}")
                when (action) {
                    ACTION_ALL_CHANNELS -> {
                        // 43 号：系统性探测（native 直读 + 反射三签名）
                        stage(11, "probe begin")
                        val result = kotlinx.coroutines.runBlocking {
                            PilotShellClient.probeAllTouchChannels()
                        }
                        // 探测正文经 UserService 已落盘 /data/local/tmp/wheelpilot_probe.txt；
                        // 这里只在 resultData 带长度与 down>0 判定，避免 data 过长被截断。
                        val hasDown = Regex("down>0|DOWN count=[1-9]|RESULT=OK").containsMatchIn(result)
                        stage(12, "probe done len=${result.length} downPositive=$hasDown")
                        Log.e(TAG, "== probeAllTouchChannels result ==\n$result")
                    }
                    ACTION_A11Y_MOTION -> {
                        // 43 号探测项 3：打开 onMotionEvent 日志开关（探索模式由配置 flag 生效）
                        val on = intent?.getBooleanExtra("on", true) ?: true
                        Prefs.setProbeMotionEventLog(context, on)
                        stage(13, "a11y-motion log=$on")
                        Log.w(TAG, "probe-a11y motion log = $on（无障碍服务需重启以应用 flagRequestTouchExplorationMode）")
                    }
                    else -> {
                        // 旧：monitorInput 最小验证
                        val result = kotlinx.coroutines.runBlocking {
                            PilotShellClient.startInputMonitorProbe()
                        }
                        stage(14, "monitor probe len=${result.length}")
                        Log.w(TAG, "== monitorInput probe result ==\n$result")
                    }
                }
            } catch (t: Throwable) {
                stage(-1, "threw ${t.javaClass.simpleName}: ${t.message}")
                Log.e(TAG, "probe receiver threw ${t.javaClass.simpleName}: ${t.message}\n" +
                    t.stackTraceToString())
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION = "com.pilot.wheel.INPUT_MONITOR_PROBE"
        const val ACTION_ALL_CHANNELS = "com.pilot.wheel.PROBE_ALL_TOUCH_CHANNELS"
        const val ACTION_A11Y_MOTION = "com.pilot.wheel.PROBE_A11Y_MOTION"
        private const val TAG = "WheelPilot"
    }
}
