package com.pilot.wheel

import android.util.Log
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * 系统性探测 InputManager 的隐藏输入监听方法（探测项 2）。
 *
 * 在 shell(uid 2000) 进程里，反射取 InputManager 实例后，**逐一独立** try-catch
 * 以下三个目标签名（每个互不干扰，前一个失败不阻断后一个）：
 *   a) monitorGestureInput(String name, int displayId)      —— 客户端 facade 2 参签名
 *   b) monitorInput(String name, int displayId, int source) —— 3 参签名
 *   c) getInputMonitor()（若存在）                          —— 检查返回值
 *
 * 每个方法打印**完整异常链**（NoSuchMethodException / SecurityException /
 * InvocationTargetException 的 cause 逐层展开），绝不只吞一层。
 *
 * 结果以纯文本返回，供主进程跨进程打日志。本类只探测可用性，不改业务。
 */
@androidx.annotation.Keep
object InputManagerProbe {

    private const val TAG = "WheelPilot"

    private val IM_CLASS = "android.hardware.input.InputManager"

    /** 探测结果积累 */
    fun runAll(): String {
        val sb = StringBuilder()
        sb.append("== InputManagerProbe ==\n")

        // 0) 先枚举 InputManager 全部方法（关键诊断：确认 API 37 上哪些方法还在）
        sb.append(dumpMethods())

        val im: Any
        try {
            im = getInstance(sb)
            sb.append("InputManager 实例获取 ok, class=").append(im.javaClass.name).append('\n')
        } catch (t: Throwable) {
            sb.append("RESULT=getInstance_FAILED ").append(chain(t)).append('\n')
            return sb.toString()
        }

        // a) monitorGestureInput —— 客户端 facade 上是 (String,int)，binder 层是 (IBinder,String,int)
        sb.append(probeByName(im, "monitorGestureInput", "a"))
        // b) monitorInput
        sb.append(probeByName(im, "monitorInput", "b"))
        // c) getInputMonitor()
        sb.append(probeByName(im, "getInputMonitor", "c"))

        return sb.toString()
    }

    /**
     * 在给定实例的实际类上按「名字」找同名方法（不限参数个数），逐一反射调用。
     * 这样对 binder 层的 IInputManager$Stub$Proxy 也能正确命中 monitorGestureInput
     * 的真实签名（IBinder,String,int），而不是死磕客户端 facade 的 (String,int)。
     */
    private fun probeByName(im: Any, methodName: String, tag: String): String {
        val sb = StringBuilder()
        sb.append("--- [").append(tag).append("] ").append(methodName)
            .append("（实例类 ").append(im.javaClass.name).append("）---\n")

        val methods: Array<Method> = runCatching {
            // 在实例的实际类及其父类/接口上找同名方法
            val found = LinkedHashSet<Method>()
            var cls: Class<*>? = im.javaClass
            while (cls != null) {
                cls.declaredMethods.filter { it.name == methodName }.forEach { found.add(it) }
                cls = cls.superclass
            }
            found.toTypedArray()
        }.getOrElse {
            sb.append("RESULT[$tag]=REFLECT_FAIL ").append(chain(it)).append('\n')
            return sb.toString()
        }

        if (methods.isEmpty()) {
            sb.append("RESULT[$tag]=NO_SUCH_METHOD（").append(methodName)
                .append(" 在实例类及父类上均不存在）\n")
            return sb.toString()
        }

        for (m in methods) {
            sb.append("  签名: ").append(m.name).append('(')
                .append(m.parameterTypes.joinToString(",") { it.simpleName })
                .append(") -> ").append(m.returnType.simpleName).append('\n')

            // 按参数表填值：String→name、int→displayId(0)、IBinder→null(token 由系统分配)
            val ps = m.parameterTypes
            val args = arrayOfNulls<Any>(ps.size)
            var strSeen = false
            for (i in ps.indices) {
                when {
                    ps[i] == String::class.java && !strSeen -> {
                        args[i] = "pilot-probe-all"
                        strSeen = true
                    }
                    ps[i] == Int::class.javaPrimitiveType -> args[i] = 0
                    ps[i].isPrimitive -> args[i] = 0
                    // IBinder token：传 null（monitorGestureInput 的 token 由 InputManager 服务侧分配）
                    else -> args[i] = null
                }
            }

            try {
                m.isAccessible = true
                val ret = m.invoke(im, *args)
                if (ret == null) {
                    sb.append("  RESULT[$tag]=NULL_RETURN（调用成功但返回 null）\n")
                } else {
                    sb.append("  RESULT[$tag]=OK 返回类型=").append(ret.javaClass.name)
                        .append(" value=").append(ret.toString().take(120)).append('\n')
                    // 若返回 InputMonitor，尝试取 channel
                    runCatching {
                        val ch = ret.javaClass.getMethod("getInputChannel").invoke(ret)
                        sb.append("    getInputChannel() = ").append(ch).append('\n')
                    }.onFailure { sb.append("    getInputChannel() 失败: ").append(it.message).append('\n') }
                }
            } catch (e: InvocationTargetException) {
                sb.append("  RESULT[$tag]=INVOCATION_TARGET cause 链:\n    ")
                    .append(chain(e.cause ?: e)).append('\n')
            } catch (e: NoSuchMethodException) {
                sb.append("  RESULT[$tag]=NO_SUCH_METHOD ").append(chain(e)).append('\n')
            } catch (e: SecurityException) {
                sb.append("  RESULT[$tag]=SECURITY_EXCEPTION ").append(chain(e)).append('\n')
            } catch (t: Throwable) {
                sb.append("  RESULT[$tag]=OTHER ").append(chain(t)).append('\n')
            }
        }

        return sb.toString()
    }

    /** 枚举 InputManager 全部 public/declared 方法（定位 getInstance 重载与 monitor 方法） */
    private fun dumpMethods(): String {
        val sb = StringBuilder()
        sb.append("--- InputManager 全部方法 ---\n")
        try {
            val cls = Class.forName(IM_CLASS)
            val methods = cls.declaredMethods
            sb.append("declaredMethods 共 ").append(methods.size).append(" 个:\n")
            for (m in methods) {
                val isStatic = (m.modifiers and java.lang.reflect.Modifier.STATIC) != 0
                sb.append("  ").append(if (isStatic) "static " else "")
                    .append(m.name).append('(')
                    .append(m.parameterTypes.joinToString(",") { it.simpleName })
                    .append(") -> ").append(m.returnType.simpleName).append('\n')
            }
        } catch (t: Throwable) {
            sb.append("枚举方法失败: ").append(chain(t)).append('\n')
        }
        return sb.toString()
    }

    /** 多途径获取 InputManager 实例：getInstance() 无参 → getInstance(Context) → ServiceManager */
    private fun getInstance(sb: StringBuilder): Any {
        val cls = Class.forName(IM_CLASS)

        // 途径 1：getInstance() 无参静态
        try {
            val m = cls.getDeclaredMethod("getInstance")
            m.isAccessible = true
            val inst = m.invoke(null)
            if (inst != null) return inst
            sb.append("途径1 getInstance() 返回 null\n")
        } catch (e: NoSuchMethodException) {
            sb.append("途径1 getInstance() 无参不存在（API 37 可能已移除）: ").append(e.message).append('\n')
        } catch (e: Throwable) {
            sb.append("途径1 getInstance() 失败: ").append(chain(e)).append('\n')
        }

        // 途径 2：getInstance(Context) 单参静态
        try {
            val m = cls.getDeclaredMethod("getInstance", android.content.Context::class.java)
            m.isAccessible = true
            val inst = m.invoke(null, null as Any?)
            if (inst != null) return inst
            sb.append("途径2 getInstance(Context) 返回 null\n")
        } catch (e: NoSuchMethodException) {
            sb.append("途径2 getInstance(Context) 不存在\n")
        } catch (e: Throwable) {
            sb.append("途径2 getInstance(Context) 失败: ").append(chain(e)).append('\n')
        }

        // 途径 3：ServiceManager.getService("input") → IInputManager.Stub.asInterface
        try {
            val sm = Class.forName("android.os.ServiceManager")
            val get = sm.getDeclaredMethod("getService", String::class.java)
            get.isAccessible = true
            val binder = get.invoke(null, "input")
            sb.append("途径3 input binder = ").append(binder).append('\n')
            if (binder != null) {
                val iim = Class.forName("android.hardware.input.IInputManager")
                val stub = Class.forName("android.hardware.input.IInputManager\$Stub")
                val asInterface = stub.getDeclaredMethod("asInterface", Class.forName("android.os.IBinder"))
                asInterface.isAccessible = true
                val iimObj = asInterface.invoke(null, binder)
                if (iimObj != null) return iimObj
                sb.append("途径3 asInterface 返回 null\n")
            }
        } catch (e: Throwable) {
            sb.append("途径3 ServiceManager 失败: ").append(chain(e)).append('\n')
        }

        throw IllegalStateException("三种途径均无法获取 InputManager 实例")
    }

    /** 完整异常链：cause 逐层展开，避免只吞一层 */
    private fun chain(t: Throwable): String {
        val sb = StringBuilder()
        var cur: Throwable? = t
        var depth = 0
        while (cur != null && depth < 8) {
            if (depth > 0) sb.append(" <- ")
            sb.append(cur.javaClass.name).append(": ").append(cur.message)
            cur = cur.cause
            depth++
        }
        return sb.toString()
    }
}
