package com.pilot.wheel

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * 震动反馈。波形与幅度按实机手感调过，保证触发区与轮盘的反馈一致。
 */
object Haptics {

    private var vib: Vibrator? = null

    private fun v(context: Context): Vibrator? {
        vib?.let { return it }
        vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
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

    /** 呼出轮盘：双脉冲 [0,14,0,40]ms / 幅度 [0,120,0,180] */
    fun open(context: Context) = play(
        context,
        VibrationEffect.createWaveform(
            longArrayOf(0, 14, 0, 40),
            intArrayOf(0, 120, 0, 180),
            -1,
        ),
    )

    /** 选中/启动应用：[0,16,0,28]ms / 幅度 [0,200,0,255] */
    fun pick(context: Context) = play(
        context,
        VibrationEffect.createWaveform(
            longArrayOf(0, 16, 0, 28),
            intArrayOf(0, 200, 0, 255),
            -1,
        ),
    )

    /** 短确认：22ms / 幅度 220 —— 轮盘选中确认仍用这个 */
    fun select(context: Context) = play(
        context,
        VibrationEffect.createOneShot(22, 220),
    )

    /** 滑动刻度：12ms / 幅度 180 —— 轮盘高亮切换时用这个 */
    fun tick(context: Context) = play(
        context,
        VibrationEffect.createOneShot(12, 180),
    )
}
