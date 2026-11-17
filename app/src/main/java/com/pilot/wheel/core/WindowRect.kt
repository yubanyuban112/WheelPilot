package com.pilot.wheel.core

/**
 * 纯 JVM 的窗口矩形（不依赖 android.graphics.Rect，可单测）。
 * AppLauncher 用 toAndroidRect() 转成系统 Rect。
 */
data class WindowRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {

    val width: Int get() = right - left
    val height: Int get() = bottom - top
}
