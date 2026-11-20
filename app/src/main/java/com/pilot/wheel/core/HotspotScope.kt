package com.pilot.wheel.core

import android.content.res.Configuration

/**
 * 59 号：热区的**方向生效范围**（纯函数，可单测）。
 *
 * 动机：横屏时用户多半在玩游戏或看视频，角落内滑既容易误触、又和游戏自身的左下/右下手势打架。
 * 让热区只在竖屏（或只在横屏）生效，是成本最低的误触收敛手段 —— 比缩热区更彻底，
 * 且不改变竖屏的手感。
 *
 * 应用维度不在这里：呼出黑名单（[com.pilot.wheel.Prefs.blacklist]）已覆盖「某应用内不呼出」，
 * 两个门控是并列关系（黑名单 AND 方向）。
 */
object HotspotScope {

    /** 竖屏与横屏都生效（默认，行为与引入前一致） */
    const val BOTH = 0

    /** 只在竖屏生效 */
    const val PORTRAIT = 1

    /** 只在横屏生效 */
    const val LANDSCAPE = 2

    fun normalize(mode: Int): Int = if (mode in BOTH..LANDSCAPE) mode else BOTH

    /**
     * @param mode 用户设定（[BOTH] / [PORTRAIT] / [LANDSCAPE]）
     * @param configOrientation `Configuration.ORIENTATION_PORTRAIT` / `LANDSCAPE`
     */
    fun allows(mode: Int, configOrientation: Int): Boolean = when (normalize(mode)) {
        PORTRAIT -> configOrientation == Configuration.ORIENTATION_PORTRAIT
        LANDSCAPE -> configOrientation == Configuration.ORIENTATION_LANDSCAPE
        else -> true
    }

    fun label(mode: Int): String = when (normalize(mode)) {
        PORTRAIT -> "仅竖屏"
        LANDSCAPE -> "仅横屏"
        else -> "竖屏与横屏"
    }

    /** 点一下切换到下一档：全部 → 仅竖屏 → 仅横屏 → 全部 */
    fun next(mode: Int): Int = when (normalize(mode)) {
        BOTH -> PORTRAIT
        PORTRAIT -> LANDSCAPE
        else -> BOTH
    }
}
