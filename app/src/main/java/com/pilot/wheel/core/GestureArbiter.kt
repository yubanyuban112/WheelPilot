package com.pilot.wheel.core

import kotlin.math.abs
import kotlin.math.hypot

/**
 * 手势意图区分器（纯函数状态机，可单测）。
 *
 * 输入：一串触摸事件（down / move / up / cancel 的坐标与时间戳）。
 * 输出：[Arbitration] —— NONE（无动作）、CLICK_PASS（普通点击，透传下层）、
 *       EDGE_SWIPE_TRIGGER（符合边缘内滑，触发小窗）。
 *
 * 判定规则（与需求对齐）：
 *  - ACTION_DOWN 落在热区内：**不立即触发**，只记录 down 坐标与时间；
 *  - 位移 < 触发阈值且持续时间短 → [Arbitration.CLICK_PASS]（视为普通点击，
 *    不触发小窗、不注入任何事件，本次点击完全交给下层应用）；
 *  - 位移 >= 触发阈值且方向符合「从屏幕边缘向内滑动」→ [Arbitration.EDGE_SWIPE_TRIGGER]；
 *  - 抬指（UP）时若既没滑动也没点击 → [Arbitration.NONE]；
 *  - ACTION_CANCEL → 清理全部状态，返回 [Arbitration.NONE]。
 *
 * 本类不碰任何窗口 flag、不注入触摸，只输出意图；是否呼出小窗由调用方决定。
 * 几何（边缘宽度、触发阈值、滑动方向、生效屏幕区域）全部通过 [Config] 注入。
 */
class GestureArbiter(
    private val config: Config,
) {

    /** 可配置几何与阈值 */
    data class Config(
        /** 热区左边缘（屏幕逻辑坐标，px） */
        val zoneLeft: Float = 0f,
        /** 热区上边缘 */
        val zoneTop: Float = 0f,
        /** 热区右边缘 */
        val zoneRight: Float = 0f,
        /** 热区下边缘 */
        val zoneBottom: Float = 0f,
        /** 触发呼出的最小位移（px） */
        val triggerDistancePx: Float = 22f * 3f,
        /** 判定为「点击」的最大位移（px），小于等于此值视为轻触 */
        val clickTolerancePx: Float = 8f,
        /** 判定为「点击」的最大持续时间（ms），超过则不再是轻触 */
        val maxClickDurationMs: Long = 500L,
        /** 滑动方向是否要求「从边缘向内」；false 时只按位移判定 */
        val requireInwardDirection: Boolean = true,
        /** 维度4：MOVE 采样阈值（px）——累计位移小于此值不进入完整判定，抑制高频抖动事件 */
        val moveSampleDistancePx: Float = 2f,
    ) {
        fun contains(x: Float, y: Float): Boolean =
            x >= zoneLeft && x <= zoneRight && y >= zoneTop && y <= zoneBottom
    }

    /** 意图输出 */
    enum class Arbitration {
        /** 无动作：DOWN 未落在热区、或已清理 */
        NONE,

        /** 普通点击（轻触/短按）：不触发小窗，事件交回下层 */
        CLICK_PASS,

        /** 符合边缘内滑：触发小窗呼出 */
        EDGE_SWIPE_TRIGGER,
    }

    private enum class State { IDLE, DOWN_IN_ZONE }

    private var state = State.IDLE
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var triggered = false
    /** 维度4：上次参与判定的 MOVE 坐标，用于距离采样降频 */
    private var sampleX = 0f
    private var sampleY = 0f

    /** 最近一次 DOWN 的坐标（屏幕逻辑坐标），供调用方做二次几何校验。无有效 DOWN 时为 null。 */
    val lastDownPoint: Pair<Float, Float>?
        get() = if (state == State.DOWN_IN_ZONE) downX to downY else null

    /**
     * 喂入一个触摸事件。
     *
     * @param action MotionEvent 的 action（用 ACTION_DOWN=0/UP=1/MOVE=2/CANCEL=3）
     * @param x/y 屏幕逻辑坐标（px，左上原点；monitorInput 的 getRawX/getRawY 已满足）
     * @param eventTime 事件时间戳（ms，MotionEvent.getEventTime 或 elapsedRealtime）
     * @return 意图判定结果
     */
    fun feed(action: Int, x: Float, y: Float, eventTime: Long): Arbitration {
        val masked = action and 0xff
        return when (masked) {
            ACTION_DOWN -> onDown(x, y, eventTime)
            ACTION_MOVE -> onMove(x, y, eventTime)
            ACTION_UP -> onUp(x, y, eventTime)
            ACTION_CANCEL -> onCancel()
            else -> Arbitration.NONE
        }
    }

    private fun onDown(x: Float, y: Float, eventTime: Long): Arbitration {
        reset()
        if (!config.contains(x, y)) return Arbitration.NONE
        state = State.DOWN_IN_ZONE
        downX = x
        downY = y
        downTime = eventTime
        triggered = false
        sampleX = x
        sampleY = y
        // DOWN 落在热区内：不立即触发，等待后续 move/up 判定
        return Arbitration.NONE
    }

    private fun onMove(x: Float, y: Float, eventTime: Long): Arbitration {
        if (state != State.DOWN_IN_ZONE || triggered) return Arbitration.NONE
        // 维度4：距离采样降频——相对上次参与判定的点位移不足阈值时直接丢弃，
        // 避免每个 MOVE 都算 hypot + 方向（快速滑动时 MOVE 频率可达 120Hz+）。
        if (hypot(x - sampleX, y - sampleY) < config.moveSampleDistancePx) return Arbitration.NONE
        sampleX = x
        sampleY = y
        val dist = hypot(x - downX, y - downY)
        if (dist < config.triggerDistancePx) {
            // 尚未达到滑动阈值，继续观察
            return Arbitration.NONE
        }
        if (config.requireInwardDirection && !isInwardSwipe(downX, downY, x, y)) {
            // 方向不符合边缘内滑：视为普通滑动，不触发
            triggered = true
            return Arbitration.NONE
        }
        triggered = true
        return Arbitration.EDGE_SWIPE_TRIGGER
    }

    private fun onUp(x: Float, y: Float, eventTime: Long): Arbitration {
        val result: Arbitration
        if (state != State.DOWN_IN_ZONE) {
            result = Arbitration.NONE
        } else if (triggered) {
            result = Arbitration.NONE
        } else {
            val dist = hypot(x - downX, y - downY)
            val dur = eventTime - downTime
            result = if (dist <= config.clickTolerancePx && dur <= config.maxClickDurationMs) {
                Arbitration.CLICK_PASS
            } else {
                Arbitration.NONE
            }
        }
        reset()
        return result
    }

    private fun onCancel(): Arbitration {
        reset()
        return Arbitration.NONE
    }

    /** 方向判定：从屏幕边缘「向内」滑动（左缘向右、右缘向左，主体垂直向上） */
    private fun isInwardSwipe(x0: Float, y0: Float, x1: Float, y1: Float): Boolean {
        val dx = x1 - x0
        val dy = y1 - y0
        // 判定 down 点靠近屏幕左缘还是右缘，决定「向内」的 x 方向
        val nearLeft = x0 < (config.zoneLeft + config.zoneRight) / 2f
        val inwardX = if (nearLeft) dx >= 0f else dx <= 0f
        // 要求主体向上（dy < 0）且垂直分量占主导（近似垂直上滑）
        val mostlyUp = dy < 0f && abs(dy) >= 0.6f * abs(dx)
        return inwardX && mostlyUp
    }

    private fun reset() {
        state = State.IDLE
        downX = 0f
        downY = 0f
        downTime = 0L
        triggered = false
        sampleX = 0f
        sampleY = 0f
    }

    companion object {
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MOVE = 2
        const val ACTION_CANCEL = 3
    }
}
