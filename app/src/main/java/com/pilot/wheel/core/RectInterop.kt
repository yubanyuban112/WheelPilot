package com.pilot.wheel.core

import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.view.WindowManager

/**
 * 55 号：`android.graphics.Rect` / `WindowManager` 与纯数据 [WindowRect] / [DisplayGate] 之间的桥。
 *
 * 单独成文件的原因：单测跑在 JVM 上，`android.graphics.Rect` 的方法体是 stub（调用即抛），
 * 所以**纯几何逻辑一律放在 [DisplayGate]（只用 WindowRect）**，这里只做取数与转换，
 * 不承担任何判定语义。
 */

/** `android.graphics.Rect` → 纯数据 [WindowRect] */
fun Rect.toWindowRect(): WindowRect = WindowRect(left, top, right, bottom)

/** 纯数据 → `android.graphics.Rect`（给 `Region.union(Rect)` 之类的老 API 用） */
fun WindowRect.toRect(): Rect = Rect(left, top, right, bottom)

/**
 * 物理显示边界：`maximumWindowMetrics ∪ currentWindowMetrics`，失败退回 displayMetrics。
 *
 * 之所以不直接用 `currentWindowMetrics`：它描述的是「一个 MATCH_PARENT 窗口能占到的区域」，
 * 在带导航条的竖屏上可能把导航条整段排除，而热区窗口贴的是物理底边（详见 [DisplayGate]）。
 */
fun WindowManager.physicalBounds(dmWidth: Int, dmHeight: Int): WindowRect {
    val current = runCatching { currentWindowMetrics.bounds.toWindowRect() }.getOrNull()
    val maximum = runCatching { maximumWindowMetrics.bounds.toWindowRect() }.getOrNull()
    return DisplayGate.unionBounds(current, maximum, WindowRect(0, 0, dmWidth, dmHeight))
}

/** 导航条高度（px，忽略可见性）；取不到返回 0 */
fun WindowManager.navBarBottomPx(): Int = runCatching {
    currentWindowMetrics.windowInsets
        .getInsetsIgnoringVisibility(android.view.WindowInsets.Type.navigationBars())
        .bottom
}.getOrDefault(0)

/** 系统手势带 inset（px）；取不到返回全 0 */
fun WindowManager.systemGestureInsets(): IntArray = runCatching {
    val i = currentWindowMetrics.windowInsets
        .getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemGestures())
    intArrayOf(i.left, i.top, i.right, i.bottom)
}.getOrDefault(intArrayOf(0, 0, 0, 0))

/**
 * 56 号：把 [DisplayGate.sectorContains] 描述的四分之一椭圆转成 [Region]。
 *
 * 仅用于区域 passthrough（探索模式）那类必须传 `Region` 的老 API；
 * 命中判定一律走纯函数 [DisplayGate.sectorContains]，两者口径一致（同圆心、同半轴）。
 */
fun sectorRegion(
    bounds: WindowRect,
    insetPx: Int,
    bottomPx: Int,
    bottomExtentPx: Int,
    edgeExtentPx: Int,
    left: Boolean,
): Region {
    val cx = if (left) bounds.left + insetPx else bounds.right - insetPx
    val cy = bounds.bottom - bottomPx
    val region = Region()
    if (bottomExtentPx <= 0 || edgeExtentPx <= 0) return region
    val oval = RectF(
        (cx - bottomExtentPx).toFloat(),
        (cy - edgeExtentPx).toFloat(),
        (cx + bottomExtentPx).toFloat(),
        (cy + edgeExtentPx).toFloat(),
    )
    val path = Path().apply {
        moveTo(cx.toFloat(), cy.toFloat())
        // 左下角取 270°→360°（屏幕内象限：向右向上）；右下角取 180°→270°（向左向上）
        if (left) arcTo(oval, 270f, 90f, false) else arcTo(oval, 180f, 90f, false)
        close()
    }
    return region.apply { setPath(path, Region(bounds.left, bounds.top, bounds.right, bounds.bottom)) }
}
