import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 签名凭据全部来自 local.properties（该文件不入版本库）。
// 缺失时不做签名配置，release 直接产出未签名 APK —— 克隆仓库的人无需密钥也能构建。
val localProps = Properties()
val localPropsFile = rootProject.file("local.properties")
if (localPropsFile.exists()) {
    localPropsFile.inputStream().use { localProps.load(it) }
}
val keystorePath: String? = localProps.getProperty("WHEEL_KEYSTORE_FILE")
val keystoreFile = if (keystorePath != null) rootProject.file(keystorePath) else rootProject.file("pilot-release.jks")
val keystorePassword: String? = localProps.getProperty("WHEEL_KEYSTORE_PASSWORD")
val hasSigningKey = keystoreFile.exists() && !keystorePassword.isNullOrEmpty()

android {
    namespace = "com.pilot.wheel"
    // priv-kit 0.21.0 的 AAR 要求 compileSdk >= 37
    compileSdk = 37
    // 固定 NDK 版本，探测项 1 的 native_input_probe.so 依赖
    ndkVersion = "26.3.11579264"

    signingConfigs {
        if (hasSigningKey) {
            create("release") {
                storeFile = keystoreFile
                storePassword = keystorePassword
                keyAlias = localProps.getProperty("WHEEL_KEY_ALIAS") ?: "pilot"
                keyPassword = keystorePassword
                // 三种签名全开：v1 兼容老安装器和第三方工具，v2/v3 用于新系统
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    defaultConfig {
        applicationId = "com.pilot.wheel"
        minSdk = 30
        // targetSdk 保持 35，避免 Android 17 的新运行时行为影响特权服务
        targetSdk = 35
        versionCode = 137
        versionName = "3.24.28"

        // 瘦身：真机只需 arm 两种 ABI，去掉 x86 / x86_64 模拟器的 so
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            // 正式签名，才能发给别人安装；没有密钥时输出未签名包
            if (hasSigningKey) signingConfig = signingConfigs.getByName("release")
            // 瘦身：开启 R8 代码压缩 + 资源收缩，这是 dex 从 24MB 降下来的关键
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
        aidl = true
    }

    // 探测项 1：native 直读 /dev/input 的 .so（见 app/src/main/cpp/）
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    lint {
        // BlockedPrivateApi 已在 lint.xml 里按文件抑制（见 shell/AtmCli.java 的运行环境说明）
        lintConfig = file("lint.xml")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // 特权服务：自带 Root / ADB / 手动 三条启动路径
    implementation("io.github.priv-kit:priv-ui:0.21.0")

    // 运行时 hidden API 豁免，priv-kit 必需
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.2")

    // Shizuku 作为外部启动方式（UserService 桥接）
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    // 单元测试（纯 JVM 逻辑：几何/候选模式/解析/分组）
    testImplementation("junit:junit:4.13.2")
}
