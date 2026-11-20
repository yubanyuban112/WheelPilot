package com.pilot.wheel

import android.os.Build
import android.util.Log

/**
 * 旁路触摸「通道层」状态机 —— 单一事实来源。
 *
 * 背景：旁路触摸的感知通道有多条，按优先级 monitorInput > getevent > 无障碍 onMotionEvent。
 * 不同机型可用通道不同：
 *  - Android 17 移除了 InputManager.getInstance → monitorInput 失效；
 *  - 部分 ROM 无 getevent 读取权限；
 *  - 系统 < 34 无 setMotionEventSources → onMotionEvent 不可用。
 *
 * 本对象记录「当前实际跑在哪一层」，供三处使用：
 *  1. logcat 诊断：每次切换通道都打 `touch-layer -> xxx` 日志，换机型排查一眼定位；
 *  2. 设置页展示：给用户看「旁路触摸当前通道」；
 *  3. 互斥仲裁：只有无障碍层在跑时 onMotionEvent 才接管，避免同一边缘内滑被两条通道双重触发。
 */
object TouchLayerState {

    enum class Layer(val label: String) {
        /** Shizuku InputManager.monitorInput（首选，不读 /dev/input） */
        MONITOR_INPUT("monitorInput（Shizuku）"),

        /** Shizuku getevent 读 /dev/input/eventX（monitorInput 失效时） */
        GETEVENT("getevent（Shizuku）"),

        /** 无障碍 onMotionEvent（Shizuku 全部失效时的兜底，需系统 >= 34） */
        ACCESSIBILITY("无障碍 onMotionEvent"),

        /** 全部通道不可用：旁路触摸无法开启 */
        NONE("不可用"),
    }

    private const val TAG = "WheelPilot"

    @Volatile
    var current: Layer = Layer.NONE
        private set

    /** 当前通道的附加说明（如 getevent 设备路径 / 等待触摸事件） */
    @Volatile
    var detail: String = ""
        private set

    /** 最近一次切到 NONE 的原因，供设置页与日志展示 */
    @Volatile
    var lastError: String = ""
        private set

    /** 无障碍 onMotionEvent 是否受支持：setMotionEventSources 需要 API 34+ */
    fun accessibilitySupported(): Boolean = Build.VERSION.SDK_INT >= 34

    fun set(layer: Layer, detail: String = "", error: String = "") {
        val changed = current != layer || this.detail != detail
        current = layer
        this.detail = detail
        if (error.isNotEmpty()) lastError = error
        if (changed) {
            val msg = "touch-layer -> ${layer.label}${if (detail.isNotEmpty()) " ($detail)" else ""}" +
                (if (error.isNotEmpty()) " reason=$error" else "")
            Log.w(TAG, msg)
            // 荣耀 Android 17 过滤第三方 logcat：层切换同步落盘，供 adb cat 判定降级时机
            PilotShellClient.trace("LAYER", msg)
        }
    }

    /** 给设置页/诊断读的人类可读说明；enabled=false 时直接告知已关闭 */
    fun describe(enabled: Boolean): String = when {
        !enabled -> "旁路触摸已关闭"
        current == Layer.NONE ->
            if (lastError.isNotEmpty()) "不可用：$lastError" else "正在探测通道…"
        current == Layer.MONITOR_INPUT ->
            "当前通道：monitorInput（Shizuku 直连 InputManager）"
        current == Layer.GETEVENT ->
            "当前通道：getevent（Shizuku 读 ${detail.ifEmpty { "底层触摸设备" }}）"
        current == Layer.ACCESSIBILITY ->
            "当前通道：无障碍 onMotionEvent（Shizuku 不可用，已自动兜底）"
        else -> current.label
    }
}
