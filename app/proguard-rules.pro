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
