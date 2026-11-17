package com.pilot.wheel

import android.graphics.Bitmap
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import kotlin.math.roundToInt

/**
 * 图标轮廓：底座（软垫）与图标裁剪必须用同一个形状，否则会出现
 * 「圆底座里套一个方图标」这种明显不是一体的观感。
 *
 * 系统里的应用图标形状并不统一：有的圆形、有的 squircle、有的圆角矩形、
 * 少数还是直角方形。所以不能写死一个半径，必须从图标自己的像素里量出来。
 */
internal const val DEFAULT_ICON_CONTOUR = 0.27f

/**
 * 实测图标外轮廓的圆角比例：`圆角半径 / 短边`，取值 0..0.5。
 * 0 = 直角方形，0.5 = 圆形。
 *
 * 做法：先按 alpha 求不透明包围盒（去掉图标自带的透明留白），
 * 再沿包围盒左上角对角线向内找到第一个不透明像素，距离 d 与半径 r 的关系是
 * `d = r·(1 - 1/√2)`，即 `r = d / 0.2929`。
 */
internal fun detectIconContour(bitmap: Bitmap, alphaThreshold: Int = 28): Float {
    val w = bitmap.width
    val h = bitmap.height
    if (w <= 0 || h <= 0) return DEFAULT_ICON_CONTOUR

    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

    fun alphaAt(x: Int, y: Int): Int {
        if (x < 0 || y < 0 || x >= w || y >= h) return 0
        return (pixels[y * w + x] ushr 24) and 0xFF
    }

    var left = w
    var top = h
    var right = -1
    var bottom = -1
    for (y in 0 until h) {
        val row = y * w
        for (x in 0 until w) {
            if (((pixels[row + x] ushr 24) and 0xFF) >= alphaThreshold) {
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
    }
    // 整张图都透明（数据异常）：用默认值，不要退化成方形
    if (right < left || bottom < top) return DEFAULT_ICON_CONTOUR

    val bw = right - left + 1
    val bh = bottom - top + 1
    val short = minOf(bw, bh).toFloat()

    // 四个角都不透明 = 直角方形图标
    val cornerOpaque = alphaAt(left, top) >= alphaThreshold &&
        alphaAt(right, top) >= alphaThreshold &&
        alphaAt(left, bottom) >= alphaThreshold &&
        alphaAt(right, bottom) >= alphaThreshold
    if (cornerOpaque) return 0f

    var d = 0
    val limit = minOf(bw, bh) / 2
    while (d < limit && alphaAt(left + d, top + d) < alphaThreshold) d++
    val radius = d / 0.29289f
    return (radius / short).coerceIn(0f, 0.5f)
}

/**
 * 底座轮廓：图标的圆角是「固定像素」，底座比图标大一圈 pad，
 * 所以半径也要加上 pad，这样底座边框才会均匀等宽，而不是被放大一圈后变粗。
 */
internal fun iconPlateShape(contour: Float, iconSize: Dp, pad: Dp): Shape {
    val ratio = contour.coerceIn(0f, 0.5f)
    return if (ratio >= 0.46f) CircleShape else RoundedCornerShape(iconSize * ratio + pad)
}

/** 由轮廓比例得到 Compose 形状：接近圆形的直接用 CircleShape，其余按百分比圆角 */
internal fun iconShape(contour: Float): Shape {
    val ratio = contour.coerceIn(0f, 0.5f)
    return if (ratio >= 0.46f) {
        CircleShape
    } else {
        // 留 1% 余量，避免与实测半径完全相同时出现极细的直角毛边
        RoundedCornerShape(percent = (ratio * 100f).roundToInt().coerceIn(0, 46))
    }
}
