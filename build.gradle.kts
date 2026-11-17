plugins {
    // AGP 9.x 内置 Kotlin 编译，不再需要 kotlin.android 插件
    id("com.android.application") version "9.5.1" apply false
    // Compose 编译器插件仍然是必需的
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
