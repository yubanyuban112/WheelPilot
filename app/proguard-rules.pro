# ============================================================================
# 包体瘦身：只 keep 真正走反射 / JNI / AIDL 的部分，其余交给 R8 裁剪
# ============================================================================

# hiddenapibypass：纯反射调用 hidden API，必须整体保留
-keep class org.lsposed.hiddenapibypass.** { *; }
-dontwarn org.lsposed.hiddenapibypass.**

# priv-kit：特权启动库内部大量反射与 Compose 资源，整体保留其自有类；
# 依赖的 Shizuku 接口经 AIDL / 反射调用，也保留
-keep class priv.kit.** { *; }
-dontwarn priv.kit.**
-keep class rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**

# AIDL 生成的 Binder 类由系统反射实例化，保留
-keep class com.pilot.wheel.IPilotShizukuStartService** { *; }
-keep class com.pilot.wheel.PilotShizukuStartService** { *; }
# 常驻 shell 通道：Shizuku 用类名反射实例化 UserService，AIDL 桩也必须保留
-keep class com.pilot.wheel.IPilotShellService** { *; }
-keep class com.pilot.wheel.PilotShellService** { *; }

# InputManager.monitorInput 最小验证模块：全部保留（含反射入口与桥接子类）
-keep class com.pilot.wheel.InputMonitorProbe** { *; }
-keep class com.pilot.wheel.InputEventReceiverBridge** { *; }
-keep class com.pilot.wheel.InputMonitorProbeReceiver** { *; }

# Native 直读 /dev/input：native 方法名与 onNativeTouch 回调签名必须原样保留，
# native_input_probe.cpp 通过 GetStaticMethodID("onNativeTouch","(IFFI)V") 精确查找，
# 一旦 R8 改名/裁剪，native 侧 CallStaticVoidMethod 会 ClassNotFound / NoSuchMethod。
-keep class com.pilot.wheel.NativeInputProbe { *; }

# 编译期桩类：运行时由真机 framework 真实类顶替，APK 内不得保留这些 android.* 桩，
# 否则可能与系统类冲突。bridge 继承关系在编译期已固化，运行时指向系统真实类。
-dontwarn android.view.InputEventReceiver
-dontwarn android.view.InputMonitor
-dontwarn android.view.InputChannel

# shell 侧 app_process 入口：类名由命令行硬编码传入，main 由 ART 直接调用。
# R8 一旦改名或裁掉，app_process 就 ClassNotFoundException，binder 通道整条失效。
-keep class com.pilot.wheel.shell.** { *; }
-keepclassmembers class com.pilot.wheel.shell.AtmCli {
    public static void main(java.lang.String[]);
}

# Compose：不整体 keep。Compose 编译器生成的是普通字节码，R8 可安全裁剪。
# Compose runtime / material3 自身随 AAR 附带了 consumer proguard 规则。
-dontwarn androidx.compose.**
-dontwarn androidx.lifecycle.**
-dontwarn kotlinx.serialization.**

# kotlinx.serialization：保留注解与序列化器入口
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }

# 通用：保留行号便于崩溃定位（体积极小）
-keepattributes SourceFile, LineNumberTable, RuntimeExceptionDescriptions
-renamesourcefileattribute SourceFile

# release 里把 Log.v/d/i 打桩，轻微减小体积并减少运行时开销
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
}
