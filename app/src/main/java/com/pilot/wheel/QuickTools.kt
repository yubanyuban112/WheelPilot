package com.pilot.wheel

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import priv.kit.core.Privilege

/**
 * 「更多」面板的工具页，全部改为调用系统能力，不再依赖特权服务（shell）：
 *
 *  - 识屏  → 无障碍注入「双指长按」，等价于用户双指按住屏幕，唤起荣耀 YOYO 看见；
 *  - 截屏  → AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT（Android 14+）；
 *  - 锁屏  → AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN；
 *  - 手电筒 → CameraManager（需要 CAMERA 运行时权限，由调用方申请）；
 *  - 微信/支付宝/云闪付 → scheme 或 exported Activity 直达，不走 am start。
 *
 * 荣耀 MagicOS 10 的 YOYO 看见、AI 键扫码都是系统 UI 独占能力，没有对第三方开放的
 * intent；能稳定调用的只有上面这些系统 API 与各支付 App 自己的入口。
 */
object QuickTools {

    data class Tool(
        val id: String,
        val label: String,
        /** 轮盘/固定位上的短名，太长会把轮盘标签挤爆 */
        val short: String,
        val glyph: String,
        val desc: String,
        val color: Int,
    )

    const val SCREEN_TEXT = "screen_text"
    const val SCREENSHOT = "screenshot"
    const val TORCH = "torch"
    const val LOCK = "lock"
    const val WECHAT_SCAN = "wechat_scan"
    const val ALIPAY_SCAN = "alipay_scan"
    const val UNIONPAY_SCAN = "unionpay_scan"
    const val WECHAT_PAY = "wechat_pay"
    const val ALIPAY_PAY = "alipay_pay"
    const val ALIPAY_RECEIPT = "alipay_receipt"
    const val UNIONPAY_PAY = "unionpay_pay"

    private const val WECHAT = "com.tencent.mm"
    private const val ALIPAY = "com.eg.android.AlipayGphone"
    private const val UNIONPAY = "com.unionpay"

    /** 轮盘条目编码：应用直接存包名，工具存 tool:<id> */
    const val TOOL_PREFIX = "tool:"

    fun toolKey(id: String) = TOOL_PREFIX + id

    fun isToolKey(key: String) = key.startsWith(TOOL_PREFIX)

    fun toolIdOf(key: String) = key.removePrefix(TOOL_PREFIX)

    /**
     * 工具关联的应用包名：用于「更多页」工具卡显示关联应用的真实图标与轮廓。
     * 系统能力类（截屏/手电/锁屏/打开链接）无关联应用，返回 null，继续用工具字形图标。
     */
    fun linkedPackage(toolId: String): String? = when (toolId) {
        WECHAT_SCAN, WECHAT_PAY -> WECHAT
        ALIPAY_SCAN, ALIPAY_RECEIPT, ALIPAY_PAY -> ALIPAY
        UNIONPAY_SCAN, UNIONPAY_PAY -> UNIONPAY
        else -> null
    }

    // 只保留能真正直达的工具：微信内部页不导出、荣耀识屏不接受注入手势，
    // 这两类已移除，避免点了没反应还占位置。
    val ALL = listOf(
        Tool(SCREENSHOT, "截屏", "截屏", "截", "保存到相册", 0xFF4ECDC4.toInt()),
        Tool(TORCH, "手电筒", "手电", "灯", "点亮手电筒", 0xFFF0B429.toInt()),
        Tool(LOCK, "锁屏", "锁屏", "锁", "一键锁屏", 0xFF64748B.toInt()),
        Tool(ALIPAY_SCAN, "支付宝扫一扫", "支付宝扫", "扫", "直达扫码", 0xFF1677FF.toInt()),
        Tool(ALIPAY_RECEIPT, "支付宝收款码", "支付宝收", "收", "直达收款码", 0xFF1677FF.toInt()),
        Tool(ALIPAY_PAY, "支付宝付款码", "支付宝付", "付", "直达付款码", 0xFF1677FF.toInt()),
        Tool(UNIONPAY_SCAN, "云闪付扫一扫", "云闪付扫", "扫", "直达扫码", 0xFFE60012.toInt()),
        Tool(UNIONPAY_PAY, "云闪付收付款", "云闪付付", "付", "付款码 · 页内有收款", 0xFFE60012.toInt()),
    )

    private val byId = ALL.associateBy { it.id }

    fun tool(id: String): Tool? = byId[id]

    @Volatile
    private var torchOn = false

    // -----------------------------------------------------------------------
    // 轮盘 / 固定位
    // -----------------------------------------------------------------------

    /** 把 tool:<id> 解析成轮盘条目；图标现画并缓存，避免每次呼出都重绘 */
    fun asPilotApp(context: Context, key: String): PilotApp? {
        val tool = byId[toolIdOf(key)] ?: return null
        return PilotApp(
            pkg = key,
            label = tool.short,
            icon = visual(context, tool).icon,
            toolId = tool.id,
        )
    }

    /**
     * 工具在界面上的统一呈现：图标 + 实测轮廓。
     *
     * 判据与「更多」面板的工具卡完全一致 —— 有关联应用的（支付宝/云闪付各入口）
     * 直接用该应用的真实图标与轮廓，没有关联应用的系统能力类（截屏/手电/锁屏/链接）
     * 才用品牌色底 + 字形的自绘图标。此前轮盘与应用页一律用自绘图标，
     * 同一个「支付宝扫一扫」在三个页面显示成两个形状，本次统一到这一处。
     */
    data class Visual(val icon: Bitmap, val contour: Float)

    private val visualCache = HashMap<String, Visual>()

    fun visual(context: Context, tool: Tool): Visual = visualCache.getOrPut(tool.id) {
        val linked = linkedPackage(tool.id)
        val appBitmap = linked?.let { pkg ->
            runCatching { context.packageManager.getApplicationIcon(pkg).toBitmap(96, 96) }.getOrNull()
        }
        if (appBitmap != null) {
            Visual(appBitmap, detectIconContour(appBitmap))
        } else {
            Visual(icon(context, tool), ICON_CONTOUR)
        }
    }

    /** 按轮盘条目键（tool:<id>）取轮廓；非工具键返回 null */
    fun contourOf(context: Context, key: String): Float? =
        tool(toolIdOf(key))?.let { visual(context, it).contour }

    /** 工具图标的圆角比例（26 / 96），底座与裁剪必须用同一个值，形状才和图标一致 */
    const val ICON_CONTOUR = 26f / 96f

    private val iconCache = HashMap<String, Bitmap>()

    /** 品牌色圆角底 + 白色字形，96×96，够轮盘 50dp 用 */
    fun icon(context: Context, tool: Tool): Bitmap = iconCache.getOrPut(tool.id) {
        val size = 96
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        fill.color = tool.color
        val rect = RectF(0f, 0f, size.toFloat(), size.toFloat())
        val r = ICON_CONTOUR * size
        canvas.drawRoundRect(rect, r, r, fill)
        fill.color = Color.argb(40, 255, 255, 255)
        canvas.drawRoundRect(RectF(0f, 0f, size.toFloat(), size * 0.48f), r, r, fill)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 50f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val fm = text.fontMetrics
        canvas.drawText(tool.glyph, size / 2f, size / 2f - (fm.ascent + fm.descent) / 2f, text)
        bmp
    }

    // -----------------------------------------------------------------------
    // 工具页
    // -----------------------------------------------------------------------

    fun orderedEnabled(context: Context): List<Tool> {
        val enabled = Prefs.toolsEnabled(context).toSet()
        val order = Prefs.toolOrder(context)
        return order.mapNotNull { byId[it] }
            .filter { it.id in enabled }
    }

    fun isAvailable(context: Context, id: String): Boolean = when (id) {
        TORCH -> context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)
        ALIPAY_SCAN, ALIPAY_PAY, ALIPAY_RECEIPT -> installed(context, ALIPAY)
        UNIONPAY_SCAN, UNIONPAY_PAY -> installed(context, UNIONPAY)
        else -> true
    }

    fun needsCameraPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED

    private fun installed(context: Context, pkg: String): Boolean = runCatching {
        context.packageManager.getApplicationInfo(pkg, 0).enabled
    }.getOrDefault(false)
    /**
     * 执行工具，返回提示文案（空串表示成功且无需提示）。
     *
     * 启动类工具统一走 [openComponent]：特权服务在线时优先 shell `am start`，
     * 这条通路是项目里验证过能绕开荣耀 iAware 的；普通 startActivity 在 MagicOS 上
     * 会被静默拦掉（不报错，但界面不出来）。特权不在线时才退回 intent。
     */
    suspend fun run(context: Context, id: String): String = runCatching {
        when (id) {
            SCREEN_TEXT -> screenText(context)
            SCREENSHOT -> screenshot(context)
            TORCH -> toggleTorch(context)
            LOCK -> lockScreen()
            WECHAT_SCAN -> openOnly(context, WECHAT, "扫一扫")
            WECHAT_PAY -> openOnly(context, WECHAT, "我 → 服务 → 收付款")
            ALIPAY_SCAN -> launchScheme(
                context, ALIPAY, "alipays://platformapi/startapp?appId=10000007", "正在打开支付宝扫一扫",
            )
            ALIPAY_RECEIPT -> firstScheme(
                context, ALIPAY,
                listOf(
                    "alipayqr://platformapi/startapp?saId=20000123",
                    "alipays://platformapi/startapp?appId=20000123",
                ),
                "正在打开支付宝收款码",
            )
            ALIPAY_PAY -> firstScheme(
                context, ALIPAY,
                listOf(
                    "alipayqr://platformapi/startapp?saId=20000056",
                    "alipays://platformapi/startapp?appId=20000056",
                ),
                "正在打开支付宝付款码",
            )
            UNIONPAY_SCAN -> launchScheme(
                context, UNIONPAY, "upwallet://native/scanCode", "正在打开云闪付扫一扫",
            )
            UNIONPAY_PAY -> launchScheme(
                context, UNIONPAY, "upwallet://pay", "正在打开云闪付收付款，点下方「收款」",
            )
            else -> "未知工具"
        }
    }.getOrElse { "执行失败：${it.message ?: it.javaClass.simpleName}" }

    // -----------------------------------------------------------------------
    // 荣耀系统能力
    // -----------------------------------------------------------------------

    /**
     * 识屏：先注入双指长按（YOYO 看见的触发方式）；注入被系统拒绝时
     * 退到系统助手 ACTION_ASSIST，至少能把 YOYO 叫起来。
     */
    private fun screenText(context: Context): String {
        val service = PilotService.instance
        if (service != null && service.doubleFingerLongPress()) {
            return "正在唤起 YOYO 看见"
        }
        val assist = runCatching {
            context.startActivity(
                Intent(Intent.ACTION_ASSIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.isSuccess
        return if (assist) "手势被系统拒绝，已唤起 YOYO" else "识屏不可用"
    }

    /**
     * 截屏：先试 shell 的 screencap（可靠、直接落相册），没有再走无障碍全局动作。
     * MagicOS 上 GLOBAL_ACTION_TAKE_SCREENSHOT 会返回 true 但实际不截图，所以不能只信返回值。
     */
    private suspend fun screenshot(context: Context): String {
        if (Privilege.serverState.value != null) {
            val stamp = System.currentTimeMillis()
            val dir = "/sdcard/Pictures/Screenshots"
            val path = "$dir/WP_$stamp.png"
            if (AppLauncher.runShellCommand("mkdir -p $dir; screencap -p $path")) {
                AppLauncher.runShellCommand(
                    "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://$path",
                )
                return "已截屏，保存在相册"
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val service = PilotService.instance ?: return "截屏需要先开启无障碍服务"
            val ok = runCatching {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
            }.getOrDefault(false)
            if (ok) return "截屏中…（系统未响应就先把特权服务连上）"
        }
        return "截屏失败：请先启动特权服务"
    }

    private fun lockScreen(): String {
        val service = PilotService.instance ?: return "锁屏需要先开启无障碍服务"
        val ok = runCatching {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
        }.getOrDefault(false)
        return if (ok) "已锁屏" else "锁屏失败，请确认无障碍服务在运行"
    }

    private fun toggleTorch(context: Context): String {
        if (needsCameraPermission(context)) return "需要相机权限才能开手电筒"
        return runCatching {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id)[CameraCharacteristics.FLASH_INFO_AVAILABLE] == true
            } ?: return "没有可用的闪光灯"
            torchOn = !torchOn
            manager.setTorchMode(cameraId, torchOn)
            if (torchOn) "手电筒已开启" else "手电筒已关闭"
        }.getOrElse { "手电筒切换失败：${it.message}" }
    }

    // -----------------------------------------------------------------------
    // 支付 App
    // -----------------------------------------------------------------------

    /**
     * 微信、云闪付的内部页面全都不导出（真机日志：not exported from uid 10276），
     * 外部 am start / scheme 进不去；无障碍自动点按又会被风控拦（云闪付会提示
     * "被自动控制类软件控制"），所以只把 App 拉到前台，剩下一步交给用户。
     */
    private suspend fun openOnly(context: Context, pkg: String, what: String): String {
        if (!installed(context, pkg)) return "未安装${labelOf(pkg)}"
        val launch = context.packageManager.getLaunchIntentForPackage(pkg) ?: return "启动失败"
        val component = launch.component ?: return "启动失败"
        val opened = openComponent(context, component, "已打开${labelOf(pkg)}")
        return if (opened.startsWith("已打开")) "$opened，请手动点「$what」" else opened
    }

    /** 依次尝试多个 scheme，第一个被系统接住就算成功 */
    private suspend fun firstScheme(
        context: Context,
        pkg: String,
        uris: List<String>,
        success: String,
    ): String {
        if (!installed(context, pkg)) return "未安装对应应用"
        for (uri in uris) {
            if (Privilege.serverState.value != null) {
                val detail = AppLauncher.runShellCommandDetail(
                    "am start -a android.intent.action.VIEW -d '$uri'",
                )
                if (shellStarted(detail)) {
                    PilotLog.d(context, TAG, "shell scheme $uri")
                    return success
                }
                PilotLog.w(context, TAG, "shell scheme failed $uri :: $detail")
            }
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isSuccess) {
                PilotLog.d(context, TAG, "scheme $uri")
                return success
            }
        }
        return "启动失败"
    }

    private suspend fun launchScheme(context: Context, pkg: String, uri: String, success: String): String {
        if (!installed(context, pkg)) return "未安装对应应用"
        if (Privilege.serverState.value != null) {
            val detail = AppLauncher.runShellCommandDetail(
                "am start -a android.intent.action.VIEW -d '$uri'",
            )
            if (shellStarted(detail)) {
                PilotLog.d(context, TAG, "shell scheme $uri")
                return success
            }
            PilotLog.w(context, TAG, "shell scheme failed $uri :: $detail")
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ok = runCatching { context.startActivity(intent) }.isSuccess
        if (ok) return success
        return openHome(context, pkg, "已打开${labelOf(pkg)}，请手动进入")
    }

    /**
     * 启动指定 Activity。特权在线先走 shell —— MagicOS 的 iAware 会静默拦截
     * 普通应用的 startActivity（无异常、无报错，但界面不出现），只有 uid 2000
     * 的 am start 能稳定拉起。
     */
    private suspend fun openComponent(
        context: Context,
        component: ComponentName,
        success: String,
    ): String {
        if (!installed(context, component.packageName)) return "未安装对应应用"
        if (Privilege.serverState.value != null) {
            val cmd = "am start -n ${component.flattenToShortString()}"
            val detail = AppLauncher.runShellCommandDetail(cmd)
            if (shellStarted(detail)) {
                PilotLog.d(context, TAG, "shell start $component")
                return success
            }
            // 失败原因（类不存在 / 未导出 / 被拦）决定了下一步策略，必须落日志
            PilotLog.w(context, TAG, "shell start failed $component :: $detail")
        }
        if (startExplicit(context, component)) return success
        return "启动失败"
    }

    private fun openHome(context: Context, pkg: String, tip: String): String {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: return "启动失败"
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ok = runCatching { context.startActivity(intent) }.isSuccess
        return if (ok) tip else "启动失败"
    }

    private fun labelOf(pkg: String): String = when (pkg) {
        WECHAT -> "微信"
        ALIPAY -> "支付宝"
        UNIONPAY -> "云闪付"
        else -> pkg
    }

    /** 执行结果里带这个前缀 = 没匹配到入口，需要用户挑一次 */
    const val NEED_PICK = "need-pick"

    private const val TAG = "WheelPilot"

    private val WECHAT_PAY_KEYWORDS = listOf(
        "offline", "purse", "coin", "paycode", "paymentcode", "receive", "shoukuan",
    )

    /** 需要用户手动校准入口的工具 */
    data class PickTarget(
        val pkg: String,
        val toolId: String,
        val title: String,
        val keywords: List<String>,
    )

    fun pickTarget(toolId: String): PickTarget? = when (toolId) {
        UNIONPAY_SCAN -> PickTarget(UNIONPAY, UNIONPAY_SCAN, "云闪付扫一扫", UnionpaySlot.SCAN.keywords)
        UNIONPAY_PAY -> PickTarget(UNIONPAY, UNIONPAY_PAY, "云闪付收款码", UnionpaySlot.RECEIPT.keywords)
        WECHAT_PAY -> PickTarget(WECHAT, WECHAT_PAY, "微信收付款", WECHAT_PAY_KEYWORDS)
        else -> null
    }

    private val componentCache = HashMap<String, List<ComponentName>>()

    /**
     * 云闪付没有公开的扫一扫/收款码 scheme，只能枚举它自己声明的 exported Activity
     * 按类名关键词打分。挑不到不硬猜：返回 [NEED_PICK]，由界面把真实 Activity 列表
     * 摆给用户选一次，选中后写进 Prefs，之后直接走那条。
     */
    private suspend fun unionpay(context: Context, slot: UnionpaySlot): String {
        if (!installed(context, UNIONPAY)) return "未安装云闪付"
        // 用户校准过的入口优先
        Prefs.toolComponent(context, slot.toolId)?.let { cls ->
            val result = openComponent(context, ComponentName(UNIONPAY, cls), "正在打开云闪付${slot.label}")
            if (result == "正在打开云闪付${slot.label}") return result
            // 37 号改动（S4/4-2）：入口打不开只记录，不再抹掉用户校准数据 ——
            // openComponent 失败可能是 iAware 静默拦截等临时原因（exit code 仍 0），
            // 删校准不可逆；候选遍历照旧兑底，校准入口下次仍优先尝试
            PilotLog.d(context, TAG, "unionpay ${slot.name} calibrated entry failed, keep: $cls")
        }
        for (component in candidates(context, UNIONPAY, slot.keywords, slot.negative)) {
            if (openComponent(context, component, "正在打开云闪付${slot.label}") ==
                "正在打开云闪付${slot.label}"
            ) {
                PilotLog.d(context, TAG, "unionpay ${slot.name} -> ${component.className}")
                return "正在打开云闪付${slot.label}"
            }
        }
        PilotLog.d(context, TAG, "unionpay ${slot.name} -> no candidate")
        return NEED_PICK + ":" + slot.toolId
    }

    /**
     * 微信收付款：老版本能直接进 WalletOfflineCoinPurseUI，新版收紧了导出。
     * 先试固定入口，再按类名关键词挑，挑不到同样交给用户校准。
     */
    private suspend fun wechatPay(context: Context): String {
        if (!installed(context, WECHAT)) return "未安装微信"
        Prefs.toolComponent(context, WECHAT_PAY)?.let { cls ->
            val ok = openComponent(context, ComponentName(WECHAT, cls), "正在打开微信收付款")
            if (ok == "正在打开微信收付款") return ok
            // 37 号改动（S4/4-2）：同上，校准数据不再因一次打开失败而被抹掉
            PilotLog.d(context, TAG, "wechat pay calibrated entry failed, keep: $cls")
        }
        val legacy = ComponentName(WECHAT, "$WECHAT.plugin.offline.ui.WalletOfflineCoinPurseUI")
        if (openComponent(context, legacy, "正在打开微信收付款") == "正在打开微信收付款") {
            return "正在打开微信收付款"
        }
        for (component in candidates(
            context, WECHAT, WECHAT_PAY_KEYWORDS, listOf("webview", "setting", "result", "scan"),
        )) {
            if (openComponent(context, component, "正在打开微信收付款") == "正在打开微信收付款") {
                PilotLog.d(context, TAG, "wechat pay -> ${component.className}")
                return "正在打开微信收付款"
            }
        }
        PilotLog.d(context, TAG, "wechat pay -> no candidate")
        return NEED_PICK + ":" + WECHAT_PAY
    }

    /**
     * am start 即使没找到目标也返回 exit 0，只在输出里打 Error。
     * 不检查输出就会把「没起来」当成成功——微信 scheme 失效那次就是这么被误判的。
     */
    private fun shellStarted(detail: String): Boolean {
        if (detail == "server-dead" || !detail.startsWith("exit=0")) return false
        for (bad in listOf("Error", "Exception", "Unable", "does not exist", "not exported")) {
            if (detail.contains(bad, ignoreCase = true)) return false
        }
        return true
    }

    private fun startExplicit(context: Context, component: ComponentName): Boolean = runCatching {
        context.startActivity(
            Intent(Intent.ACTION_MAIN).apply {
                this.component = component
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }.isSuccess

    /**
     * 校准列表：关键词命中的排前面，其余按类名排在后面。
     * 微信动辄上千个 Activity，所以最多给 80 条。
     */
    fun activityNames(context: Context, pkg: String, keywords: List<String>): List<String> {
        val all = activities(context, pkg)
        val hit = all.filter { score(it, keywords, emptyList()) > 0 }
        return (hit + all.filter { it !in hit }).take(80)
    }

    fun setToolComponent(context: Context, toolId: String, cls: String?) {
        Prefs.setToolComponent(context, toolId, cls)
        componentCache.clear()
    }

    private fun candidates(
        context: Context,
        pkg: String,
        keywords: List<String>,
        negative: List<String>,
    ): List<ComponentName> {
        val key = "$pkg|$keywords|$negative"
        return componentCache.getOrPut(key) {
            activities(context, pkg)
                .map { it to score(it, keywords, negative) }
                .filter { it.second > 0 }
                .sortedByDescending { it.second }
                .take(4)
                .map { ComponentName(pkg, it.first) }
        }
    }

    /** 读某个包的 exported Activity 类名，按包缓存，避免重复解析 */
    private val activityCache = HashMap<String, List<String>>()

    private fun activities(context: Context, pkg: String): List<String> =
        activityCache.getOrPut(pkg) {
            runCatching {
                context.packageManager.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES)
                    .activities.orEmpty()
                    .filter { it.exported && it.name.contains('.') }
                    .map { it.name }
                    .distinct()
                    .sorted()
            }.getOrDefault(emptyList())
        }

    private fun score(name: String, keywords: List<String>, negative: List<String>): Int {
        val lower = name.lowercase()
        var s = 0
        for (k in keywords) {
            if (lower.contains(k)) s += 12
            if (lower.substringAfterLast('.').startsWith(k)) s += 6
        }
        // 无关页面与不属于本入口的页面直接压到负分
        for (bad in negative + listOf("result", "webview", "setting", "web")) {
            if (lower.contains(bad)) s -= 20
        }
        return if (s > 0) s - lower.length / 30 else 0
    }
}

/** 云闪付的两个入口：扫一扫 / 收款码 */
enum class UnionpaySlot(
    val toolId: String,
    val label: String,
    val keywords: List<String>,
    val negative: List<String> = emptyList(),
) {
    /** 类名通常带 Scan / Capture */
    SCAN(
        "unionpay_scan",
        "扫一扫",
        listOf("capture", "scan"),
        listOf("receipt", "paymentcode", "mycode"),
    ),

    /**
     * 收款码：真机上扫一扫命中的是 activity.payment.UPActivityScanCodePayEntry，
     * 收款页大概率同包，所以关键词放宽到 payment/payee，再用 negative 排掉扫码页。
     */
    RECEIPT(
        "unionpay_pay",
        "收款码",
        listOf("receipt", "collect", "payee", "qrcode", "mycode", "paycode", "payment", "receive", "gather", "shoukuan"),
        listOf("scan", "capture"),
    ),
}
