package com.pilot.wheel

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pilot.wheel.core.ImeFilter
import com.pilot.wheel.core.LightOpenDecision
import com.pilot.wheel.core.LightOpenOutcome
import com.pilot.wheel.core.LightOpenRules
import com.pilot.wheel.core.LightOpenScene
import com.pilot.wheel.core.LightOpenSnapshot
import com.pilot.wheel.core.LightOpenStatsCodec
import com.pilot.wheel.core.SystemPackages
import com.pilot.wheel.core.SystemUiWindow
import com.pilot.wheel.core.SystemUiWindows
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 「轻量打开」事件汇总器。
 *
 * 三个事件源：
 * - 无障碍 `TYPE_WINDOW_STATE_CHANGED`：前台应用变化，以及通知栏/控制中心的展开
 * - 无障碍 `TYPE_TOUCH_INTERACTION_START`：用户是否在摸屏幕（仅诊断，不参与硬判定）
 * - [PilotNotificationListener]：通知到达与横幅标记
 *
 * 这里不缓存「未来要发生的启动」，因为启动由系统直接投递，第三方没有 hook 点。
 * 全部判据都是「事后」的：前台已经变了，再判断它属于哪个场景，然后转窗。
 *
 * 时间统一用 [SystemClock.elapsedRealtime]（单调、不受改时间影响）。
 */
object LightOpenWatcher {

    private const val TAG = "WheelPilot"

    /** 通知包名表最多留多少条，防止长期运行无限增长 */
    private const val MAX_NOTIF_PKGS = 32
    /** 统计每累计这么多次写一次盘 */
    private const val STATS_FLUSH_EVERY = 24
    /** 输入法包名缓存时长：输入法装/卸/切换很少，但换输入法后必须能及时生效 */
    private const val IME_REFRESH_MS = 10_000L
    /** 服务刚连上时向前回溯多久找「当前前台」 */
    private const val ATTACH_FOREGROUND_LOOKBACK_MS = 5_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var appContext: Context? = null
    private var launcherPkg: String? = null

    private var lastForeground: String? = null
    private var previousForeground: String? = null
    private var shadeOpenedAt = 0L
    private var quickSettingsOpenedAt = 0L
    private var headsUpAt = 0L
    private var headsUpPkg: String? = null
    /** 分享面板（ChooserActivity）出现过的时刻；分享与普通跳转的唯一可观测区别 */
    private var chooserSeenAt = 0L
    /** 锚点（通知栏/控制中心/分享面板/横幅）出现前的前台应用，用于识别「取消返回」 */
    private var anchorReturnPkg: String? = null
    private val notifAt = LinkedHashMap<String, Long>()
    private var lastConvertAt = 0L
    private var lastTouchAt = 0L
    /** 轮盘/快捷工具自己发起启动的时刻与目标包，用于把「手动启动」从判据里摘出去 */
    private var manualAt = 0L
    private var manualPkg: String? = null
    /**
     * 分享/授权流程的来源包：发起分享的应用（如从小红书分享到微信时的小红书）。
     * 分享完成返回它时与「应用跳转」同形，必须与目标包一起进流程锁，否则会被转成小窗。
     */
    private var flowSourcePkg: String? = null
    /** 当前启用/默认的输入法包名，用于把键盘弹出从「应用跳转」里摘出去 */
    private var imePkgs: Set<String> = emptySet()
    private var imeRefreshedAt = 0L
    /**
     * 分享/授权流程锁。第一次识别到这类场景就锁住目标包，45 秒内不再对同一包转窗。
     * 分享是多步的（选联系人 → 聊天页 → 发送），中途转窗会把流程拆成两个叠加窗口。
     */
    private var flowTargetPkg: String? = null
    private var flowAt = 0L

    // 37 号改动（S5/2-8）：@Volatile var 改 mutableStateOf —— 诊断面板读取时能跟踪
    // 后台线程的写入并自动重组；写在后台线程发起，mutableStateOf 线程安全
    /** 最近一次判定结果，供设置页展示 */
    var lastDecision: LightOpenDecision? by mutableStateOf(null)
        private set
    /** 最近一次转窗结果码，供设置页展示 */
    var lastConvertResult: String? by mutableStateOf(null)
        private set

    private val stats = LinkedHashMap<String, Int>()
    private var pendingFlush = 0
    private var loaded = false

    /** 无障碍服务连上时调用一次 */
    fun attach(context: Context) {
        val app = context.applicationContext
        appContext = app
        launcherPkg = resolveLauncher(app)
        refreshImePkgs(app, force = true)
        if (!loaded) {
            stats.clear()
            stats.putAll(Prefs.lightOpenStats(app))
            loaded = true
        }
        // 初始前台：避免服务刚起来时把「第一次切换」算成从无到有
        if (lastForeground == null) {
            lastForeground = ForegroundCheck.currentForeground(app, ATTACH_FOREGROUND_LOOKBACK_MS)
        }
        Log.d(TAG, "lightOpen attach, fg=$lastForeground launcher=$launcherPkg")
    }

    /**
     * 无障碍窗口状态变化。
     *
     * [imeWindowOnScreen] 表示此刻屏幕上是否存在活动的输入法窗口（键盘）。
     */
    fun onWindow(pkgName: String?, className: String?, imeWindowOnScreen: Boolean = false) {
        val context = appContext ?: return
        val now = SystemClock.elapsedRealtime()
        val systemWindow = SystemUiWindows.classify(className)

        // 通知栏/控制中心的窗口事件有时不带包名，只给类名；这类也要记锚点，否则通知场景永远不命中
        if (pkgName == SystemPackages.SYSTEMUI ||
            (pkgName == null && systemWindow != SystemUiWindow.NONE)
        ) {
            // 锚点出现前的前台应用：用户拉开又收起时要能识别出「回到原样」
            anchorReturnPkg = lastForeground
            when (systemWindow) {
                SystemUiWindow.SHADE -> shadeOpenedAt = now
                SystemUiWindow.QS -> quickSettingsOpenedAt = now
                SystemUiWindow.NONE -> Unit
            }
            return
        }
        val pkg = pkgName ?: return

        // 分享面板：不去转它（转成浮窗会让「选应用」这一步错位），只记锚点。
        // 面板后面那次「前台变成微信」才是真正要处理的分享，判据据此把它识别成 SHARE
        // 而不是普通应用跳转 —— 两者的转窗策略不同（SHARE 绝不能重投 Intent）。
        if (SystemUiWindows.isChooser(pkg, className)) {
            chooserSeenAt = now
            anchorReturnPkg = lastForeground
            previousForeground = lastForeground
            lastForeground = pkg
            Log.d(TAG, "lightOpen chooser seen pkg=$pkg cls=$className prev=$previousForeground")
            return
        }

        // 输入法窗口不是「用户打开了另一个应用」，但它报的事件与「应用内跳转」同形。
        // 这里必须直接返回、连前后台状态都不更新 —— 一旦把输入法写进 lastForeground，
        // 收起键盘回到原应用时又会被判成一次「从输入法跳到应用」，二次误转。
        refreshImePkgs(context)
        if (ImeFilter.shouldSkip(pkg, className, imePkgs, imeWindowOnScreen)) {
            Log.d(TAG, "lightOpen skip ime pkg=$pkg cls=$className onScreen=$imeWindowOnScreen")
            return
        }

        if (pkg == lastForeground) return

        previousForeground = lastForeground
        lastForeground = pkg
        judge(context, now)
    }

    /** 无障碍触摸开始事件：只记录，硬判定不依赖它（点通知本身也是触摸） */
    fun onTouch() {
        lastTouchAt = SystemClock.elapsedRealtime()
    }

    /**
     * 记录一次「自己发起」的启动。
     *
     * 轮盘、快捷工具、更多面板都是用 startActivity 拉起目标应用，而轮盘本身是悬浮窗、
     * 不在前台栈里，所以无障碍看到的变化与「在 A 里点开了 B」一模一样。
     * 由发起方在这里盖个时间戳，判据据此直接放行 —— 这是唯一确定性的区分方式。
     */
    fun markManualLaunch(pkg: String? = null) {
        manualAt = SystemClock.elapsedRealtime()
        manualPkg = pkg
        Log.d(TAG, "lightOpen manual launch pkg=$pkg")
    }

    /**
     * 通知到达。[isHeadsUp] 由监听服务用 ranking 的 ambient 判定，
     * 拿不准时传 false —— 宁可少认一次横幅，也不要把普通通知当横幅。
     */
    fun onNotification(pkg: String?, isHeadsUp: Boolean) {
        val context = appContext ?: return
        val target = pkg ?: return
        if (target == context.packageName || target == SystemPackages.SYSTEMUI) return
        val now = SystemClock.elapsedRealtime()

        // 重新插入到尾部，保持「最近的在最后」；每次只增 1 条，超出上限时淘汰队首
        notifAt.remove(target)
        notifAt[target] = now
        if (notifAt.size > MAX_NOTIF_PKGS) {
            notifAt.keys.firstOrNull()?.let { notifAt.remove(it) }
        }
        if (isHeadsUp) {
            headsUpPkg = target
            headsUpAt = now
            anchorReturnPkg = lastForeground
        }
        Log.d(TAG, "lightOpen notif from=$target headsUp=$isHeadsUp")
    }

    /** 设置页手动清空统计 */
    fun resetStats() {
        stats.clear()
        val context = appContext ?: return
        Prefs.setLightOpenStats(context, "")
    }

    fun statsSnapshot(): Map<String, Int> = LinkedHashMap(stats)

    fun flush(context: Context? = appContext) {
        val ctx = context ?: return
        if (pendingFlush <= 0) return
        pendingFlush = 0
        Prefs.setLightOpenStats(ctx, LightOpenStatsCodec.encode(stats))
    }

    // -----------------------------------------------------------------------

    private fun judge(context: Context, now: Long) {
        val snapshot = buildSnapshot(context, now)
        val decision = LightOpenRules.decide(snapshot)
        lastDecision = decision
        // 只要认出是分享/授权就上锁，哪怕这次因为开关或范围没转成功 ——
        // 否则用户关了「应用分享」，聊天页却会在下一步被当成「应用跳转」转掉。
        if (decision.scene != null && LightOpenRules.needsFlowLock(decision.scene)) {
            flowTargetPkg = decision.detail
            // 记录来源包：分享面板出现时 anchorReturnPkg 就是发起分享的应用。
            // 分享完成返回它时与「应用跳转」同形，必须连同目标一起锁住，否则会被转成小窗。
            flowSourcePkg = snapshot.anchorReturnPkg ?: snapshot.previousPkg
            flowAt = now
            Log.d(
                TAG,
                "lightOpen flow lock target=${decision.detail} source=$flowSourcePkg " +
                    "for ${LightOpenRules.FLOW_LOCK_MS}ms",
            )
        }
        // 锚点只用一次：这次窗口变化已经用掉「刚展开过通知栏/控制中心」这条证据，
        // 不清掉的话，用户收起面板再回桌面点图标会被当成「从通知栏点了通知」。
        shadeOpenedAt = 0L
        quickSettingsOpenedAt = 0L
        headsUpAt = 0L
        headsUpPkg = null
        chooserSeenAt = 0L
        anchorReturnPkg = null
        note(decision.statKey())
        if (!decision.accept) {
            Log.d(
                TAG,
                "lightOpen reject scene=${decision.scene?.key} why=${decision.reject?.code} " +
                    "detail=${decision.detail}",
            )
            return
        }

        val pkg = decision.detail
        lastConvertAt = now
        // 分享/授权场景只允许「改几何」，绝不重投 Intent；跳转默认也保守，
        // 由设置里的「跳转也强制转窗」放开。冷启动由执行器自动升级成 FULL。
        val policy = LightOpenRules.policyFor(
            scene = decision.scene,
            aggressiveJump = Prefs.lightOpenJumpAggressive(context),
        )
        Log.d(
            TAG,
            "lightOpen accept scene=${decision.scene?.key} pkg=$pkg policy=$policy " +
                "touchAgo=${now - lastTouchAt}ms",
        )
        scope.launch {
            val outcome = runCatching { LightOpenController.convert(context, pkg, policy) }
                .getOrElse { t ->
                    // 异常类型单独进日志：统计键要稳定，排查时要具体
                    Log.d(TAG, "lightOpen convert threw ${t.javaClass.simpleName}: ${t.message}")
                    LightOpenOutcome.ERROR
                }
            val code = outcome.code
            lastConvertResult = code
            note("${decision.scene?.key}|convert:$code")
            Log.d(TAG, "lightOpen convert $pkg -> $code")
            flush(context)
        }
    }

    private fun buildSnapshot(context: Context, now: Long): LightOpenSnapshot {
        val recentNotifs = LinkedHashMap<String, Long>()
        notifAt.forEach { (pkg, at) ->
            if (now - at <= LightOpenRules.NOTIF_RECENT_MS) recentNotifs[pkg] = at
        }
        val wheel = runCatching { Prefs.load(context).toSet() }.getOrDefault(emptySet())
        return LightOpenSnapshot(
            now = now,
            foregroundPkg = lastForeground,
            previousPkg = previousForeground,
            launcherPkg = launcherPkg,
            selfPkg = context.packageName,
            excluded = excludedPackages(context),
            shadeOpenedAt = shadeOpenedAt,
            quickSettingsOpenedAt = quickSettingsOpenedAt,
            headsUpAt = headsUpAt,
            headsUpPkg = headsUpPkg,
            chooserSeenAt = chooserSeenAt,
            anchorReturnPkg = anchorReturnPkg,
            notifPkgs = recentNotifs,
            wheelPkgs = wheel,
            enabled = Prefs.lightOpenEnabled(context),
            enabledScenes = Prefs.lightOpenScenes(context),
            wheelOnly = Prefs.lightOpenWheelOnly(context),
            cooldownMs = LightOpenRules.DEFAULT_COOLDOWN_MS,
            lastConvertAt = lastConvertAt,
            manualAt = manualAt,
            manualPkg = manualPkg,
            flowTargetPkg = flowTargetPkg,
            flowAt = flowAt,
            flowSourcePkg = flowSourcePkg,
        )
    }

    private fun note(key: String) {
        stats[key] = (stats[key] ?: 0) + 1
        pendingFlush++
        if (pendingFlush >= STATS_FLUSH_EVERY) flush()
    }

    /**
     * 不参与转窗的包名集合。
     *
     * 分三类，全部来自 [SystemPackages]，不再在本文件里散着写字面量：
     * 自己 / 系统界面与桌面 / 电话与系统弹窗 / 输入法。
     */
    private fun excludedPackages(context: Context): Set<String> = buildSet {
        add(context.packageName)
        add(SystemPackages.SYSTEMUI)
        add(SystemPackages.SETTINGS)
        launcherPkg?.let { add(it) }
        // 双保险：Watcher 已在入口拦掉输入法事件，这里再兜一层，
        // 万一某台机器报的包名不在启用列表里，decide 仍会以 EXCLUDED 拒绝
        addAll(imePkgs)
        addAll(SystemPackages.TELEPHONY)
        addAll(SystemPackages.SYSTEM_DIALOGS)
        addAll(SystemPackages.SHARE_RESOLVERS)
    }

    /**
     * 读当前启用/默认的输入法包名。
     *
     * 两路取并集：`Settings.Secure` 里的字符串最可靠（不受前台状态影响），
     * `InputMethodManager.enabledInputMethodList` 覆盖刚启用但 Secure 还没同步的情况。
     */
    private fun refreshImePkgs(context: Context, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - imeRefreshedAt < IME_REFRESH_MS) return
        imeRefreshedAt = now

        val cr = context.contentResolver
        val enabled = runCatching {
            Settings.Secure.getString(cr, Settings.Secure.ENABLED_INPUT_METHODS)
        }.getOrNull()
        val default = runCatching {
            Settings.Secure.getString(cr, Settings.Secure.DEFAULT_INPUT_METHOD)
        }.getOrNull()
        val fromSecure = ImeFilter.parsePackageList(enabled) + ImeFilter.parsePackageList(default)

        val fromManager = runCatching {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.enabledInputMethodList?.map { it.packageName }?.toSet().orEmpty()
        }.getOrDefault(emptySet())

        val merged = fromSecure + fromManager
        if (merged != imePkgs) {
            imePkgs = merged
            Log.d(TAG, "lightOpen ime pkgs=$imePkgs")
        }
    }

    private fun resolveLauncher(context: Context): String? = runCatching {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        context.packageManager
            .resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
    }.getOrNull()
}
