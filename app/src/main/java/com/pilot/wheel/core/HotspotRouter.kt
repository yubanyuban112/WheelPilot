package com.pilot.wheel.core

import android.graphics.Rect
import kotlin.math.hypot

/**
 * 热区分流路由器（53 号 · 维度一）。
 *
 * 设计要点：
 *  - **无窗口/无 Context**：纯几何 + 纯函数，可单测，可在任意线程调用；
 *  - **单一职责**：只回答「这次触摸该不该呼出 / 该不该放行」，不碰窗口、不注入、不弹 UI；
 *  - **线程归属**：由调用方在专用 routing 线程串行调用（[com.pilot.wheel.RawHotspotController]
 *    的 `hotspot-router` 线程），因此内部字段不加锁也不暴露给多线程。
 *
 * 与旧实现的差异（语义不变，只补归因）：
 *  1. 边缘带内的 DOWN 先判 [Route.EDGE_EXCLUDE]，不进入 [GestureArbiter]——系统返回手势带内
 *     我们主动不触发，日志可与「下层已被系统 CANCEL」区分开；
 *  2. ACTION_CANCEL 显式返回 [Route.CANCEL]，供上层记 `EDGE_CANCEL_OBSERVED`；
 *  3. 命中/排除计数内聚在这里，快照直接读，不再散落在各个分支的 Log 里。
 *
 * 明确不负责：被系统 `pilferPointers()` 抢占的指针流不恢复（红线）。
 */
class HotspotRouter(config: Config) {

    /**
     * @param bounds 坐标域（**物理**显示区域，逻辑坐标 px）。热区几何与归一化都以它为准；
     *   必须是 `maximum ∪ current`（见 [DisplayGate.unionBounds]），不能只取
     *   `currentWindowMetrics`——后者在带导航条的竖屏上可能少一段，判定区会整体上浮；
     * @param rotation 当前旋转角 0/90/180/270，仅用于快照与误映射定位；
     * @param hotBottomDp 热区离底距离（dp）。调用方须传「等效 dp」——即已按
     *   [ConfigRules.hotspotBottomPx] 加上导航栏护栏后的值，保证判定矩形与视觉窗口同源；
     * @param edgeBand 系统手势带 [left, top, right, bottom]（px）；全 0 = 不排除。
     */
    data class Config(
        val bounds: Rect,
        val rotation: Int,
        val hotWidthDp: Float,
        val hotHeightDp: Float,
        val hotInsetDp: Float,
        val hotBottomDp: Float,
        val density: Float,
        val triggerPx: Float,
        val tapTolerancePx: Float,
        val edgeBand: IntArray,
    )

    /** 分流结论。UI/窗口动作由调用方执行，本类不产生副作用。 */
    enum class Route {
        /** 无需动作 */
        NONE,

        /** 落在系统手势带内：主动不触发（区别于被系统 CANCEL） */
        EDGE_EXCLUDE,

        /** 左下角热区上滑达标 */
        TRIGGER_LEFT,

        /** 右下角热区上滑达标 */
        TRIGGER_RIGHT,

        /** 系统取消本次指针流（下层已被抢占），我们不做恢复 */
        CANCEL,
    }

    /** 计数字段仅供快照读取（同一 routing 线程写、快照线程读，@Volatile 足够） */
    @Volatile
    var hitCount = 0
        private set

    @Volatile
    var edgeExcludeCount = 0
        private set

    @Volatile
    var cancelCount = 0
        private set

    @Volatile
    var cancelAfterExclude = 0
        private set

    private var cfg = config

    private var arbiter = buildArbiter(cfg)

    private var excludedThisGesture = false

    /** 配置变化（Prefs / 旋转 / 尺寸）时重建几何；返回是否真的变了 */
    fun updateConfig(next: Config): Boolean {
        val changed = next.bounds != cfg.bounds || next.rotation != cfg.rotation ||
            next.hotWidthDp != cfg.hotWidthDp || next.hotHeightDp != cfg.hotHeightDp ||
            next.hotInsetDp != cfg.hotInsetDp || next.hotBottomDp != cfg.hotBottomDp ||
            next.tapTolerancePx != cfg.tapTolerancePx || next.triggerPx != cfg.triggerPx
        cfg = next
        if (changed) arbiter = buildArbiter(next)
        return changed
    }

    fun snapshotOfConfig(): Config = cfg

    /**
     * 喂入一个触摸事件。
     *
     * @param action MotionEvent action（0/1/2/3）
     * @param x/y 显示逻辑坐标（monitorInput 的 rawX/rawY 已是此域）
     */
    fun feed(action: Int, x: Float, y: Float, eventTime: Long): Route {
        val masked = action and 0xff

        if (masked == GestureArbiter.ACTION_DOWN) {
            excludedThisGesture = inEdgeBand(x, y, cfg)
            if (excludedThisGesture) {
                edgeExcludeCount++
                return Route.EDGE_EXCLUDE
            }
            arbiter.feed(masked, x, y, eventTime)
            return Route.NONE
        }

        if (masked == GestureArbiter.ACTION_CANCEL) {
            val wasExcluded = excludedThisGesture
            excludedThisGesture = false
            arbiter.feed(masked, x, y, eventTime)
            cancelCount++
            if (wasExcluded) cancelAfterExclude++
            return Route.CANCEL
        }

        if (excludedThisGesture) {
            // 手势起始于系统手势带：本手势全程不参与呼出判定
            if (masked == GestureArbiter.ACTION_UP) excludedThisGesture = false
            return Route.EDGE_EXCLUDE
        }

        val result = arbiter.feed(masked, x, y, eventTime)
        if (result != GestureArbiter.Arbitration.EDGE_SWIPE_TRIGGER) return Route.NONE

        // 56 号：二次几何校验由「角区矩形」改为「四分之一椭圆」——
        // 矩形把远离屏幕角的对角区也算作热区，而那一带正是系统返回手势带与上滑回桌面带
        // 重叠最多的地方；椭圆 u²+v²≤1 只切掉该对角冗余，底边/侧边极值不变。
        val down = arbiter.lastDownPoint ?: return Route.NONE
        val fromLeft = down.first <= cfg.bounds.left + cfg.bounds.width() / 2f
        val inCorner = DisplayGate.sectorContains(
            x = down.first,
            y = down.second,
            bounds = cfg.bounds.toWindowRect(),
            insetPx = (cfg.hotInsetDp * cfg.density).toInt(),
            bottomPx = (cfg.hotBottomDp * cfg.density).toInt(),
            bottomExtentPx = (cfg.hotWidthDp * cfg.density).toInt(),
            edgeExtentPx = (cfg.hotHeightDp * cfg.density).toInt(),
            left = fromLeft,
        )
        if (!inCorner) return Route.NONE
        hitCount++
        return if (fromLeft) Route.TRIGGER_LEFT else Route.TRIGGER_RIGHT
    }

    /** 该点是否落在系统手势带内（只认左右缘与底缘，顶部状态栏不参与） */
    private fun inEdgeBand(x: Float, y: Float, c: Config): Boolean {
        val (l, _, r, b) = c.edgeBand
        if (l <= 0 && r <= 0 && b <= 0) return false
        val inLeft = l > 0 && x <= c.bounds.left + l
        val inRight = r > 0 && x >= c.bounds.right - r
        val inBottom = b > 0 && y >= c.bounds.bottom - b
        return inLeft || inRight || inBottom
    }

    private fun buildArbiter(c: Config): GestureArbiter {
        val hPx = c.hotHeightDp * c.density
        val bottomPx = c.hotBottomDp * c.density
        val top = c.bounds.bottom - bottomPx - hPx
        return GestureArbiter(
            GestureArbiter.Config(
                zoneLeft = c.bounds.left.toFloat(),
                zoneTop = top,
                zoneRight = c.bounds.right.toFloat(),
                zoneBottom = c.bounds.bottom - bottomPx,
                triggerDistancePx = c.triggerPx,
                clickTolerancePx = c.tapTolerancePx,
            ),
        )
    }

    /** 采样点速度（px/s），供 §5 甩动语义判定复用；纯函数，独立可测 */
    fun displacementVelocity(
        startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long,
    ): Float {
        if (durationMs <= 0L) return 0f
        return hypot(endX - startX, endY - startY) / (durationMs / 1000f)
    }
}
