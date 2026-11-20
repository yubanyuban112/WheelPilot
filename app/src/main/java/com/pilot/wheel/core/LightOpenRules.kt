package com.pilot.wheel.core

/**
 * 「轻量打开」的场景（64 号：删除通知中心/控制中心/应用分享/复制链接，只留三类）。
 *
 * 注意 [AUTH] 没有独立的系统信号：它与 [APP_JUMP] 一样都是
 * "某个应用被另一个应用/系统拉起"，第三方没有 API 能区分是哪一种。
 * 因此两者共用同一条判据，任一开关打开即生效（见 [LightOpenRules.decide]）。
 */
enum class LightOpenScene(val key: String, val label: String, val hint: String) {
    NOTIF_HEADS_UP("notif_heads_up", "通知横幅", "点屏幕顶部弹出的横幅"),
    APP_JUMP("app_jump", "应用跳转", "在一个应用里点开另一个应用"),
    AUTH("auth", "登录授权", "第三方登录或授权后跳回"),

    ;

    companion object {
        /**
         * 共用一条判据的场景组。
         * 登录授权在系统层面就是普通 `startActivity`，第三方没有 API 能区分，
         * 所以这两个开关必须整体联动 —— 否则界面上会出现「开了 A 却是 B 在生效」。
         */
        val SHARED: Set<LightOpenScene> =
            setOf(APP_JUMP, AUTH)

        /**
         * 不受「只对轮盘里的应用生效」限制的场景。
         *
         * 横幅是用户在系统界面里主动点了某个应用的通知 —— 意图非常明确，
         * 再拿「这个应用有没有放进轮盘」去卡它只会让功能看起来坏掉。
         */
        val EXEMPT_FROM_SCOPE: Set<LightOpenScene> =
            setOf(NOTIF_HEADS_UP)

        /** 与 [scene] 联动的整组开关；不共用判据的场景自成一组 */
        fun linkedTo(scene: LightOpenScene): Set<LightOpenScene> =
            if (scene in SHARED) SHARED else setOf(scene)

        fun of(key: String): LightOpenScene? = entries.firstOrNull { it.key == key }
    }
}

/** 判定被拒的原因码，进日志与统计，不弹给用户 */
enum class LightOpenReject(val code: String, val label: String) {
    DISABLED("disabled", "总开关关闭"),
    NO_FOREGROUND("no-foreground", "拿不到前台应用"),
    SELF("self", "目标是轻窗自己"),
    EXCLUDED("excluded", "在排除表里"),
    NOT_A_SWITCH("no-switch", "前台没有变化"),
    SCENE_UNKNOWN("scene-unknown", "认不出场景"),
    SCENE_OFF("scene-off", "该场景开关未开"),
    OUT_OF_SCOPE("out-of-scope", "不在生效范围"),
    COOLDOWN("cooldown", "冷却期内"),
    MANUAL("manual", "自己点的图标"),
    /**
     * 同一分享/授权流程内的后续窗口变化。
     *
     * 分享是多步的（分享面板 → 选联系人 → 聊天页 → 发送），每一步都是一个 Activity，
     * 无障碍看到的是几次独立的「前台切换到微信」。只对第一步转窗，后续一律不碰 ——
     * 否则会出现「全屏选联系人 + 浮窗聊天页」两个窗口叠在一起、输入框被压、内容发不出去。
     */
    FLOW_LOCK("flow-lock", "同一流程中不重复转窗"),
}

/** 一次判定的结果 */
data class LightOpenDecision(
    val scene: LightOpenScene?,
    val accept: Boolean,
    val reject: LightOpenReject?,
    val detail: String,
) {
    /** 统计用的键：`场景|ok` 或 `场景|原因码` */
    fun statKey(): String =
        "${scene?.key ?: "none"}|${if (accept) "ok" else reject?.code ?: "reject"}"
}

/** 判定所需的全部输入快照，时间统一用毫秒时间戳（同一条时间基准） */
data class LightOpenSnapshot(
    val now: Long,
    val foregroundPkg: String?,
    val previousPkg: String?,
    val launcherPkg: String?,
    val selfPkg: String,
    val excluded: Set<String>,
    val shadeOpenedAt: Long,
    val quickSettingsOpenedAt: Long,
    val headsUpAt: Long,
    val headsUpPkg: String?,
    /** 分享面板（Chooser）出现过的时刻；0 表示没有 */
    val chooserSeenAt: Long = 0L,
    /**
     * 锚点（通知栏 / 控制中心 / 分享面板 / 横幅）出现之前的前台应用。
     *
     * 用途：用户拉开面板又收起、或从分享面板点了返回，前台会回到这个应用。
     * 这时的窗口变化与「从面板里打开了它」完全同形，如果不排除，一次取消操作
     * 就会把用户正在用的应用转成浮窗。
     */
    val anchorReturnPkg: String? = null,
    val notifPkgs: Map<String, Long>,
    val wheelPkgs: Set<String>,
    val enabled: Boolean,
    val enabledScenes: Set<LightOpenScene>,
    val wheelOnly: Boolean,
    val cooldownMs: Long,
    val lastConvertAt: Long,
    /** 轮盘/快捷工具自己发起启动的时刻；0 表示没有 */
    val manualAt: Long,
    /** 自己发起启动的目标包名；用于冷启动偏慢时补判 */
    val manualPkg: String? = null,
    /**
     * 分享/授权流程锁：识别出该场景后记下的目标包与时刻。
     * 在这段时间内同一包的任何窗口变化都不再转窗（流程中途转窗会打断分享）。
     */
    val flowTargetPkg: String? = null,
    val flowAt: Long = 0L,
    /**
     * 分享/授权的来源包（发起分享的应用）。
     * 分享完成返回来源应用时，前台变化与「应用跳转」完全同形；只锁目标包的话，
     * 来源应用会被误判成跳转/通知而转成小窗。来源包同样纳入流程锁。
     */
    val flowSourcePkg: String? = null,
)

/**
 * 轻量打开判据（纯函数，无 Android 依赖，全部可单测）。
 *
 * 判定优先级：横幅 → 通知中心 → 控制中心 → 应用跳转。
 * 之所以要这个顺序：用户在用 A 应用时弹出一条横幅，此后前台变成 B，
 * 这时"上一前台是 A（普通应用）"也成立，若不先判横幅就会误记成应用跳转。
 */
object LightOpenRules {

    /** 横幅出现后多久内算"点了横幅" */
    const val HEADS_UP_WINDOW_MS = 3_000L
    /** 通知栏展开后多久内算"从通知栏点了通知" */
    const val SHADE_WINDOW_MS = 3_000L
    /** 控制中心展开后多久内算"从控制中心拉起" */
    const val QS_WINDOW_MS = 2_000L
    /**
     * 通知有效期：通知到达后这段时间内前台变成该应用，就算"点了它的通知"。
     *
     * 12 秒覆盖「来消息 → 亮屏看一眼 → 点开」的常见节奏；不再强制要求通知栏展开过 ——
     * 锁屏点通知、点顶部横幅、从桌面直接点都没有"展开通知栏"这一步，
     * 旧判据要求 shade 锚点，导致这三种情况全部漏判成"认不出场景"。
     */
    const val NOTIF_RECENT_MS = 12_000L

    /**
     * 分享面板出现后的判定窗口。
     *
     * 分享面板（ChooserActivity）是系统界面上唯一能观测到的"这是一次分享"的信号：
     * A 点分享 → 面板 → 选微信 → 微信分享页。面板出现过的这段时间内前台切到某个应用，
     * 就按「应用分享」处理，与普通应用跳转区分开 —— 两者的转窗策略必须不同（见 [LightOpenPolicy]）。
     */
    const val CHOOSER_WINDOW_MS = 10_000L

    /**
     * 分享/授权的流程锁时长。
     *
     * 「选择联系人 → 聊天页 → 发送」在慢机器上可以拖到十几秒，期间微信会连开三四个
     * Activity。3 秒的默认冷却盖不住，第二次窗口变化仍会触发转窗 —— 这正是录屏里
     * 「选联系人全屏、聊天页浮窗」两个窗口叠加的原因。45 秒覆盖一次完整分享，
     * 到期后用户如果又主动从微信里点开别的应用，判据照常工作。
     */
    const val FLOW_LOCK_MS = 45_000L
    /** 默认冷却：防止连环转窗（应用内部再起一个 Activity 也触发一次） */
    const val DEFAULT_COOLDOWN_MS = 3_000L
    /**
     * 手动启动的保护期。轮盘是悬浮窗，不属于任何前台 Activity，
     * 所以「从轮盘点图标」在无障碍眼里与「在 A 里点开了 B」完全同形，
     * 只能靠启动时自己打的时间戳区分。
     */
    // 冷启动重的应用（微信、淘宝）从点击到窗口出现实测可到 2-3 s，保护期必须盖住这段
    const val MANUAL_GRACE_MS = 4_000L
    /**
     * 同一个包的宽限：冷启动更慢时窗口事件可能晚于 [MANUAL_GRACE_MS] 才到。
     * 只对「包名与刚启动的一致」放宽，不扩大对其它应用的屏蔽范围。
     */
    const val MANUAL_PKG_WINDOW_MS = 8_000L

    /**
     * 这次前台变化是不是"用户自己点的图标"。
     * 两道：保护期内一律算；保护期外，若前台正是刚才启动的那个包，也算。
     */
    fun manualLaunchHit(now: Long, manualAt: Long, manualPkg: String?, foregroundPkg: String?): Boolean =
        within(now, manualAt, MANUAL_GRACE_MS) ||
            (manualPkg != null && manualPkg == foregroundPkg &&
                within(now, manualAt, MANUAL_PKG_WINDOW_MS))

    private const val SYSTEMUI = SystemPackages.SYSTEMUI

    /** 时间窗判定：at 为 0（未发生）时恒 false，避免"从未发生"被当成"很久以前发生" */
    fun within(now: Long, at: Long, windowMs: Long): Boolean =
        at > 0L && now >= at && now - at <= windowMs

    /**
     * 只做场景识别，不做开关/范围/冷却过滤。
     * 返回 null 表示这次前台变化不属于任何受支持场景（例如用户点了桌面图标）。
     */
    fun detectScene(s: LightOpenSnapshot): LightOpenScene? {
        val fg = s.foregroundPkg ?: return null

        // 1. 横幅：目标就是横幅所属应用，且横幅刚出现
        if (s.headsUpPkg != null && s.headsUpPkg == fg &&
            within(s.now, s.headsUpAt, HEADS_UP_WINDOW_MS)
        ) {
            return LightOpenScene.NOTIF_HEADS_UP
        }

        // 2. 通知：这个应用最近发过通知。
        //    只要「通知到达后 12 秒内前台切到它」就算横幅点击。
        //    但「从桌面点图标」必须排除：桌面点应用是明确的「全屏打开」意图。
        val notifAt = s.notifPkgs[fg] ?: 0L
        val fromLauncherTap = s.previousPkg == s.launcherPkg
        if (fg != s.anchorReturnPkg && within(s.now, notifAt, NOTIF_RECENT_MS) &&
            !fromLauncherTap
        ) {
            return LightOpenScene.NOTIF_HEADS_UP
        }

        // 3. 应用跳转：上一前台是另一个普通应用（不是桌面、不是系统界面、不是自己）
        val prev = s.previousPkg
        if (prev != null && prev != fg && prev != s.selfPkg &&
            prev != SYSTEMUI && prev != s.launcherPkg
        ) {
            return LightOpenScene.APP_JUMP
        }

        return null
    }

    /**
     * 场景开关闸门。
     *
     * 授权/分享与跳转在系统层面是同一种启动方式，[detectScene] 只会返回 [LightOpenScene.APP_JUMP]，
     * 所以闸门必须做在 APP_JUMP 这一侧：三者任一开启即放行。
     * 否则只打开「登录授权」时，判定出的场景是 APP_JUMP、闸门查的却是 APP_JUMP 关着，
     * 开关就成了摆设。
     */
    fun sceneEnabled(scene: LightOpenScene, enabled: Set<LightOpenScene>): Boolean =
        if (scene in LightOpenScene.SHARED) {
            enabled.any { it in LightOpenScene.SHARED }
        } else {
            scene in enabled
        }

    /** 完整判定 */
    fun decide(s: LightOpenSnapshot): LightOpenDecision {
        if (!s.enabled) return reject(null, LightOpenReject.DISABLED, "")
        val fg = s.foregroundPkg
            ?: return reject(null, LightOpenReject.NO_FOREGROUND, "")
        if (fg == s.selfPkg) return reject(null, LightOpenReject.SELF, fg)
        if (fg in s.excluded || fg == SYSTEMUI) {
            return reject(null, LightOpenReject.EXCLUDED, fg)
        }
        if (s.previousPkg == fg) return reject(null, LightOpenReject.NOT_A_SWITCH, fg)
        // 自己点的图标：轮盘、快捷工具、更多面板发起启动时打了时间戳，期间一律不转窗。
        // 这条必须排在场景识别之前 —— 否则「从 A 呼出轮盘点开 B」会被当成应用跳转。
        if (manualLaunchHit(s.now, s.manualAt, s.manualPkg, fg)) {
            return reject(null, LightOpenReject.MANUAL, "ago=${s.now - s.manualAt}ms pkg=$fg")
        }
        // 分享/授权的第一步已经处理过了：后续同一包里连续开几个 Activity 都是同一流程，
        // 再转一次就会出现「全屏选联系人 + 浮窗聊天页」叠加，分享内容发不出去。
        // 这条必须在场景识别之前 —— 第二次窗口变化很可能被认成 APP_JUMP。
        // 目标包与来源包都锁：来源包是发起分享的应用，分享完成按返回键回到它时，
        // 前台变化与跳转同形，不锁就会被当成新一次转窗（用户报障：返回小红书被切成小窗）。
        if (s.flowAt > 0L && s.now >= s.flowAt && s.now - s.flowAt <= FLOW_LOCK_MS &&
            (fg == s.flowTargetPkg || fg == s.flowSourcePkg)
        ) {
            return reject(null, LightOpenReject.FLOW_LOCK, "ago=${s.now - s.flowAt}ms")
        }

        val scene = detectScene(s)
            ?: return reject(null, LightOpenReject.SCENE_UNKNOWN, "prev=${s.previousPkg} fg=$fg")

        if (!sceneEnabled(scene, s.enabledScenes)) {
            return reject(scene, LightOpenReject.SCENE_OFF, scene.label)
        }
        if (s.wheelOnly && fg !in s.wheelPkgs &&
            scene !in LightOpenScene.EXEMPT_FROM_SCOPE
        ) {
            return reject(scene, LightOpenReject.OUT_OF_SCOPE, fg)
        }
        val cooldown = s.cooldownMs.coerceAtLeast(0L)
        if (cooldown > 0L && within(s.now, s.lastConvertAt, cooldown)) {
            return reject(scene, LightOpenReject.COOLDOWN, "since=${s.now - s.lastConvertAt}ms")
        }
        return LightOpenDecision(scene, accept = true, reject = null, detail = fg)
    }

    /**
     * 该用哪种转窗方式（请求值）。
     *
     * 目标应用是否已有存活 task 由执行器判断：task 不存在（冷启动）时执行器会把策略升级成
     * [LightOpenPolicy.FULL] —— 新建 task 一定能进浮窗，也不存在打断已有流程的问题。
     *
     * `when` 显式列出全部场景、不写 `else`：新增场景时编译器会强制在这里做决定。
     * 重构前是 `else -> FULL`，新场景会被静默当成「可以重投 Intent」，而这恰好是
     * 分享/授权最不能接受的副作用。
     */
    fun policyFor(scene: LightOpenScene?, aggressiveJump: Boolean): LightOpenPolicy = when (scene) {
        // 授权：登录页已把结果接收者接好，任何重投都会让回传失败
        null, LightOpenScene.AUTH -> LightOpenPolicy.GEOMETRY_ONLY
        // 应用跳转与授权在系统层面不可区分，默认保守；开关打开后才允许重投
        LightOpenScene.APP_JUMP ->
            if (aggressiveJump) LightOpenPolicy.FULL else LightOpenPolicy.GEOMETRY_ONLY
        // 横幅：用户点的就是这条通知，SINGLE_TOP 复用栈顶，页面通常能保住
        LightOpenScene.NOTIF_HEADS_UP -> LightOpenPolicy.FULL
    }

    /**
     * 识别出这类场景后对目标包加流程锁。
     *
     * 只有分享与授权：它们是多步流程，中途转窗会打断结果回传。
     *
     * 注意 [LightOpenScene.AUTH] 目前没有独立生产者（见 [LightOpenScene.SHARED] 的说明），
     * 保留在集合里是为了「将来能区分授权」时不必再改三处判据；单测已按语义断言。
     */
    private val FLOW_LOCK_SCENES = setOf(LightOpenScene.AUTH)

    fun needsFlowLock(scene: LightOpenScene?): Boolean =
        scene != null && scene in FLOW_LOCK_SCENES

    private fun reject(
        scene: LightOpenScene?,
        why: LightOpenReject,
        detail: String,
    ) = LightOpenDecision(scene, accept = false, reject = why, detail = detail)
}

/**
 * 转窗方式。为什么必须有两种：
 *
 * 系统里没有「把已存在的全屏 task 就地变成浮窗」的公开命令。能用的手段只有两种，
 * 代价差别很大：
 *
 * - 只改几何（`am task resize`）：不动 Activity 栈、不重投 Intent。
 *   缺点是对全屏 task 时常失败（跨窗口模式 resize 多数 ROM 不支持）。
 * - 重新 `am start --windowingMode 5`：能新建浮窗，但对**已存在**的 task 会把
 *   Intent 重新投递一遍。分享、登录授权这类流程靠 `resultTo` 链回传结果，
 *   重投一次就等于把流程掐断 —— 表现是「分享出去的内容对方收不到 / 授权回不来」。
 *
 * 所以：能确定是分享/授权 → 只改几何；失败了宁可保持全屏，也不能破坏功能。
 */
enum class LightOpenPolicy {
    /** 只改几何，绝不重投 Intent；失败就保持原样 */
    GEOMETRY_ONLY,

    /** 几何失败后允许重新 `am start`（新建 task 是安全的，复用旧 task 有副作用） */
    FULL,
}

/**
 * 统计串编解码（纯函数）。
 * 存储形如 `notif_shade|ok=3;app_jump|cooldown=1`，键里不会出现分隔符。
 */
object LightOpenStatsCodec {

    private const val PAIR_SEP = ';'
    private const val KV_SEP = '='

    fun decode(raw: String): Map<String, Int> {
        if (raw.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, Int>()
        raw.split(PAIR_SEP).forEach { seg ->
            val i = seg.lastIndexOf(KV_SEP)
            if (i <= 0) return@forEach
            val key = seg.substring(0, i).trim()
            val value = seg.substring(i + 1).trim().toIntOrNull() ?: return@forEach
            if (key.isNotEmpty()) out[key] = value
        }
        return out
    }

    fun encode(map: Map<String, Int>): String =
        map.entries.filter { it.value > 0 }
            .joinToString(PAIR_SEP.toString()) { "${it.key}$KV_SEP${it.value}" }
}
