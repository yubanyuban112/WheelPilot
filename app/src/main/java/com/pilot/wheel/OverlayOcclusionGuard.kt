package com.pilot.wheel

import android.content.Context
import android.os.Build
import android.view.View
import android.view.WindowManager
import com.pilot.wheel.diag.TouchStats

/**
 * 同 UID 遮挡闸门探针（53 号 · 维度一 · 第 3 项）。
 *
 * 机理（与「单读 LayoutParams.alpha」的旧假设不同）：
 *  - 输入分发侧对**可触摸**窗口按 per-UID 累计不透明度裁决遮挡，
 *    阈值来自 `InputManager.getMaximumObscuringOpacityForTouch()`（API 31+，隐藏接口）；
 *  - 我们自己的热区窗在旁路模式与注入期间都是 `FLAG_NOT_TOUCHABLE`，**不进入命中链**，
 *    所以它自身 alpha 与闸门无关；
 *  - 真正会把注入事件吃掉的是「同 UID 其它仍在可触摸状态、且覆盖注入点」的浮层
 *    （轮盘、悬浮球、边缘面板…）。它们叠加后一旦被判遮挡，表现就是
 *    `dispatchGesture` 返回 true、`onCompleted` 也到、但下层毫无响应。
 *
 * 本类因此做两件事：
 *  1. 读阈值（读不到就回落 0.8 并标注来源，绝不假装已知）；
 *  2. 在注入前算「注入点上方同 UID 可触摸浮层的累计不透明度 + 是否真的覆盖该点」，
 *     覆盖即视为风险（哪怕 alpha 很小，事件也会被自己的窗口接走）。
 *
 * 线程归属：全部在主线程调用（View/LayoutParams 读取）；查询为纯内存，无 I/O。
 */
class OverlayOcclusionGuard(private val context: Context) {

    data class Risk(
        val combined: Float,
        val threshold: Float,
        val thresholdSource: String,
        val covering: String,
        /** true = 注入点被自家可触摸浮层覆盖，回放注定无效 */
        val blocked: Boolean,
    ) {
        fun describe(): String =
            "alpha=%.2f thr=%.2f src=%s cover=%s".format(combined, threshold, thresholdSource, covering)
    }

    private val providers = ArrayList<Pair<String, () -> View?>>(6)

    private val thresholdInfo: Pair<Float, String> by lazy { readThreshold() }

    fun register(name: String, provider: () -> View?) {
        providers.add(name to provider)
    }

    /** 注入点风险评估；无覆盖时 blocked=false、combined≈0 */
    fun riskAt(x: Float, y: Float): Risk {
        val (threshold, source) = thresholdInfo
        var rest = 1f
        val covering = StringBuilder()
        val loc = IntArray(2)
        for ((name, provider) in providers) {
            val view = runCatching { provider() }.getOrNull() ?: continue
            if (!view.isShown || view.width <= 0 || view.height <= 0) continue
            val lp = view.layoutParams as? WindowManager.LayoutParams ?: continue
            // 只有可触摸窗口才参与命中与遮挡；NOT_TOUCHABLE 的层直接跳过
            val touchable = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0
            if (!touchable) continue
            runCatching { view.getLocationOnScreen(loc) }.getOrNull() ?: continue
            val inside = x >= loc[0] && x <= loc[0] + view.width &&
                y >= loc[1] && y <= loc[1] + view.height
            if (!inside) continue
            val alpha = if (lp.alpha <= 0f) 1f else lp.alpha.coerceIn(0f, 1f)
            rest *= (1f - alpha)
            if (covering.isNotEmpty()) covering.append('+')
            covering.append(name).append(':').append("%.2f".format(alpha))
        }
        val combined = 1f - rest
        // 覆盖即阻断：alpha 再低，事件也是被自家窗口接走而不是下层
        val blocked = covering.isNotEmpty()
        return Risk(combined, threshold, source, if (covering.isEmpty()) "none" else covering.toString(), blocked)
    }

    /** 供快照使用的单行描述（不触发覆盖计算时取上一次结论） */
    fun describeThreshold(): String {
        val (t, s) = thresholdInfo
        return "%.2f/%s".format(t, s)
    }

    /**
     * 反射读系统阈值。API 31+ 才有；失败一律回落 0.8 并把来源写清楚 —— 断言「已知」才是危险的。
     *
     * 真机实测（Android 17 / 荣耀 AAK-AN00）：`InputManager.getInstance` 已被移除，
     * 所以先走 `Context.getSystemService`，再退回反射 getInstance。
     * 两种路径都失败时来源为 `no-manager`；拿到实例但方法不可见（hidden API 限制）为 `no-method`。
     */
    private fun readThreshold(): Pair<Float, String> {
        if (Build.VERSION.SDK_INT < 31) return 0.8f to "sdk<31"
        val im = runCatching {
            context.getSystemService(android.hardware.input.InputManager::class.java)
        }.getOrNull() ?: runCatching {
            Class.forName("android.hardware.input.InputManager")
                .getMethod("getInstance").invoke(null)
        }.getOrNull() ?: return 0.8f to "no-manager"
        val value = runCatching {
            val m = im.javaClass.getMethod("getMaximumObscuringOpacityForTouch")
            m.isAccessible = true
            m.invoke(im) as? Float
        }.getOrNull() ?: return 0.8f to "no-method"
        return value to "system"
    }

    /** 一次性风险日志（同一 verdict 不重复刷屏由调用方控制） */
    fun reportIfRisk(label: String, risk: Risk) {
        if (!risk.blocked) return
        TouchStats.overlayRisk++
        TouchStats.overlayLastVerdict = risk.describe()
        val line = "OVERLAY_ALPHA_RISK $label ${risk.describe()}"
        android.util.Log.w(PilotService.TAG, line)
        PilotShellClient.trace("OVERLAY_ALPHA_RISK", "$label ${risk.describe()}")
    }
}
