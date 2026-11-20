package com.pilot.wheel

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * 震动反馈。波形与幅度按实机手感调过，保证触发区与轮盘的反馈一致。
 *
 * 3.18.1 修正「强度调到最大仍然不明显」：
 *  - 原先三档只缩放 amplitude（0.5/0.78/1.0），而强档的基准幅度本身只有 120–220，
 *    在支持幅度控制的马达上等于一直没跑满；不支持幅度控制的老马达上则完全无差别。
 *  - 现在强档把幅度抬到 255 并同时拉长每个波形的时长（时长在所有设备上都能被感知），
 *    轻档相应压低时长，三档之间的差异不再只依赖马达是否支持 amplitude。
 */
object Haptics {

    private var vib: Vibrator? = null
    /** 是否支持幅度控制；不支持时 amplitude 会被系统忽略，只能靠时长区分档位 */
    private var amplitudeControl: Boolean? = null

    private data class Profile(val amp: Float, val dur: Float)

    private fun profile(context: Context): Profile = when (Prefs.hapticStrength(context)) {
        Prefs.HAPTIC_LIGHT -> Profile(amp = 0.55f, dur = 0.72f)
        Prefs.HAPTIC_STRONG -> Profile(amp = 1f, dur = 1.42f)
        else -> Profile(amp = 0.84f, dur = 1f)
    }

    private fun v(context: Context): Vibrator? {
        vib?.let { return it }
        vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        amplitudeControl = runCatching { vib?.hasAmplitudeControl() == true }.getOrDefault(false)
        return vib
    }

    private fun play(context: Context, effect: VibrationEffect) {
        runCatching {
            val vibrator = v(context)
            if (vibrator?.hasVibrator() == true) vibrator.vibrate(effect)
        }.onFailure {
            // 失败绝不静默：缺 VIBRATE 权限等问题要在 logcat 里现形
            Log.e("Haptics", "vibrate failed", it)
        }
    }

    /**
     * 按「震动强度」档位缩放幅度（1..255）。
     * 不支持幅度控制的马达会忽略这个值（按满振播放），那种设备上三档的差异由
     * [ms] 的时长缩放承担 —— 两个维度都改，才不会出现「调了没反应」。
     */
    private fun scaled(context: Context, base: Int): Int {
        val amp = (base * profile(context).amp).toInt().coerceIn(1, 255)
        return if (amplitudeControl == false) 255 else amp
    }

    /** 时长同样按档位缩放：这是唯一在「无幅度控制」马达上也能被感知的维度 */
    private fun ms(context: Context, base: Long): Long =
        (base * profile(context).dur).toLong().coerceAtLeast(6L)

    private fun waveform(context: Context, timings: LongArray, amplitudes: IntArray): VibrationEffect {
        val t = LongArray(timings.size) { ms(context, timings[it]) }
        val a = IntArray(amplitudes.size) { scaled(context, amplitudes[it]) }
        return VibrationEffect.createWaveform(t, a, -1)
    }

    /** 呼出轮盘：双脉冲 [0,14,0,40]ms / 幅度 [0,120,0,180] → 强档 20/57ms、幅度 255 */
    fun open(context: Context) = play(
        context,
        waveform(context, longArrayOf(0, 16, 0, 46), intArrayOf(0, 150, 0, 255)),
    )

    /** 选中/启动应用：[0,16,0,28]ms / 幅度 [0,200,0,255] */
    fun pick(context: Context) = play(
        context,
        waveform(context, longArrayOf(0, 18, 0, 34), intArrayOf(0, 230, 0, 255)),
    )

    /** 短确认：34ms 单振（原 22ms 在强档几乎摸不到） */
    fun select(context: Context) = play(
        context,
        VibrationEffect.createOneShot(ms(context, 34L), scaled(context, 255)),
    )

    /** 滑动刻度：16ms 单振 */
    fun tick(context: Context) = play(
        context,
        VibrationEffect.createOneShot(ms(context, 16L), scaled(context, 210)),
    )

    /**
     * 强度调节时的即时试振：三档给出彼此可分辨的样本（轻=短促，中=单击，强=双击）。
     * 设置页选中某一档后立刻调用，用户不用退出设置页再呼出轮盘才知道手感。
     */
    fun preview(context: Context) = when (Prefs.hapticStrength(context)) {
        Prefs.HAPTIC_LIGHT -> play(
            context,
            VibrationEffect.createOneShot(ms(context, 18L), scaled(context, 160)),
        )
        Prefs.HAPTIC_STRONG -> play(
            context,
            waveform(context, longArrayOf(0, 26, 42, 42), intArrayOf(0, 255, 0, 255)),
        )
        else -> play(
            context,
            VibrationEffect.createOneShot(ms(context, 32L), scaled(context, 235)),
        )
    }
}
