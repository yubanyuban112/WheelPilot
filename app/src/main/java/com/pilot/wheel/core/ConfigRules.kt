package com.pilot.wheel.core

/**
 * 配置项的取值规则。写成纯函数是为了能在 JVM 单测里直接验证边界，
 * 不用起模拟器、也不用读 SharedPreferences。
 */
object ConfigRules {

    const val MIN_SLOTS = 6
    const val MAX_SLOTS = 8
    /** 双排模式槽位上限：双排最多 15 个 */
    const val MAX_SLOTS_DOUBLE = 15

    /** 槽位数夹到 [MIN_SLOTS]..上限（单排 [MAX_SLOTS]，双排 [MAX_SLOTS_DOUBLE]），非法输入退回对应上限 */
    fun normalizeSlots(requested: Int, doubleRow: Boolean = false): Int {
        val max = if (doubleRow) MAX_SLOTS_DOUBLE else MAX_SLOTS
        return if (requested in MIN_SLOTS..max) requested else max
    }

    /**
     * 热区离屏幕底边的距离（px）。
     *
     * 三个约束取最大值：用户设定、导航栏高度 + 8dp 安全边距、横向内缩。
     * 未取 max 时把用户值调到 0，热区会被压在导航栏下面，上滑直接被系统吃掉。
     */
    fun hotspotBottomPx(
        bottomDp: Float,
        density: Float,
        navBarPx: Int,
        edgeInsetPx: Int,
    ): Int {
        val guard = navBarPx + (8 * density).toInt()
        val user = (bottomDp * density).toInt()
        return maxOf(user, guard, edgeInsetPx)
    }
}
