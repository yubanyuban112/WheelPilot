package com.pilot.wheel

import android.graphics.drawable.Drawable
import com.pilot.wheel.core.WindowRect

/**
 * 41B：freeform 小窗登记表。
 *
 * 记录当前由本应用以小窗形态拉起、还活着的 task（taskId / 包名 / bounds / 图标）。
 * 双窗共存判重（点已开的不再新开而是 refocus）、多窗错位计数、把手跟随最近操作
 * 窗口、收球恢复，全部以它为准。
 *
 * 读写都发生在主线程（无障碍服务的回调链上），synchronized 只是防抖：
 * 设置页 / shell 回调偶尔从别的线程探一下状态。
 */
object FreeformWindowRegistry {

    data class Entry(
        val taskId: Int,
        val pkg: String,
        val bounds: WindowRect,
        val icon: Drawable?,
    )

    private val entries = LinkedHashMap<Int, Entry>()

    @Synchronized
    fun record(taskId: Int, pkg: String, bounds: WindowRect, icon: Drawable?) {
        entries[taskId] = Entry(taskId, pkg, bounds, icon)
    }

    @Synchronized
    fun remove(taskId: Int) {
        entries.remove(taskId)
    }

    @Synchronized
    fun updateBounds(taskId: Int, bounds: WindowRect) {
        val old = entries[taskId] ?: return
        entries[taskId] = old.copy(bounds = bounds)
    }

    /** 最近记录的在前（把手跟最近操作窗口） */
    @Synchronized
    fun list(): List<Entry> = entries.values.toList().asReversed()

    @Synchronized
    fun findByPkg(pkg: String): Entry? = entries.values.lastOrNull { it.pkg == pkg }

    @Synchronized
    fun find(taskId: Int): Entry? = entries[taskId]

    @Synchronized
    fun clear() = entries.clear()
}
