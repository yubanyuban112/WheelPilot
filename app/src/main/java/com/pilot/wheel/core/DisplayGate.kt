package com.pilot.wheel.core

/**
 * 55 号：热区几何的**单一基准**。
 *
 * 背景（MeiWindow `CornerInputMonitor.displayBounds` 的实测结论）：`currentWindowMetrics`
 * 描述的是「一个 MATCH_PARENT 窗口能占到的区域」，在带导航条的竖屏上可能把导航条整段排除
 * （参考机实测差 65px，横屏时这段 inset 又跑到宽度上）。而热区视觉窗口是
 * `TYPE_ACCESSIBILITY_OVERLAY + FLAG_LAYOUT_NO_LIMITS + Gravity.BOTTOM`，它贴的是**物理底边**。
 * 两者一旦不同源，就会出现「看得见的把手在上、判定区在别处」——症状正是
 * 「竖屏角点不灵、横屏正常」。
 *
 * 本类只做三件纯几何的事，全部可 JVM 单测（刻意只用 [WindowRect]，不依赖 android.graphics.Rect）：
 *  1. [unionBounds]：物理显示边界，`maximum ∪ current`，**只扩不缩**；
 *  2. [judgeRect] / [visualRect]：判定矩形与视觉窗口矩形，同一套参数分别用
 *     「判定域底边」与「物理底边」推导，两者之差就是几何错位量；
 *  3. [gateLine]：把上述事实压成一行 `GATE ...`，落进 trace.log，
 *     让「热区不灵」从主观手感变成可读数字。
 *
 * 不负责：触摸事件、窗口创建、注入。纯函数，任意线程可调。
 */
object DisplayGate {

    /** 一次几何快照的原始输入，仅用于日志与自检 */
    data class Geometry(
        val physical: WindowRect,
        val current: WindowRect,
        val maximum: WindowRect,
        val dmWidth: Int,
        val dmHeight: Int,
        val navBottomPx: Int,
        val rotation: Int,
    )

    /**
     * 物理显示边界：`maximum` 与 `current` 取并集。
     *
     * 只扩不缩是刻意约束——任何一边取到空或异常值时都不会让热区上浮，
     * 最差退回 [fallback]（调用方的 displayMetrics）。
     */
    fun unionBounds(current: WindowRect?, maximum: WindowRect?, fallback: WindowRect): WindowRect {
        val c = current?.takeIf { it.width > 0 && it.height > 0 }
        val m = maximum?.takeIf { it.width > 0 && it.height > 0 }
        return when {
            c == null && m == null -> fallback
            c == null -> m!!
            m == null -> c
            else -> WindowRect(
                minOf(c.left, m.left),
                minOf(c.top, m.top),
                maxOf(c.right, m.right),
                maxOf(c.bottom, m.bottom),
            )
        }
    }

    /**
     * 判定矩形：与 [HotspotHitRules.inHotZone] 完全同源（底边 = [bounds].bottom）。
     *
     * @param bottomPx 热区离底距离，**必须**已含导航栏护栏（见 [ConfigRules.hotspotBottomPx]），
     *   否则判定区会压到导航条上、与视觉窗口错位。
     */
    fun judgeRect(
        bounds: WindowRect,
        widthPx: Int,
        heightPx: Int,
        insetPx: Int,
        bottomPx: Int,
        left: Boolean,
    ): WindowRect {
        val top = bounds.bottom - bottomPx - heightPx
        val bottom = bounds.bottom - bottomPx
        return if (left) {
            WindowRect(bounds.left + insetPx, top, bounds.left + insetPx + widthPx, bottom)
        } else {
            WindowRect(bounds.right - insetPx - widthPx, top, bounds.right - insetPx, bottom)
        }
    }

    /**
     * 视觉窗口矩形：`Gravity.BOTTOM | START/END` + 纵向偏移的等效屏幕坐标，
     * 锚在**物理底边**上（与 `FLAG_LAYOUT_NO_LIMITS` 的实际布局一致）。
     */
    fun visualRect(
        physical: WindowRect,
        widthPx: Int,
        heightPx: Int,
        insetPx: Int,
        bottomPx: Int,
        left: Boolean,
    ): WindowRect {
        val top = physical.bottom - bottomPx - heightPx
        val bottom = physical.bottom - bottomPx
        return if (left) {
            WindowRect(physical.left + insetPx, top, physical.left + insetPx + widthPx, bottom)
        } else {
            WindowRect(physical.right - insetPx - widthPx, top, physical.right - insetPx, bottom)
        }
    }

    /**
     * 判定区相对视觉窗口的纵向错位（px）。正数 = 判定区比看得见的把手更靠上，
     * 即「按在把手上却判不中」。0 为理想值。
     */
    fun verticalDriftPx(judge: WindowRect, visual: WindowRect): Int = judge.top - visual.top

    /**
     * 56 号：四分之一椭圆（扇形）命中判定。
     *
     * 与 [judgeRect] 的唯一差别：矩形把「同时远离底边与侧边」的那个对角区也算作热区，
     * 而那一带恰好是与系统「底部上滑回桌面」「侧边返回」重叠最多的区域。椭圆
     * `u² + v² ≤ 1` 把该角切掉，**同一条底边/侧边极值不变**，只减掉对角冗余。
     *
     * 两轴独立且不新增配置：`u` 沿底边用热区宽度，`v` 沿侧边向上用热区高度——
     * 这两个滑条本来就独立可调，这里直接把它们当作椭圆的半轴。
     *
     * 坐标以「角点为圆心、向上向内为正」：
     *  - 圆心 = (底角 x（含内缩）, 物理底边 − 离底距离)；
     *  - `u = |x − 圆心x| / 宽`，`v = (圆心y − y) / 高`，均需 ≥ 0（越界即不属于该象限）。
     *
     * @param bottomPx 离底距离，必须已含导航栏护栏（与 [judgeRect] 同一口径）
     */
    fun sectorContains(
        x: Float,
        y: Float,
        bounds: WindowRect,
        insetPx: Int,
        bottomPx: Int,
        bottomExtentPx: Int,
        edgeExtentPx: Int,
        left: Boolean,
    ): Boolean {
        if (bottomExtentPx <= 0 || edgeExtentPx <= 0) return false
        val centerY = bounds.bottom - bottomPx
        val v = (centerY - y) / edgeExtentPx
        if (v < 0f || v > 1f) return false
        val cornerX = if (left) bounds.left + insetPx else bounds.right - insetPx
        val u = if (left) (x - cornerX) / bottomExtentPx else (cornerX - x) / bottomExtentPx
        if (u < 0f || u > 1f) return false
        return u * u + v * v <= 1f
    }

    /**
     * 56 号：扇形内接的矩形（椭圆的包围盒）。
     *
     * 只用于**日志与降级**（如 `Region` 不可用时退回矩形），判定一律走 [sectorContains]。
     */
    fun sectorBoundsRect(
        bounds: WindowRect,
        insetPx: Int,
        bottomPx: Int,
        bottomExtentPx: Int,
        edgeExtentPx: Int,
        left: Boolean,
    ): WindowRect {
        val centerY = bounds.bottom - bottomPx
        return if (left) {
            WindowRect(bounds.left + insetPx, centerY - edgeExtentPx, bounds.left + insetPx + bottomExtentPx, centerY)
        } else {
            WindowRect(bounds.right - insetPx - bottomExtentPx, centerY - edgeExtentPx, bounds.right - insetPx, centerY)
        }
    }

    /** `l,t-r,b` 形式，日志可 grep、可比对两次安装 */
    fun shortString(r: WindowRect): String = "${r.left},${r.top}-${r.right},${r.bottom}"

    /**
     * 一行几何快照。字段全部是 `key=value`，便于 `grep GATE` 后直接比对两次安装的差异。
     *
     * `dTop` 是核心指标：非 0 就说明判定域与视觉窗口不同源，值得回头查
     * current / max / dm 三个来源。
     */
    fun gateLine(
        g: Geometry,
        judgeLeft: WindowRect,
        visualLeft: WindowRect,
        density: Float,
        extra: String = "",
    ): String {
        val sb = StringBuilder(192)
        sb.append("phys=").append(shortString(g.physical))
        sb.append(" cur=").append(shortString(g.current))
        sb.append(" max=").append(shortString(g.maximum))
        sb.append(" dm=").append(g.dmWidth).append('x').append(g.dmHeight)
        sb.append(" nav=").append(g.navBottomPx)
        sb.append(" rot=").append(g.rotation)
        sb.append(" d=").append((density * 100).toInt() / 100f)
        sb.append(" judge=").append(shortString(judgeLeft))
        sb.append(" visual=").append(shortString(visualLeft))
        sb.append(" dTop=").append(verticalDriftPx(judgeLeft, visualLeft))
        if (extra.isNotEmpty()) sb.append(' ').append(extra)
        return sb.toString()
    }
}
