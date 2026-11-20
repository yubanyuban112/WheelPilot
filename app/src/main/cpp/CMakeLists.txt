# CMakeLists.txt — native 直读 /dev/input 探测（探测项 1）
#
# 当前构建机 SDK 无 NDK/CMake，此文件默认不挂入 app/build.gradle.kts 的
# externalNativeBuild。需要编译 .so 时：
#   1) 安装 NDK + CMake 到 SDK；
#   2) 在 app/build.gradle.kts 加 externalNativeBuild { cmake { path = "src/main/cpp/CMakeLists.txt" } }；
#   3) 重新 assembleRelease。
# Java 侧 NativeInputProbe 在 .so 缺失时用 System.loadLibrary 失败自动跳过，
# 结果如实标注 NOT_COMPILED，不会虚构成功。

cmake_minimum_required(VERSION 3.18.1)
project(native_input_probe LANGUAGES CXX)

add_library(native_input_probe SHARED native_input_probe.cpp)

target_compile_features(native_input_probe PRIVATE cxx_std_17)

# pthread：std::thread（旁路触摸采集线程）依赖
find_package(Threads REQUIRED)

find_library(log-lib log)
target_link_libraries(native_input_probe ${log-lib} Threads::Threads)
