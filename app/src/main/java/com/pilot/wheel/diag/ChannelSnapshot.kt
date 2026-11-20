package com.pilot.wheel.diag

import android.content.Context
import android.os.Build
import android.util.Log
import com.pilot.wheel.InputEventReceiverBridge
import com.pilot.wheel.OverlayOcclusionGuard
import com.pilot.wheel.PilotShellClient
import com.pilot.wheel.TouchLayerState
import com.pilot.wheel.core.HotspotRouter

/**
 * 周期通道快照（53 号 · 维度四 · 第 8 项）。
 *
 * 存在理由：离散日志回答不了「此刻到底哪条通道在跑、回放是否真的零调用、
 * capabilities 够不够」这三个验收问题。快照把三者放进**同一帧**，
 * 使「旁路可用时回放零调用」和「capabilities 缺失 → INCAPABLE」成为一眼可判的证据。
 *
 * 输出双通道：logcat（`SNAPSHOT` 前缀）+ 落盘（`/data/local/tmp/wheelpilot_trace.log`，
 * 荣耀等过滤第三方 logcat 的 ROM 只能靠后者）。
 *
 * 线程：由主线程定时调用；所有字段读取均为 volatile 快照，无锁、无 I/O 阻塞。
 */
object ChannelSnapshot {

    private const val TAG = "WheelPilot"

    @Volatile
    private var lastLayer: String = ""

    /** 组装并输出一帧。返回值便于单测/调试。 */
    fun emit(
        context: Context,
        caps: Int,
        touchExploration: Boolean,
        guard: OverlayOcclusionGuard?,
        router: HotspotRouter?,
    ): String {
        val layer = TouchLayerState.current.name
        if (lastLayer.isNotEmpty() && lastLayer != layer) {
            val change = "CHANNEL_STATE_CHANGE from=$lastLayer to=$layer detail=${TouchLayerState.detail}"
            Log.w(TAG, change)
            PilotShellClient.trace("CHANNEL_STATE_CHANGE", "from=$lastLayer to=$layer detail=${TouchLayerState.detail}")
        }
        lastLayer = layer

        val canInject = caps and 0x8 != 0
        val line = buildString {
            append("layer=").append(layer)
            append(" ; monitor=").append(InputEventReceiverBridge.health())
            append(" ; a11y=caps=").append(caps)
                .append(" inj=").append(if (canInject) "ok" else "INCAPABLE")
                .append(" te=").append(touchExploration)
            append(" ; evdev=").append(TouchStats.evdevDescriptor)
            append(" ; spy=").append(TouchStats.spyStatus)
            append(" ; coord=").append(TouchStats.coordDomain)
            append(" ; overlay=").append(guard?.describeThreshold() ?: "n/a")
                .append(" last=").append(TouchStats.overlayLastVerdict)
            append(" ; route=").append(TouchStats.routeCalls)
                .append("/main=").append(TouchStats.dispatchToMain)
                .append("/oob=").append(TouchStats.downOutOfBounds)
            append(" ; edge=").append(TouchStats.edgeExclude)
                .append("/cancel=").append(TouchStats.edgeCancelObserved)
            append(" ; hit=").append(router?.hitCount ?: -1)
                .append("/exclude=").append(router?.edgeExcludeCount ?: -1)
                // 59 号：方向门控拦下的次数（非 0 说明「生效方向」设定正在起作用）
                .append(" ; orient=").append(TouchStats.orientationBlocked)
            append(" ; replay=att=").append(TouchStats.replayAttempt)
                .append("/ok=").append(TouchStats.replaySuccess)
                .append("/to=").append(TouchStats.replayTimeout)
                .append("/drop=").append(TouchStats.replayDropped)
                .append("/incap=").append(TouchStats.replayIncapable)
                .append("/sem=").append(TouchStats.replaySemanticLost)
                .append("/thread=").append(TouchStats.injectThreadState)
            append(" ; fp=sdk").append(Build.VERSION.SDK_INT)
                .append('/').append(Build.MANUFACTURER)
                .append('/').append(Build.MODEL)
        }
        Log.i(TAG, "SNAPSHOT $line")
        PilotShellClient.trace("SNAPSHOT", line)
        return line
    }
}
