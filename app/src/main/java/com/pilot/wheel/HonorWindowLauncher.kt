package com.pilot.wheel

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import java.lang.reflect.Method

/**
 * 荣耀 MagicOS 的「第三方拉起小窗」桥接。
 *
 * 公开文档只承诺「一步窗口/通知快捷窗口」这类系统触发方式，没有给第三方
 * 直接把任意应用强制切成悬浮窗的公开 API。这里按荣耀开发者文档的反射入口
 * ActivityManagerEx 做能力探测，并同时下发荣耀/华为私有的 ActivityOptions key。
 * 任一步失败都会被上层忽略，继续走 AOSP freeform / shell / 全屏兜底。
 */
object HonorWindowLauncher {

    private const val TAG = "WheelPilot"
    private const val AM_EX = "com.hihonor.android.app.ActivityManagerEx"

    /** 多窗口位置值候选：不同 MagicOS 版本取值不同，逐个尝试直到有非 false 返回 */
    private val POSITION_CANDIDATES = intArrayOf(2, 1, 3, 5, 100)

    fun isHonor(context: Context): Boolean {
        val manufacturer = android.os.Build.MANUFACTURER.orEmpty()
        val brand = android.os.Build.BRAND.orEmpty()
        // contains 而非 equals：部分机型上报 "HONOR"/"honor"/带后缀的变体
        return manufacturer.contains("HONOR", ignoreCase = true) ||
            brand.contains("HONOR", ignoreCase = true) ||
            runCatching {
                context.packageManager.getApplicationInfo("com.hihonor.android", 0)
                true
            }.getOrDefault(false)
    }

    /**
     * 荣耀小窗启动 options：公开 setLaunchBounds + AOSP windowingMode 5，
     * 同时写入荣耀/华为私有 key，覆盖 MagicOS 不同版本的解析分支。
     * 不使用 windowingMode=100：在荣耀上会被解释成贴顶分屏（此前图二的问题）。
     */
    fun options(bounds: Rect?): Bundle {
        val bundle = runCatching {
            ActivityOptions.makeBasic().apply {
                if (bounds != null) runCatching { setLaunchBounds(bounds) }
            }.toBundle()
        }.getOrElse { Bundle() }

        bundle.putInt("android.activity.windowingMode", 5)
        bundle.putInt("com.hihonor.android.activity.windowingMode", 5)
        bundle.putInt("hihonor.activity.windowingMode", 5)
        bundle.putInt("com.huawei.android.activity.windowingMode", 5)
        bundle.putBoolean("com.hihonor.android.activity.launchAsFloatingWindow", true)
        bundle.putBoolean("hihonor.activity.launchAsFloatingWindow", true)
        return bundle
    }

    /** 按荣耀文档探测系统是否声明了动态多窗口能力 */
    fun supportedOperations(): Map<*, *>? = runCatching {
        val clazz = Class.forName(AM_EX)
        val method: Method = clazz.getMethod("getSupportedWindowOperations")
        method.invoke(null) as? Map<*, *>
    }.onFailure {
        Log.d(TAG, "honor getSupportedWindowOperations unavailable: ${it.javaClass.simpleName}")
    }.getOrNull()

    // -------------------------------------------------------------------------
    // 原生轻窗直拉（mode 102）—— 参考社区 LSP 模块「荣耀轻窗」(dev.flywindow.honor)
    // 的实现逆向还原：
    //   ActivityManagerEx.HW_MULTI_WINDOWING_MODE_FREEFORM == 102
    //   ActivityManagerEx.startActivityOneStep(Context, Intent, Integer)  静态方法
    //   HwActivityTaskManager.isResizableApp(ActivityInfo)                可小窗白名单
    // 荣耀原生小窗（系统边缘手势直拉那种形态）走的就是 102 —— AOSP freeform(5)
    // 会被 MagicOS 钳制尺寸，而 102 是 MagicOS 私有轻窗层，尺寸与系统直拉一致。
    // -------------------------------------------------------------------------

    /** 荣耀原生轻窗 windowingMode（正常应为 102；读不到返回 null） */
    fun oneStepMode(context: Context? = null): Int? = runCatching {
        val clazz = Class.forName(AM_EX)
        clazz.getField("HW_MULTI_WINDOWING_MODE_FREEFORM").getInt(null)
    }.onFailure {
        log(context, "honor HW_MULTI_WINDOWING_MODE_FREEFORM unavailable: ${it.javaClass.simpleName}")
    }.getOrNull()

    /** 系统是否开放了 OneStep 小窗入口（能力位缺失视为开放，仅记录日志） */
    private fun oneStepEnabled(context: Context? = null): Boolean = runCatching {
        val clazz = Class.forName(AM_EX)
        val enabled = runCatching {
            clazz.getField("HWMULTIWIN_ENABLED").getBoolean(null)
        }.getOrNull()
        val oneStep = runCatching {
            clazz.getField("IS_HW_MULTIWINDOW_MULTITASK_ONESTEPWIN").getBoolean(null)
        }.getOrNull()
        log(context, "honor oneStep capability HWMULTIWIN_ENABLED=$enabled ONESTEP=$oneStep")
        // 两个能力位都明确为 false 才判定关闭；读不到字段时保守放行
        !(enabled == false || (oneStep == null && enabled == false))
    }.getOrDefault(true)

    /** 系统白名单判定：isResizableApp(ActivityInfo)。方法不存在或调用失败都放行 */
    private fun resizable(context: Context, activityInfo: android.content.pm.ActivityInfo): Boolean {
        val result = runCatching {
            val clazz = Class.forName("com.hihonor.android.app.HwActivityTaskManager")
            val method = clazz.getDeclaredMethod("isResizableApp", android.content.pm.ActivityInfo::class.java)
            method.isAccessible = true
            method.invoke(null, activityInfo) as? Boolean
        }.onFailure {
            log(context, "honor isResizableApp unavailable: ${it.javaClass.simpleName}")
        }.getOrNull()
        log(context, "honor isResizableApp -> $result")
        return result != false
    }

    /**
     * 通过荣耀 ActivityManagerEx.startActivityOneStep 直拉原生轻窗（mode 102）。
     * 成功返回 true；类/方法不存在、能力未开放或调用被拒都返回 false，由上层走兜底。
     */
    fun launchOneStep(context: Context, component: ComponentName): Boolean {
        val mode = oneStepMode(context) ?: run {
            log(context, "honor oneStep skip: mode field unreadable")
            return false
        }
        if (!oneStepEnabled(context)) {
            log(context, "honor oneStep skip: capability disabled")
            return false
        }

        // 目标必须是导出的桌面入口；不可启动/非公开入口直接放弃
        val pm = context.packageManager
        val info = runCatching { pm.getActivityInfo(component, 0) }.getOrNull() ?: run {
            log(context, "honor oneStep skip: getActivityInfo failed for $component")
            return false
        }
        if (!info.exported || !info.enabled || !info.applicationInfo.enabled) {
            log(context, "honor oneStep skip: target not publicly launchable")
            return false
        }
        if (!resizable(context, info)) {
            log(context, "honor oneStep skip: system says app not resizable")
            return false
        }

        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ok = invokeOneStep(context, intent, mode)
        if (ok) log(context, "honor oneStep launch ok mode=$mode component=$component")
        return ok
    }

    /**
     * 反射调用 startActivityOneStep。
     * 真实签名是 (Context, Intent, int)——LSP 模块反汇编用 Integer.TYPE（原始类型）；
     * Java 反射参数类型必须精确匹配，用装箱 Integer.class 会 NoSuchMethodException
     * （v27 日志已验证）。先精确查原始类型，失败再枚举同名方法兜底并打日志。
     */
    private fun invokeOneStep(context: Context, intent: Intent, mode: Int): Boolean {
        val clazz = runCatching { Class.forName(AM_EX) }.getOrNull() ?: return false

        // 首选：精确签名 (Context, Intent, int)
        val primitive = runCatching {
            val method = clazz.getDeclaredMethod(
                "startActivityOneStep",
                Context::class.java,
                Intent::class.java,
                Integer::class.javaPrimitiveType,
            )
            method.isAccessible = true
            method.invoke(null, context, intent, mode)
            true
        }.onFailure {
            val cause = it.cause
            log(context, "honor oneStep primitive-int invoke failed: ${it.javaClass.simpleName}: ${cause?.message ?: it.message}")
        }.getOrDefault(false)
        if (primitive) return true

        // 兜底：枚举同名方法，按实际参数表构造调用，并记录真实签名供诊断
        val candidates = runCatching {
            clazz.declaredMethods.filter { it.name == "startActivityOneStep" }
        }.onFailure {
            log(context, "honor oneStep enumerate failed: ${it.javaClass.simpleName}: ${it.message}")
        }.getOrDefault(emptyList())

        if (candidates.isEmpty()) {
            log(context, "honor oneStep: no method visible (hidden API blocked or signature renamed)")
            return false
        }
        for (method in candidates) {
            val args = buildOneStepArgs(method.parameterTypes, context, intent, mode) ?: continue
            log(context, "honor oneStep try signature: (${method.parameterTypes.joinToString { it.simpleName }})")
            val ok = runCatching {
                method.isAccessible = true
                method.invoke(null, *args)
                true
            }.onFailure {
                log(context, "honor oneStep invoke failed: ${it.javaClass.simpleName}: ${it.cause?.message ?: it.message}")
            }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }

    /** 按参数表构造 startActivityOneStep 实参：Context/Intent/整数的常见组合 */
    private fun buildOneStepArgs(
        types: Array<Class<*>>,
        context: Context,
        intent: Intent,
        mode: Int,
    ): Array<Any?>? {
        var ctx: Context? = null
        var itn: Intent? = null
        var num: Int? = null
        var i = 0
        while (i < types.size) {
            when {
                Context::class.java.isAssignableFrom(types[i]) && ctx == null -> ctx = context
                Intent::class.java.isAssignableFrom(types[i]) && itn == null -> itn = intent
                (types[i] == Integer::class.javaPrimitiveType || types[i] == Integer::class.java) && num == null -> num = mode
                else -> return null // 出现 Bundle/UserHandle 等未知参数，不盲调
            }
            i++
        }
        if (itn == null || num == null) return null
        val args = mutableListOf<Any?>(itn)
        if (ctx != null) args.add(0, ctx)
        args.add(num)
        return args.toTypedArray()
    }

    /**
     * 尝试荣耀文档中的 ActivityManagerEx.changeActivityPosition。
     * 该方法主要面向「已有页面迁移到多窗口」，不同版本签名不一致；
     * 这里穷举常见参数形态，任一调用返回 true / 非 false 即视为命中。
     */
    fun changeActivityPosition(context: Context, pkg: String, component: ComponentName): Boolean {
        val clazz = runCatching { Class.forName(AM_EX) }.getOrNull() ?: return false
        val methods = clazz.declaredMethods.filter { it.name == "changeActivityPosition" }
        if (methods.isEmpty()) return false

        for (method in methods) {
            for (position in POSITION_CANDIDATES) {
                val args = buildArgs(method, context, pkg, component, position) ?: continue
                val ok = runCatching {
                    method.isAccessible = true
                    when (val result = method.invoke(null, *args)) {
                        null -> false
                        is Boolean -> result
                        is Number -> result.toInt() != 0
                        else -> true
                    }
                }.getOrDefault(false)
                if (ok) {
                    Log.d(TAG, "honor changeActivityPosition ok: ${method.parameterTypes.joinToString()} pos=$position")
                    return true
                }
            }
        }
        return false
    }

    private fun buildArgs(
        method: Method,
        context: Context,
        pkg: String,
        component: ComponentName,
        position: Int,
    ): Array<Any?>? {
        val types = method.parameterTypes
        return when (types.size) {
            1 -> when {
                types[0] == Int::class.javaPrimitiveType || types[0] == Int::class.javaObjectType -> arrayOf(position)
                types[0] == String::class.java -> arrayOf(pkg)
                types[0] == Bundle::class.java -> arrayOf(positionBundle(pkg, component, position))
                else -> null
            }
            2 -> when {
                types[0] == String::class.java && isInt(types[1]) -> arrayOf(pkg, position)
                types[0] == Context::class.java && isInt(types[1]) -> arrayOf(context, position)
                types[0] == ComponentName::class.java && isInt(types[1]) -> arrayOf(component, position)
                types[0] == Bundle::class.java && isInt(types[1]) ->
                    arrayOf(positionBundle(pkg, component, position), position)
                isInt(types[0]) && isInt(types[1]) -> arrayOf(position, position)
                else -> null
            }
            3 -> when {
                types[0] == String::class.java && types[1] == String::class.java && isInt(types[2]) ->
                    arrayOf(pkg, component.className, position)
                else -> null
            }
            else -> null
        }
    }

    private fun isInt(type: Class<*>): Boolean =
        type == Int::class.javaPrimitiveType || type == Int::class.javaObjectType

    /** 双写日志：logcat + 应用内日志 */
    private fun log(context: Context?, message: String) {
        Log.d(TAG, message)
        if (context != null) PilotLog.d(context, TAG, message)
    }

    private fun positionBundle(pkg: String, component: ComponentName, position: Int): Bundle =
        Bundle().apply {
            putString("package", pkg)
            putString("component", component.flattenToShortString())
            putInt("position", position)
            putInt("windowMode", 5)
        }
}
