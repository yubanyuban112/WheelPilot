package com.pilot.wheel

/**
 * 旁路触摸感知的抽象接口（事件源）。
 *
 * 热区窗口常驻 FLAG_NOT_TOUCHABLE、完全不参与触摸分发后，触摸感知必须走旁路。
 * 本接口把「从何处获得全局触摸事件流」与「如何判定热区手势」解耦，
 * 便于在 [MonitorInputTouchMonitor]（monitorInput 方案）与降级方案之间切换。
 *
 * 契约：
 *  - 构造时通过 [listener] 注入事件消费者；[start] 成功后持续回调；
 *  - 感知**只读不消费**：回调方不得注入点击，事件照常派发给下层；
 *  - 失败必须显式抛出 [TouchMonitorUnavailableException]，禁止静默失败。
 */
interface TouchMonitor {

    /** 触摸事件监听器（屏幕逻辑坐标 px，左上原点；eventTime 为毫秒时间戳）。 */
    fun interface Listener {
        fun onTouch(action: Int, x: Float, y: Float, eventTime: Long)
    }

    /** 启动监听。成功返回；失败抛出 [TouchMonitorUnavailableException]。 */
    fun start()

    /** 停止监听并释放资源。幂等，可重复调用。 */
    fun stop()

    /** 是否已成功启动并处于监听中。 */
    fun isActive(): Boolean
}

/**
 * 旁路监听不可用的显式异常。
 *
 * 携带 [stage]（失败发生在哪一步）与 [reason]（可读原因），
 * 供上层打印明确日志并触发降级。绝不允许静默吞掉。
 */
class TouchMonitorUnavailableException(
    val stage: String,
    reason: String,
    cause: Throwable? = null,
) : Exception("TouchMonitor unavailable at $stage: $reason", cause)
