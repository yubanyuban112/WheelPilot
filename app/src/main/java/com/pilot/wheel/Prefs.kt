package com.pilot.wheel

import android.content.Context
import android.graphics.Bitmap

/** 轮盘上的一个条目：普通应用或快捷工具（toolId 非空） */
data class PilotApp(
    val pkg: String,
    val label: String,
    val icon: Bitmap?,
    val toolId: String? = null,
)

/**
 * 配置存储。应用列表和各项可调参数都放这里。
 * 不存图标，图标每次现取，避免缓存和系统不一致。
 */
object Prefs {

    private const val FILE = "pilot"
    private const val KEY_APPS = "apps"
    private const val KEY_LEFT = "left_enabled"
    private const val KEY_RIGHT = "right_enabled"
    private const val KEY_RADIUS = "ring_radius"
    private const val KEY_ICON = "icon_size"
    private const val KEY_HAPTIC = "haptic"
    private const val KEY_DISMISS = "dismiss_outside"
    private const val KEY_ANGLE = "corner_angle"
    private const val KEY_HOT_W = "hot_w"
    private const val KEY_HOT_H = "hot_h"
    private const val KEY_HOT_INSET = "hot_inset"
    private const val KEY_HANDLE = "corner_handle"
    private const val KEY_HIDE_RECENTS = "hide_recents"
    private const val KEY_RECENT = "recent"
    private const val KEY_TOOLS = "tools_enabled"
    private const val KEY_TOOL_ORDER = "tool_order"
    /** 工具列表一次性补齐标记（v3.0 新增云闪付两项） */
    private const val KEY_TOOLS_SEEDED = "tools_seeded_v3"
    private const val KEY_APPS_READY = "apps_ready"
    private const val KEY_SHOW_SYSTEM = "show_system"
    private const val KEY_CLOSE_MODE = "close_mode"
    private const val KEY_CLOSE_X = "close_x"
    private const val KEY_CLOSE_DURATION = "close_duration"
    private const val KEY_CLOSE_DISTANCE = "close_distance"
    private const val KEY_CLOSE_BOTTOM = "close_bottom"
    private const val KEY_PAUSE_LANDSCAPE = "pause_landscape"
    private const val KEY_PAUSE_GAME = "pause_game"
    private const val KEY_PAUSE_KEYBOARD = "pause_keyboard"
    private const val KEY_VERBOSE_LOG = "verbose_log"
    private const val KEY_WECHAT_NOTIFY = "wechat_notify"
    private const val KEY_SHOW_WHEEL_HINT = "show_wheel_hint"
    /** 一次性迁移标记：旧默认高度/内缩迁移到新默认值 */
    private const val KEY_CORNER_ALIGNED = "corner_aligned_v24"

    /** 最多放几个，太多轮盘排不开 */
    const val MAX_APPS = 8

    /** 同类小窗应用「更多」面板：固定栏 6 个、最近使用 8 个 */
    const val MAX_PINNED = 6
    const val MAX_RECENT = 8

    // 默认值对齐同类小窗应用 C：半径 199dp、图标 50dp、张角 90°、
    // 触发区 48×96dp、边缘内缩 0dp（D 旧版默认 72dp/10dp，见 migrateLegacyCornerDefaults）
    const val DEFAULT_RADIUS = 199f
    const val DEFAULT_ICON = 50f
    const val DEFAULT_ANGLE = 90f
    const val DEFAULT_HOT_W = 48f
    const val DEFAULT_HOT_H = 96f
    const val DEFAULT_HOT_INSET = 0f

    private fun sp(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * 旧版 D 默认触发区 72dp 高 / 10dp 内缩，与 C 的 96dp / 0dp 不一致。
     * 只在仍为旧默认值时迁移，用户手动改过的数值原样保留。
     */
    fun migrateLegacyCornerDefaults(context: Context) {
        val prefs = sp(context)
        if (prefs.getBoolean(KEY_CORNER_ALIGNED, false)) return
        val editor = prefs.edit()
        if (prefs.contains(KEY_HOT_H) && prefs.getFloat(KEY_HOT_H, 72f) == 72f) {
            editor.putFloat(KEY_HOT_H, DEFAULT_HOT_H)
        }
        if (prefs.contains(KEY_HOT_INSET) && prefs.getFloat(KEY_HOT_INSET, 10f) == 10f) {
            editor.putFloat(KEY_HOT_INSET, DEFAULT_HOT_INSET)
        }
        editor.putBoolean(KEY_CORNER_ALIGNED, true).apply()
    }

    fun load(context: Context): List<String> {
        val raw = sp(context).getString(KEY_APPS, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        // 对齐同类小窗应用 pinned()：过滤空串 + 去重 + 上限，顺序即轮盘顺序
        val out = LinkedHashSet<String>()
        for (seg in raw.split('\n')) {
            val s = seg.trim()
            if (s.isNotEmpty()) out.add(s)
            if (out.size >= MAX_APPS) break
        }
        return out.toList()
    }

    fun save(context: Context, packages: List<String>) {
        // 对齐同类小窗应用 setPinned()：过滤空串 + 去重 + 上限，写回后标记已初始化
        val out = LinkedHashSet<String>()
        for (pkg in packages) {
            val s = pkg.trim()
            if (s.isNotEmpty()) out.add(s)
            if (out.size >= MAX_APPS) break
        }
        sp(context).edit()
            .putString(KEY_APPS, out.joinToString("\n"))
            .putBoolean(KEY_APPS_READY, true)
            .apply()
    }

    /** 工具下线后，把轮盘里已不存在的 tool: 项清掉，避免白占位置 */
    fun pruneToolKeys(context: Context) {
        val current = load(context)
        val valid = QuickTools.ALL.map { it.id }.toSet()
        val kept = current.filter { !QuickTools.isToolKey(it) || QuickTools.toolIdOf(it) in valid }
        if (kept.size != current.size) save(context, kept)
    }

    /** 应用页首次进入时是否已做过默认填充（对齐 pinned_ready） */
    fun appsInitialized(context: Context): Boolean =
        sp(context).getBoolean(KEY_APPS_READY, false)

    fun setAppsInitialized(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_APPS_READY, value).apply()
    }

    /** 应用页「显示系统应用」开关，默认关闭 */
    fun showSystemApps(context: Context): Boolean =
        sp(context).getBoolean(KEY_SHOW_SYSTEM, false)

    fun setShowSystemApps(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_SHOW_SYSTEM, value).apply()
    }

    fun leftEnabled(context: Context): Boolean = sp(context).getBoolean(KEY_LEFT, true)
    fun rightEnabled(context: Context): Boolean = sp(context).getBoolean(KEY_RIGHT, true)

    fun setLeftEnabled(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_LEFT, value).apply()
    }

    fun setRightEnabled(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_RIGHT, value).apply()
    }

    fun radiusDp(context: Context): Float =
        sp(context).getFloat(KEY_RADIUS, DEFAULT_RADIUS)

    fun setRadiusDp(context: Context, value: Float) {
        sp(context).edit().putFloat(KEY_RADIUS, value.coerceIn(120f, 300f)).apply()
    }

    fun iconDp(context: Context): Float {
        val stored = sp(context).getFloat(KEY_ICON, DEFAULT_ICON)
        // 早期版本可能写入过过小的值，这里兜个底
        return if (stored < 40f) DEFAULT_ICON else stored
    }

    fun setIconDp(context: Context, value: Float) {
        sp(context).edit().putFloat(KEY_ICON, value.coerceIn(36f, 72f)).apply()
    }

    fun haptic(context: Context): Boolean = sp(context).getBoolean(KEY_HAPTIC, true)
    fun setHaptic(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_HAPTIC, value).apply()
    }

    fun dismissOutside(context: Context): Boolean = sp(context).getBoolean(KEY_DISMISS, true)
    fun setDismissOutside(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_DISMISS, value).apply()
    }

    /** 轮盘张角，度数。图标沿这个弧度排开 */
    fun angle(context: Context): Float = sp(context).getFloat(KEY_ANGLE, DEFAULT_ANGLE)
    fun setAngle(context: Context, value: Float) {
        sp(context).edit().putFloat(KEY_ANGLE, value.coerceIn(45f, 150f)).apply()
    }

    /** 热区宽高与横向内缩，单位 dp */
    fun hotWidth(context: Context): Float = sp(context).getFloat(KEY_HOT_W, DEFAULT_HOT_W)
    fun setHotWidth(context: Context, value: Float) {
        sp(context).edit().putFloat(KEY_HOT_W, value.coerceIn(24f, 120f)).apply()
    }

    fun hotHeight(context: Context): Float = sp(context).getFloat(KEY_HOT_H, DEFAULT_HOT_H)
    fun setHotHeight(context: Context, value: Float) {
        sp(context).edit().putFloat(KEY_HOT_H, value.coerceIn(16f, 200f)).apply()
    }

    fun hotInset(context: Context): Float = sp(context).getFloat(KEY_HOT_INSET, DEFAULT_HOT_INSET)
    fun setHotInset(context: Context, value: Float) {
        sp(context).edit().putFloat(KEY_HOT_INSET, value.coerceIn(0f, 60f)).apply()
    }

    /** 角落里显示一个小把手，点一下直接呼出（备选呼出方式，默认开） */
    fun cornerHandle(context: Context): Boolean = sp(context).getBoolean(KEY_HANDLE, true)
    fun setCornerHandle(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_HANDLE, value).apply()
    }

    /** 轮盘启动应用时是否尝试用荣耀小窗（自由窗口）打开 */
    private const val KEY_FREE_WINDOW = "free_window"
    fun freeWindow(context: Context): Boolean = sp(context).getBoolean(KEY_FREE_WINDOW, true)
    fun setFreeWindow(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_FREE_WINDOW, value).apply()
    }

    /**
     * 角落点击穿透（同步同类小窗应用 tap_through）：
     * 开启后，热区上的普通点击/非上滑手势临时透传给下层应用；
     * 上滑和把手轻点仍然照常呼出轮盘。默认关闭，避免误触。
     */
    private const val KEY_PASS_THROUGH = "hotspot_pass_through"
    fun hotspotPassThrough(context: Context): Boolean =
        sp(context).getBoolean(KEY_PASS_THROUGH, false)

    fun setHotspotPassThrough(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_PASS_THROUGH, value).apply()
    }

    /**
     * 启动方式（四档）：
     * auto     = 先厂商小窗(100) → AOSP 自由窗(5) → 全屏；对应旧版 free
     * vendor   = 只用厂商小窗(100)，不支持时全屏
     * freeform = 只用 AOSP 自由窗(5)，不支持时全屏
     * full     = 始终全屏
     * 旧值 "free" 读取时映射为 auto，避免升级后设置丢失。
     */
    private const val KEY_LAUNCH_MODE = "launch_mode"
    const val LAUNCH_AUTO = "auto"
    const val LAUNCH_VENDOR = "vendor"
    const val LAUNCH_FREEFORM = "freeform"
    const val LAUNCH_FULL = "full"
    @Deprecated("旧值，读取时映射为 LAUNCH_AUTO")
    const val LAUNCH_FREE = "free"

    fun launchMode(context: Context): String {
        val raw = sp(context).getString(KEY_LAUNCH_MODE, LAUNCH_AUTO) ?: LAUNCH_AUTO
        return if (raw == LAUNCH_FREE) LAUNCH_AUTO else raw
    }

    fun setLaunchMode(context: Context, value: String) {
        sp(context).edit().putString(KEY_LAUNCH_MODE, value).apply()
    }

    // -----------------------------------------------------------------------
    // 关闭方式（移植自同类小窗应用 OutsideBlocker）
    // -----------------------------------------------------------------------

    const val CLOSE_TAP = 0
    const val CLOSE_SWIPE = 1
    const val CLOSE_SMART = 2

    fun closeMode(context: Context): Int =
        sp(context).getInt(KEY_CLOSE_MODE, CLOSE_TAP)

    fun setCloseMode(context: Context, value: Int) {
        sp(context).edit().putInt(KEY_CLOSE_MODE, value.coerceIn(CLOSE_TAP, CLOSE_SMART)).apply()
    }

    /** 上滑关闭的落点 X，屏幕宽度百分比 0–100，默认居中 */
    fun closeX(context: Context): Float =
        sp(context).getFloat(KEY_CLOSE_X, 50f)

    fun setCloseX(context: Context, value: Float) {
        sp(context).edit().putFloat(KEY_CLOSE_X, value.coerceIn(0f, 100f)).apply()
    }

    /** 上滑持续时间上限（毫秒）。越短越像手势，太长会被当成拖拽 */
    fun closeDuration(context: Context): Long =
        sp(context).getLong(KEY_CLOSE_DURATION, 450L)

    fun setCloseDuration(context: Context, value: Long) {
        sp(context).edit().putLong(KEY_CLOSE_DURATION, value.coerceIn(120L, 900L)).apply()
    }

    /** 上滑最小距离 dp */
    fun closeDistance(context: Context): Float =
        sp(context).getFloat(KEY_CLOSE_DISTANCE, 60f)

    fun setCloseDistance(context: Context, value: Float) {
        sp(context).edit().putFloat(KEY_CLOSE_DISTANCE, value.coerceIn(24f, 200f)).apply()
    }

    /** 上滑起点离小窗底边的距离 dp（同类小窗应用的"关闭落点"） */
    fun closeFromBottom(context: Context): Float =
        sp(context).getFloat(KEY_CLOSE_BOTTOM, 24f)

    fun setCloseFromBottom(context: Context, value: Float) {
        sp(context).edit().putFloat(KEY_CLOSE_BOTTOM, value.coerceIn(0f, 120f)).apply()
    }

    // -----------------------------------------------------------------------
    // 场景暂停 / 诊断 / 微信消息
    // -----------------------------------------------------------------------

    fun pauseLandscape(context: Context): Boolean =
        sp(context).getBoolean(KEY_PAUSE_LANDSCAPE, true)

    fun setPauseLandscape(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_PAUSE_LANDSCAPE, value).apply()
    }

    fun pauseGame(context: Context): Boolean =
        sp(context).getBoolean(KEY_PAUSE_GAME, true)

    fun setPauseGame(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_PAUSE_GAME, value).apply()
    }

    fun pauseKeyboard(context: Context): Boolean =
        sp(context).getBoolean(KEY_PAUSE_KEYBOARD, true)

    fun setPauseKeyboard(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_PAUSE_KEYBOARD, value).apply()
    }

    fun verboseLog(context: Context): Boolean =
        sp(context).getBoolean(KEY_VERBOSE_LOG, false)

    fun setVerboseLog(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_VERBOSE_LOG, value).apply()
    }

    fun wechatNotify(context: Context): Boolean =
        sp(context).getBoolean(KEY_WECHAT_NOTIFY, false)

    fun setWechatNotify(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_WECHAT_NOTIFY, value).apply()
    }

    fun showWheelHint(context: Context): Boolean =
        sp(context).getBoolean(KEY_SHOW_WHEEL_HINT, true)

    fun setShowWheelHint(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_SHOW_WHEEL_HINT, value).apply()
    }

    /** 不出现在最近任务列表 */
    fun hideFromRecents(context: Context): Boolean =
        sp(context).getBoolean(KEY_HIDE_RECENTS, false)

    fun setHideFromRecents(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_HIDE_RECENTS, value).apply()
    }

    // -----------------------------------------------------------------------
    // 「更多」面板数据：最近使用 / 工具页排序与开关
    // -----------------------------------------------------------------------

    fun recent(context: Context): List<String> {
        val raw = sp(context).getString(KEY_RECENT, "").orEmpty()
        return splitList(raw).take(MAX_RECENT)
    }

    fun noteRecent(context: Context, pkg: String) {
        val list = recent(context).toMutableList()
        list.remove(pkg)
        list.add(0, pkg)
        sp(context).edit().putString(KEY_RECENT, list.take(MAX_RECENT).joinToString("\n")).apply()
    }

    fun clearRecent(context: Context) {
        sp(context).edit().remove(KEY_RECENT).apply()
    }

    fun toolsEnabled(context: Context): List<String> {
        val sp = sp(context)
        val valid = QuickTools.ALL.map { it.id }
        val raw = sp.getString(KEY_TOOLS, "")?.takeIf { it.isNotBlank() }
            ?: return valid
        val saved = splitList(raw).filter { it in valid }
        // v3.0 新增云闪付两项：老配置一次性补齐，之后用户手动关掉就不再翻回来
        if (!sp.getBoolean(KEY_TOOLS_SEEDED, false)) {
            val merged = saved + valid.filter { it !in saved }
            sp.edit()
                .putString(KEY_TOOLS, merged.joinToString("\n"))
                .putBoolean(KEY_TOOLS_SEEDED, true)
                .apply()
            return merged
        }
        return saved
    }

    fun setToolsEnabled(context: Context, ids: List<String>) {
        sp(context).edit().putString(KEY_TOOLS, ids.joinToString("\n")).apply()
    }

    fun toolOrder(context: Context): List<String> {
        val raw = sp(context).getString(KEY_TOOL_ORDER, "").orEmpty()
        if (raw.isBlank()) return QuickTools.ALL.map { it.id }
        val ordered = splitList(raw).toMutableList()
        QuickTools.ALL.forEach { if (it.id !in ordered) ordered += it.id }
        return ordered
    }

    fun setToolOrder(context: Context, ids: List<String>) {
        sp(context).edit().putString(KEY_TOOL_ORDER, ids.joinToString("\n")).apply()
    }

    /** 工具入口校准结果：用户挑中的 Activity 类名，null 表示未校准 */
    fun toolComponent(context: Context, toolId: String): String? =
        sp(context).getString("tool_cls_$toolId", null)

    fun setToolComponent(context: Context, toolId: String, cls: String?) {
        val key = "tool_cls_$toolId"
        sp(context).edit().apply {
            if (cls == null) remove(key) else putString(key, cls)
        }.apply()
    }

    private fun splitList(raw: String): List<String> =
        raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    /** 无障碍服务用它监听配置变化，改动后即时重建热区 */
    fun register(
        context: Context,
        listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener,
    ) {
        sp(context).registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregister(
        context: Context,
        listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener,
    ) {
        sp(context).unregisterOnSharedPreferenceChangeListener(listener)
    }
}
