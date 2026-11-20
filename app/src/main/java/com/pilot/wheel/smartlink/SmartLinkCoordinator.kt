package com.pilot.wheel.smartlink

import android.accessibilityservice.AccessibilityService
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.pilot.wheel.App
import com.pilot.wheel.AppLauncher
import com.pilot.wheel.PilotShellClient
import com.pilot.wheel.Prefs
import com.pilot.wheel.smartlink.capsule.FloatingCapsuleManager
import com.pilot.wheel.smartlink.detect.DetectedLink
import com.pilot.wheel.smartlink.detect.LinkDetector
import com.pilot.wheel.smartlink.detect.ScreenLinkScanner
import com.pilot.wheel.smartlink.rule.LinkRule
import com.pilot.wheel.smartlink.rule.RuleEngine
import com.pilot.wheel.smartlink.rule.RuleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SmartLink 编排主链路：检测 → 规则匹配 → 弹胶囊 → 点击小窗启动。
 *
 * 由无障碍服务（PilotService）在 [com.pilot.wheel.PilotService.onAccessibilityEvent]
 * 中以开关包裹单向调用；本类不反向 import 具体业务类，仅复用 [AppLauncher] / [Prefs] 常量。
 */
object SmartLinkCoordinator {

    private const val TAG = "WheelPilot"

    /** 57 号：窗口变化后延时再扫，等内容挂载完成 */
    private const val SCAN_DELAY_MS = 400L

    /** 57 号：整页扫描命中的置信度（高于剪贴板 0.5、低于分享文本 0.8） */
    private const val SCAN_CONFIDENCE = 0.65f

    /** 58 号：shell 剪贴板轮询间隔。一次 binder 往返约 1ms，3s 足够且不扰系统 */
    private const val CLIP_POLL_MS = 3_000L

    private val detector = LinkDetector()
    /** 57 号：整页取链扫描器（IO 线程调用，见 [scheduleScan]） */
    private val scanner = ScreenLinkScanner()
    /** 57 号：窗口事件防抖令牌，只让最后一次扫描落地 */
    private var scanToken = 0
    /** 58 号：shell 身份剪贴板轮询任务 */
    private var clipPollJob: Job? = null
    /** 58 号：轮询去重（同一内容只处理一次） */
    private var lastShellClip: String? = null

    @Volatile private var ruleEngine: RuleEngine? = null
    @Volatile private var capsule: FloatingCapsuleManager? = null
    @Volatile private var currentPkg: String? = null

    // 剪贴板通道：服务连接时挂载监听；后台是否触发由 ROM 决定（靠日志验证）
    private var clipboardManager: ClipboardManager? = null
    private var clipboardListener: ClipboardManager.OnPrimaryClipChangedListener? = null

    /** 服务连接时挂载：公开监听 + shell 轮询（后者不依赖前者是否真的会被系统派发） */
    fun attach(service: AccessibilityService) {
        startShellClipboardPoll(service)
        if (clipboardListener != null) return
        val cm = service.getSystemService(ClipboardManager::class.java) ?: return
        clipboardManager = cm
        clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
            Log.w(TAG, "smartlink: clipboard changed event fired")
            onClipboardChanged(service)
        }
        cm.addPrimaryClipChangedListener(clipboardListener!!)
        Log.w(TAG, "smartlink: clipboard listener attached")
    }

    fun onAccessibilityEvent(service: AccessibilityService, event: AccessibilityEvent) {
        if (!SmartLinkFeature.isEnabled(service)) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                Log.w(TAG, "smartlink: WINDOW_STATE_CHANGED pkg=${event.packageName} cls=${event.className} hasSource=${event.source != null}")
                onWindowState(service, event)
            }
            else -> Unit
        }
    }

    private fun onWindowState(service: AccessibilityService, event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg != currentPkg) {
            currentPkg = pkg
            detector.onForegroundChanged(pkg)
        }
        scheduleScan(service, pkg)
    }

    /**
     * 57 号：整页扫描取链（替代旧的 `event.source` 单节点提取）。
     *
     * 三个要点：
     *  - **延时**：窗口变化后 400ms 再扫，等列表/正文把内容挂完（过早扫会只看骨架）；
     *  - **防抖**：连续窗口事件只保留最后一次（[scanToken]），避免一次跳转扫十几遍；
     *  - **离开主线程**：扫描在 IO 执行，命中后才回主线程弹胶囊。
     */
    private fun scheduleScan(service: AccessibilityService, pkg: String) {
        scanToken++
        val token = scanToken
        App.workScope.launch {
            delay(SCAN_DELAY_MS)
            if (token != scanToken) return@launch
            val result = withContext(Dispatchers.IO) { scanner.scan(service) }
            if (token != scanToken) return@launch
            ScreenLinkScanner.trace(TAG, "scan ${ScreenLinkScanner.describe(result)} pkg=$pkg")
            PilotShellClient.trace("SMARTLINK_SCAN", "${ScreenLinkScanner.describe(result)} pkg=$pkg")
            if (result.hits.isEmpty()) return@launch

            // 自上而下取第一条命中规则且未被处理过的链接
            for (hit in result.hits) {
                val link = DetectedLink(
                    rawText = hit.text,
                    url = hit.url,
                    sourcePackage = pkg,
                    confidence = SCAN_CONFIDENCE,
                    channel = DetectedLink.Channel.SCREEN_SCAN,
                    bounds = hit.bounds,
                )
                if (!detector.shouldHandle(link)) continue
                val rule = resolveRule(service, hit.url) ?: continue
                ScreenLinkScanner.trace(
                    TAG,
                    "hit rule=${rule.targetPackage} url=${hit.url.take(70)} at=${hit.bounds.left},${hit.bounds.top}",
                )
                detector.markHandled(link)
                showCapsule(service, link, rule)
                return@launch
            }
        }
    }

    /** 剪贴板变化回调：读剪贴板 → 提取 → 匹配 → 弹胶囊（带诊断日志） */
    private fun onClipboardChanged(service: AccessibilityService) {
        if (!SmartLinkFeature.isEnabled(service)) return
        val text = readClipboard(service)
        if (text.isNullOrBlank()) {
            Log.w(TAG, "smartlink: clipboard unreadable or empty")
            return
        }
        Log.w(TAG, "smartlink: clipboard text len=${text.length} head=${text.take(120)}")
        val link = detector.extractFromClipboardText(text, "clipboard") ?: return
        if (!detector.shouldHandle(link)) return
        val url = link.url ?: return
        val rule = resolveRule(service, url)
        if (rule == null) {
            Log.w(TAG, "smartlink: no rule matched for clipboard url=$url")
            return
        }
        Log.w(TAG, "smartlink: clipboard rule matched -> ${rule.targetPackage} (${rule.displayName})")
        detector.markHandled(link)
        showCapsule(service, link, rule)
    }

    private fun readClipboard(context: Context): String? = runCatching {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return null
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        clip.getItemAt(0)?.coerceToText(context)?.toString()
    }.getOrNull()

    /**
     * 58 号：shell 身份剪贴板轮询。
     *
     * 为什么需要：Android 10+ 把**后台应用**读剪贴板判为空，`OnPrimaryClipChangedListener`
     * 在后台也常不回调（旧实现注释写的「靠日志验证」就是这件事）。shell（uid 2000）不受
     * 该限制，因此让常驻 shell 进程代读，主进程只轮询结果（一次 binder 往返，约 1ms）。
     *
     * 开关关闭时整轮跳过（不产生任何 banner / 读取痕迹）；同一内容只处理一次。
     */
    private fun startShellClipboardPoll(service: AccessibilityService) {
        if (clipPollJob?.isActive == true) return
        clipPollJob = App.workScope.launch {
            while (isActive) {
                delay(CLIP_POLL_MS)
                if (!SmartLinkFeature.isEnabled(service)) continue
                val text = PilotShellClient.readClipboardText()
                if (text.isNullOrEmpty()) continue
                if (text.startsWith("ERR:")) {
                    Log.w(TAG, "smartlink: shell clipboard unavailable $text")
                    PilotShellClient.trace("CLIP_SHELL_ERR", text)
                    continue
                }
                if (text == lastShellClip) continue
                lastShellClip = text
                PilotShellClient.trace("CLIP_SHELL", "len=${text.length} head=${text.take(60)}")
                onShellClipboardText(service, text)
            }
        }
    }

    /** shell 通道读到的剪贴板文本 → 与公开监听同一条分发链路 */
    private fun onShellClipboardText(service: AccessibilityService, text: String) {
        if (text.startsWith("URI:")) return // 图片/文件剪贴板暂不接管
        val link = detector.extractFromClipboardText(text, "clipboard") ?: return
        if (!detector.shouldHandle(link)) return
        val url = link.url ?: return
        val rule = resolveRule(service, url) ?: return
        Log.w(TAG, "smartlink: shell clipboard rule matched -> ${rule.targetPackage}")
        PilotShellClient.trace("CLIP_SHELL_HIT", "url=${url.take(70)} -> ${rule.targetPackage}")
        detector.markHandled(link)
        showCapsule(service, link, rule)
    }

    private fun engine(context: Context): RuleEngine =
        ruleEngine ?: RuleEngine(RuleRepository(context.applicationContext)).also { ruleEngine = it }

    /**
     * 规则命中 + 目标已安装校验。
     *
     * 默认规则表是通用的（38 条，覆盖主流 App），本机未必装了对应 App。未装就不弹胶囊：
     * 点下去是"没反应"甚至被 iAware 静默吞掉，比不弹更糟。命中但未装只落一条
     * `SMARTLINK_SKIP` trace，便于判断"为什么这条链接没反应"。
     */
    private fun resolveRule(service: AccessibilityService, url: String): LinkRule? {
        val rule = engine(service).match(url) ?: return null
        if (!isInstalled(service, rule.targetPackage)) {
            PilotShellClient.trace(
                "SMARTLINK_SKIP",
                "no_pkg=${rule.targetPackage} url=${url.take(50)}",
            )
            return null
        }
        return rule
    }

    @Suppress("DEPRECATION")
    private fun isInstalled(context: Context, pkg: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    private fun showCapsule(service: AccessibilityService, link: DetectedLink, rule: LinkRule) {
        val mgr = capsule ?: FloatingCapsuleManager(service).also { capsule = it }
        // 57 号：有坐标就贴住那条链接，没有（剪贴板/分享通道）退回左下角常位
        mgr.show(rule.targetPackage, rule.displayName, anchor = link.bounds) {
            openLink(service, link, rule)
        }
    }

    private fun openLink(context: Context, link: DetectedLink, rule: LinkRule) {
        val intent = buildLaunchIntent(link, rule)
        App.workScope.launch {
            AppLauncher.launchIntent(context, intent, Prefs.LAUNCH_AUTO)
        }
    }

    /**
     * 启动优先级：urlTransform 模板 → deepLinkScheme → 原始 URL，均 setPackage 指定目标包，
     * 让系统优先用目标 App 解析（目标 App 注册了分享短链域名时可直接打开对应内容）。
     */
    private fun buildLaunchIntent(link: DetectedLink, rule: LinkRule): Intent {
        val url = link.url
        val transformed = rule.urlTransform?.let { tpl ->
            url?.let { tpl.replace("{url}", it) }
        }
        val uri = transformed ?: rule.deepLinkScheme ?: url
        return Intent(Intent.ACTION_VIEW, uri?.let(Uri::parse)).apply {
            setPackage(rule.targetPackage)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** 关闭开关 / 回滚：清胶囊与检测会话，不保留常驻对象 */
    fun shutdown() {
        clipPollJob?.cancel()
        clipPollJob = null
        lastShellClip = null
        clipboardListener?.let { clipboardManager?.removePrimaryClipChangedListener(it) }
        clipboardListener = null
        clipboardManager = null
        capsule?.hide()
        capsule = null
        detector.clear()
        currentPkg = null
    }
}
