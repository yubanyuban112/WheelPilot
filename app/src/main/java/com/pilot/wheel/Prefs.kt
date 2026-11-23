package com.pilot.wheel

import android.content.Context
import android.graphics.Bitmap
import com.pilot.wheel.core.BackupCodec

/** 轮盘上的一个条目：普通应用、快捷工具（toolId 非空）或微信消息临时入口 */
data class PilotApp(
    val pkg: String,
    val label: String,
    val icon: Bitmap?,
    val toolId: String? = null,
    /** 未读红点：微信消息入口专用，和普通项区分 */
    val badge: Boolean = false,
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
    /** 震动强度档位：0=轻 1=中(默认) 2=强 */
    private const val KEY_HAPTIC_STRENGTH = "haptic_strength"
    private const val KEY_DISMISS = "dismiss_outside"
    /** 75 号：点小窗外侧 → 直接关掉小窗（即用即走） */
    private const val KEY_CLOSE_OUTSIDE = "close_outside"
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
    private const val KEY_PAUSE_LANDSCAPE = "pause_landscape"
    private const val KEY_PAUSE_GAME = "pause_game"
    private const val KEY_PAUSE_KEYBOARD = "pause_keyboard"
    private const val KEY_VERBOSE_LOG = "verbose_log"
    private const val KEY_WECHAT_NOTIFY = "wechat_notify"
    private const val KEY_SHOW_WHEEL_HINT = "show_wheel_hint"
    /** 一次性迁移标记：旧默认高度/内缩迁移到新默认值 */
    private const val KEY_CORNER_ALIGNED = "corner_aligned_v24"
    /** 轮盘槽位数（单排 6–8，双排最多 15），默认 8 */
    private const val KEY_SLOTS = "slots"
    /** 菜单形态：双排/单排（默认单排） */
    private const val KEY_DOUBLE_ROW = "menu_double_row"
    /** 主页轮盘预览：可关闭（默认开） */
    private const val KEY_HOME_PREVIEW = "home_preview"
    /** 触发区离屏幕底边的高度，dp */
    private const val KEY_HOT_BOTTOM = "hot_bottom"
    private const val KEY_HOT_ORIENT = "hot_orient"
    /** 这些应用在前台时不呼出轮盘 */
    private const val KEY_BLACKLIST = "blacklist"

    /**
     * 「旁路触摸采集」开关：开启后热区视觉层常驻 FLAG_NOT_TOUCHABLE，
     * 触摸感知改由 Shizuku shell 旁路读 getevent 完成（只读不消费）。
     * 默认关闭 —— 旁路方案依赖 shell 读 /dev/input 的权限，部分 ROM 收紧后不可用，
     * 开启前会做运行时探测，探测失败自动降级回旧的无障碍路径。
     */
    private const val KEY_RAW_HOTSPOT = "raw_hotspot_enabled"

    /**
     * 维度5：无障碍 onMotionEvent 兜底层开关（默认开）。
     * 关闭后触摸感知退回纯 Shizuku 旁路（monitorInput/getevent），
     * 无障碍服务仅保留窗口类型持有与必要节点读取，不再介入任何触摸事件流。
     * 用途：敏感应用（支付/游戏）或系统手势异常时的降级路径。
     */
    private const val KEY_A11Y_FALLBACK = "a11y_fallback_enabled"

    fun a11yFallbackEnabled(context: Context): Boolean =
        sp(context).getBoolean(KEY_A11Y_FALLBACK, true)

    fun setA11yFallbackEnabled(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_A11Y_FALLBACK, value).apply()
    }

    /**
     * 53 号：采集通道优先级（逗号分隔，按序尝试）。
     * 默认 `monitorInput,evdev,a11y` —— 与改造前的硬编码降级顺序完全一致，
     * 因此不配置时对外语义零变化；需要「evdev 优先」的机型可自行改串。
     */
    private const val KEY_RAW_CHANNEL_PRIORITY = "raw_channel_priority"

    const val DEFAULT_RAW_CHANNEL_PRIORITY = "monitorInput,evdev,a11y"

    fun rawChannelPriority(context: Context): List<String> {
        val raw = sp(context).getString(KEY_RAW_CHANNEL_PRIORITY, DEFAULT_RAW_CHANNEL_PRIORITY)
            ?: DEFAULT_RAW_CHANNEL_PRIORITY
        val list = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return if (list.isEmpty()) DEFAULT_RAW_CHANNEL_PRIORITY.split(',') else list
    }

    fun setRawChannelPriority(context: Context, value: String) {
        sp(context).edit().putString(KEY_RAW_CHANNEL_PRIORITY, value).apply()
    }

    /**
     * 53 号：甩动序列的可回放速度上限（px/s）。
     * 超过即标记 REPLAY_SEMANTIC_LOST 并旁路放行 —— 因为 GestureDescription 只有相对时间轴，
     * 回放必然丢失惯性语义，宁可放弃也不伪装成正常触摸。<= 0 表示关闭该规则。
     */
    private const val KEY_REPLAY_VELOCITY_MAX = "replay_velocity_max_px_s"

    fun replayVelocityMaxPxS(context: Context): Float =
        sp(context).getFloat(KEY_REPLAY_VELOCITY_MAX, 1500f)

    fun setReplayVelocityMaxPxS(context: Context, value: Float) {
        sp(context).edit().putFloat(KEY_REPLAY_VELOCITY_MAX, value).apply()
    }

    /** 53 号：CHANNEL_SNAPSHOT 周期（秒）；<= 0 关闭周期快照 */
    private const val KEY_SNAPSHOT_PERIOD = "snapshot_period_s"

    fun snapshotPeriodS(context: Context): Int = sp(context).getInt(KEY_SNAPSHOT_PERIOD, 45)

    fun setSnapshotPeriodS(context: Context, value: Int) {
        sp(context).edit().putInt(KEY_SNAPSHOT_PERIOD, value).apply()
    }

    /** 无障碍 onMotionEvent 探测日志开关（探测项 3，纯诊断，默认关避免刷屏） */
    private const val KEY_PROBE_MOTION_EVENT_LOG = "probe_motion_event_log"

    /** 主题（液态玻璃/新拟物/经典拟物），见 UiTheme */
    private const val KEY_UI_THEME = "ui_theme"

    // 轻量打开（7 个场景下用浮窗打开应用）
    private const val KEY_LO_ENABLED = "lo_enabled"
    private const val KEY_LO_SCENE_PREFIX = "lo_scene_"
    private const val KEY_LO_WHEEL_ONLY = "lo_wheel_only"
    /** 应用跳转也允许重投 Intent（默认关，见 lightOpenJumpAggressive） */
    private const val KEY_LO_JUMP_AGGRESSIVE = "lo_jump_aggressive"
    private const val KEY_LO_STATS = "lo_stats"

    /** 槽位数默认值；轮盘弧长有限，超过 8 个图标会沿弧线压在一起 */
    const val DEFAULT_SLOTS = com.pilot.wheel.core.ConfigRules.MAX_SLOTS
    /** 槽位数下限：少于 6 个时弧线空得厉害，不如关掉一边热区 */
    const val MIN_SLOTS = com.pilot.wheel.core.ConfigRules.MIN_SLOTS

    /** 触发区离底高度默认值，dp。与旧版硬编码的 56dp 一致 */
    const val DEFAULT_HOT_BOTTOM = 56f

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
        val limit = slots(context)
        // 对齐同类小窗应用 pinned()：过滤空串 + 去重 + 上限，顺序即轮盘顺序
        val out = LinkedHashSet<String>()
        for (seg in raw.split('\n')) {
            val s = seg.trim()
            if (s.isNotEmpty()) out.add(s)
            if (out.size >= limit) break
        }
        return out.toList()
    }

    fun save(context: Context, packages: List<String>) {
        // 对齐同类小窗应用 setPinned()：过滤空串 + 去重 + 上限，写回后标记已初始化
        val limit = slots(context)
        val out = LinkedHashSet<String>()
        for (pkg in packages) {
            val s = pkg.trim()
            if (s.isNotEmpty()) out.add(s)
            if (out.size >= limit) break
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

    /** 震动强度档位：0=轻、1=中（默认）、2=强 */
    const val HAPTIC_LIGHT = 0
    const val HAPTIC_MEDIUM = 1
    const val HAPTIC_STRONG = 2

    fun hapticStrength(context: Context): Int =
        sp(context).getInt(KEY_HAPTIC_STRENGTH, HAPTIC_MEDIUM).coerceIn(0, 2)

    fun setHapticStrength(context: Context, value: Int) {
        sp(context).edit().putInt(KEY_HAPTIC_STRENGTH, value.coerceIn(0, 2)).apply()
    }

    fun dismissOutside(context: Context): Boolean = sp(context).getBoolean(KEY_DISMISS, true)
    fun setDismissOutside(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_DISMISS, value).apply()
    }

    /**
     * 75 号：点在小窗矩形之外 → 直接关闭这个小窗（「即用即走」）。
     *
     * 与 [dismissOutside] 不同：那条只管轮盘 overlay（点空白处收起轮盘），
     * 小窗本身是别的应用的 task，系统不会替你关。默认关闭，避免误触把用户
     * 正在看的窗口吞掉。
     */
    /**
     * 75 号（需求 3）：点在小窗外面就关掉小窗（「即用即走」）。
     *
     * **默认开** —— 这是用户明确要的行为，不是可选项；留一个开关是为了让人能关掉。
     */
    fun closeOnOutside(context: Context): Boolean =
        sp(context).getBoolean(KEY_CLOSE_OUTSIDE, true)

    fun setCloseOnOutside(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_CLOSE_OUTSIDE, value).apply()
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

    /**
     * 触发区离屏幕底边的高度，dp。
     * 旧版这里写死 56dp（见 PilotService.cornerBottomPx），调到更大可以把热区抬到
     * 拇指自然落点，代价是离导航栏更远、单手上滑行程变长。
     */
    fun hotBottom(context: Context): Float =
        sp(context).getFloat(KEY_HOT_BOTTOM, DEFAULT_HOT_BOTTOM)

    fun setHotBottom(context: Context, value: Float) {
        sp(context).edit().putFloat(KEY_HOT_BOTTOM, value.coerceIn(24f, 240f)).apply()
    }

    /**
     * 59 号：热区方向生效范围（[com.pilot.wheel.core.HotspotScope]）。
     * 默认「竖屏与横屏都生效」，与引入前行为一致。
     */
    fun hotOrientation(context: Context): Int = com.pilot.wheel.core.HotspotScope.normalize(
        sp(context).getInt(KEY_HOT_ORIENT, com.pilot.wheel.core.HotspotScope.BOTH),
    )

    fun setHotOrientation(context: Context, mode: Int) {
        sp(context).edit()
            .putInt(KEY_HOT_ORIENT, com.pilot.wheel.core.HotspotScope.normalize(mode))
            .apply()
    }

    /** 轮盘槽位数：默认 8，可降到 6 让图标间距更大；双排模式下最多 15 */
    fun slots(context: Context): Int =
        com.pilot.wheel.core.ConfigRules.normalizeSlots(
            sp(context).getInt(KEY_SLOTS, DEFAULT_SLOTS),
            doubleRow(context),
        )

    fun setSlots(context: Context, value: Int) {
        sp(context).edit()
            .putInt(
                KEY_SLOTS,
                com.pilot.wheel.core.ConfigRules.normalizeSlots(value, doubleRow(context)),
            )
            .apply()
    }

    /** 菜单形态是否为双排（默认单排） */
    fun doubleRow(context: Context): Boolean = sp(context).getBoolean(KEY_DOUBLE_ROW, false)

    fun setDoubleRow(context: Context, value: Boolean) {
        val prefs = sp(context)
        // 切回单排时把已存的槽位数重新夹到 8 以内，避免 15 槽残留
        val stored = prefs.getInt(KEY_SLOTS, DEFAULT_SLOTS)
        prefs.edit()
            .putBoolean(KEY_DOUBLE_ROW, value)
            .putInt(KEY_SLOTS, com.pilot.wheel.core.ConfigRules.normalizeSlots(stored, value))
            .apply()
    }

    /** 主页轮盘预览是否显示（默认开，可关闭） */
    fun showHomePreview(context: Context): Boolean = sp(context).getBoolean(KEY_HOME_PREVIEW, true)

    fun setShowHomePreview(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_HOME_PREVIEW, value).apply()
    }

    /** 呼出黑名单：这些包名在前台时不弹轮盘（借鉴同类手势工具的「应用黑名单」） */
    fun blacklist(context: Context): Set<String> =
        splitList(sp(context).getString(KEY_BLACKLIST, "").orEmpty()).toSet()

    fun setBlacklist(context: Context, packages: Collection<String>) {
        sp(context).edit().putString(KEY_BLACKLIST, packages.joinToString("\n")).apply()
    }

    fun isBlacklisted(context: Context, pkg: String?): Boolean =
        pkg != null && pkg in blacklist(context)

    /** 界面主题，默认经典拟物（v2.8.0 以来的原有实现） */
    fun uiTheme(context: Context): UiTheme =
        runCatching {
            // 64 号：NEUMORPHISM 已并入 CLASSIC，旧值 valueOf 会抛异常 → 自动落回经典拟物
            UiTheme.valueOf(sp(context).getString(KEY_UI_THEME, UiTheme.CLASSIC.name).orEmpty())
        }.getOrDefault(UiTheme.CLASSIC)

    fun setUiTheme(context: Context, theme: UiTheme) {
        sp(context).edit().putString(KEY_UI_THEME, theme.name).apply()
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

    /** 旁路触摸采集开关（默认开；热区层恒常驻不可触摸，感知走 monitorInput 旁路） */
    fun rawHotspotEnabled(context: Context): Boolean =
        sp(context).getBoolean(KEY_RAW_HOTSPOT, true)

    fun setRawHotspotEnabled(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_RAW_HOTSPOT, value).apply()
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

    /** 无障碍 onMotionEvent 探测日志开关（探测项 3，纯诊断） */
    fun probeMotionEventLog(context: Context): Boolean =
        sp(context).getBoolean(KEY_PROBE_MOTION_EVENT_LOG, false)

    fun setProbeMotionEventLog(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_PROBE_MOTION_EVENT_LOG, value).apply()
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
        val prefs = sp(context)
        val valid = QuickTools.ALL.map { it.id }
        val raw = prefs.getString(KEY_TOOLS, "")?.takeIf { it.isNotBlank() }
        val saved = if (raw == null) {
            valid.toMutableList()
        } else {
            splitList(raw).filter { it in valid }.toMutableList()
        }

        val editor = prefs.edit()
        var dirty = false
        // v3.0 新增云闪付两项：老配置一次性补齐，之后用户手动关掉就不再翻回来
        if (!prefs.getBoolean(KEY_TOOLS_SEEDED, false)) {
            valid.forEach { if (it !in saved) saved.add(it) }
            editor.putBoolean(KEY_TOOLS_SEEDED, true)
            dirty = true
        }
        if (dirty) {
            editor.putString(KEY_TOOLS, saved.joinToString("\n"))
            editor.apply()
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

    // -----------------------------------------------------------------------
    // 轻量打开：7 个场景开关 + 生效范围 + 统计
    // -----------------------------------------------------------------------

    /**
     * 默认开启的场景。
     *
     * 通知两档与应用跳转的信号最可靠，默认开；控制中心没有直接信号（只能靠面板展开时间窗
     * 推断），复制链接受 Android 10 起的剪贴板读取限制，两者默认关，由用户自行打开。
     */
    private val DEFAULT_LIGHT_OPEN_SCENES = setOf(
        com.pilot.wheel.core.LightOpenScene.NOTIF_HEADS_UP,
        com.pilot.wheel.core.LightOpenScene.APP_JUMP,
        com.pilot.wheel.core.LightOpenScene.AUTH,
    )

    fun lightOpenEnabled(context: Context): Boolean =
        sp(context).getBoolean(KEY_LO_ENABLED, false)

    fun setLightOpenEnabled(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_LO_ENABLED, value).apply()
    }

    /**
     * 单个场景开关；未写入过时按 [DEFAULT_LIGHT_OPEN_SCENES] 给默认值。
     * 共用判据的一组（跳转/授权/分享）读的是整组的「或」：任意一个开着就回 true，
     * 界面上三行才会始终显示同一个状态，不会出现「显示关闭却在生效」。
     */
    fun lightOpenScene(context: Context, scene: com.pilot.wheel.core.LightOpenScene): Boolean =
        com.pilot.wheel.core.LightOpenScene.linkedTo(scene).any { rawLightOpenScene(context, it) }

    /** 绕开联动、直接读单个键，只给自己用 */
    private fun rawLightOpenScene(
        context: Context,
        scene: com.pilot.wheel.core.LightOpenScene,
    ): Boolean = sp(context).getBoolean(
        KEY_LO_SCENE_PREFIX + scene.key,
        scene in DEFAULT_LIGHT_OPEN_SCENES,
    )

    /** 写的时候三合一：共用判据的三个开关一起变，不留下互相矛盾的状态 */
    fun setLightOpenScene(
        context: Context,
        scene: com.pilot.wheel.core.LightOpenScene,
        value: Boolean,
    ) {
        val edit = sp(context).edit()
        com.pilot.wheel.core.LightOpenScene.linkedTo(scene).forEach { linked ->
            edit.putBoolean(KEY_LO_SCENE_PREFIX + linked.key, value)
        }
        edit.apply()
    }

    fun lightOpenScenes(context: Context): Set<com.pilot.wheel.core.LightOpenScene> =
        com.pilot.wheel.core.LightOpenScene.entries
            .filter { lightOpenScene(context, it) }
            .toSet()

    /** 生效范围：true = 只对轮盘里的应用生效（默认，防止误转支付/游戏） */
    fun lightOpenWheelOnly(context: Context): Boolean =
        sp(context).getBoolean(KEY_LO_WHEEL_ONLY, true)

    fun setLightOpenWheelOnly(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_LO_WHEEL_ONLY, value).apply()
    }

    /**
     * 应用跳转也强制转窗（默认关）。
     *
     * 跳转与登录授权、分享在系统层面同形，无法区分。重投 Intent 能把跳转变成浮窗，
     * 但会中断分享/授权的结果回传，所以默认只做「改几何」，失败就保持全屏。
     */
    fun lightOpenJumpAggressive(context: Context): Boolean =
        sp(context).getBoolean(KEY_LO_JUMP_AGGRESSIVE, false)

    fun setLightOpenJumpAggressive(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_LO_JUMP_AGGRESSIVE, value).apply()
    }

    fun lightOpenStats(context: Context): Map<String, Int> =
        com.pilot.wheel.core.LightOpenStatsCodec.decode(
            sp(context).getString(KEY_LO_STATS, "").orEmpty(),
        )

    fun setLightOpenStats(context: Context, raw: String) {
        sp(context).edit().putString(KEY_LO_STATS, raw).apply()
    }

    // -----------------------------------------------------------------------
    // 菜单形态 / 弹幕
    // -----------------------------------------------------------------------

    /** 快捷菜单样式：arc = 轮盘扇形，vertical = 竖列（C2） */
    private const val KEY_QUICK_MENU_STYLE = "quick_menu_style"
    /** 微信/QQ 弹幕（B4） */
    private const val KEY_DANMAKU = "danmaku"

    const val MENU_STYLE_ARC = "arc"
    const val MENU_STYLE_VERTICAL = "vertical"

    fun quickMenuStyle(context: Context): String =
        sp(context).getString(KEY_QUICK_MENU_STYLE, MENU_STYLE_ARC) ?: MENU_STYLE_ARC

    fun setQuickMenuStyle(context: Context, value: String) {
        sp(context).edit().putString(KEY_QUICK_MENU_STYLE, value).apply()
    }

    fun danmaku(context: Context): Boolean = sp(context).getBoolean(KEY_DANMAKU, false)
    fun setDanmaku(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_DANMAKU, value).apply()
    }

    // -----------------------------------------------------------------------
    // 配置备份：原始条目读写（类型由存储值决定）
    // -----------------------------------------------------------------------

    /** 备份导出用：全部条目原样取出 */
    fun rawEntries(context: Context): Map<String, Any> =
        sp(context).all.mapNotNull { (key, value) -> value?.let { key to it } }.toMap()

    /** 备份恢复用：逐项覆盖，备份里没有的键保持原值（避免旧备份抹掉新版新增配置） */
    fun restoreEntries(context: Context, entries: List<BackupCodec.Entry>) {
        val editor = sp(context).edit()
        entries.forEach { entry ->
            when (entry.type) {
                BackupCodec.TYPE_STRING -> editor.putString(entry.key, entry.value)
                BackupCodec.TYPE_BOOLEAN -> editor.putBoolean(entry.key, entry.value == "true")
                BackupCodec.TYPE_INT -> editor.putInt(entry.key, entry.value.toInt())
                BackupCodec.TYPE_LONG -> editor.putLong(entry.key, entry.value.toLong())
                BackupCodec.TYPE_FLOAT -> editor.putFloat(entry.key, entry.value.toFloat())
            }
        }
        editor.apply()
    }

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
