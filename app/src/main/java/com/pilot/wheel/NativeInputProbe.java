package com.pilot.wheel;

import android.util.Log;

/**
 * Native 直读 /dev/input 探测（探测项 1）的 Java 侧入口。
 *
 * 通过 JNI 调用 {@code native_input_probe.cpp} 的 nativeProbe()，在 shell(uid 2000)
 * 进程里直接 open()/read() /dev/input/event*，绕过 getevent 子进程。
 *
 * 编译约束：当前构建机 SDK 无 NDK/CMake，libnative_input_probe.so 未编译进 APK。
 * 这里用 {@link System#loadLibrary} 延迟加载 —— .so 缺失时抛 UnsatisfiedLinkError，
 * 自动判定为 NOT_COMPILED 并如实标注，绝不虚构成功。
 *
 * 待 .so 编译并放入 jniLibs/arm64-v8a + armeabi-v7a 后，本类无需改动即可工作。
 */
@androidx.annotation.Keep
public final class NativeInputProbe {

    private static final String TAG = "WheelPilot";

    private static volatile boolean loaded = false;

    private NativeInputProbe() {
    }

    /**
     * 执行 native 探测。返回结构化纯文本：
     *  - .so 已加载：nativeProbe() 的输出（OPEN_OK/OPEN_FAIL + errno 字符串 + type/code/value）；
     *  - .so 未编译/加载失败：以 "RESULT=NOT_COMPILED ..." 开头的说明，附原始异常类名。
     * 永不抛异常。
     */
    public static String probe() {
        if (!loaded) {
            try {
                System.loadLibrary("native_input_probe");
                loaded = true;
            } catch (UnsatisfiedLinkError e) {
                final String reason = e.getMessage();
                Log.w(TAG, "NativeInputProbe: .so not available (" + reason + "), marking NOT_COMPILED");
                return "== NativeInputProbe ==\n"
                        + "RESULT=NOT_COMPILED (libnative_input_probe.so 未编译进 APK；"
                        + "本机 SDK 无 NDK/CMake。原始错误: "
                        + (reason == null ? e.getClass().getSimpleName() : reason) + ")\n"
                        + "提示：native open()/read() 与 getevent 走同一条内核权限路径，"
                        + "native 不改变权限模型；预计与 getevent 同被 EACCES/SELinux 拒绝。\n";
            } catch (Throwable t) {
                Log.w(TAG, "NativeInputProbe: loadLibrary failed " + t.getClass().getSimpleName()
                        + ": " + t.getMessage());
                return "== NativeInputProbe ==\nRESULT=NOT_COMPILED (loadLibrary failed "
                        + t.getClass().getName() + ": " + t.getMessage() + ")\n";
            }
        }
        try {
            return nativeProbe();
        } catch (Throwable t) {
            Log.w(TAG, "NativeInputProbe: nativeProbe threw " + t.getClass().getSimpleName()
                    + ": " + t.getMessage());
            return "== NativeInputProbe ==\nRESULT=NATIVE_THREW "
                    + t.getClass().getName() + ": " + t.getMessage() + "\n";
        }
    }

    /** 由 native_input_probe.cpp 实现（JNI 符号 Java_com_pilot_wheel_NativeInputProbe_nativeProbe） */
    private static native String nativeProbe();

    /**
     * 启动 native 直读触摸采集线程（替换 getevent 子进程）。
     *
     * @param path 设备路径，如 /dev/input/event10
     * @return 0=成功；-1=open 失败；-2=回调方法解析失败；-3=已在采集
     */
    private static native int nativeStartCollect(String path);

    /** 停止 native 采集线程（置停止标志并 join，最迟约 200ms 返回）。 */
    private static native void nativeStopCollect();

    /**
     * 触点事件回调入口（由 native 采集线程通过 JNI 调用）。
     * 签名必须与 native_input_probe.cpp 里 GetStaticMethodID 的 "(IFFI)V" 一致。
     *
     * @param type 0=DOWN / 1=MOVE / 2=UP
     * @param rawX/rawY 原始坐标（未归一化）
     * @param slot 触点槽位
     */
    public static void onNativeTouch(int type, float rawX, float rawY, int slot) {
        final Emitter e = emitter;
        if (e != null) {
            e.emit(type, rawX, rawY, slot);
        }
    }

    /**
     * 69 号：native 采集线程退出回调入口（由 native_input_probe.cpp 的 collectLoop 在
     * poll/read 失败、异常退出时通过 JNI 调用）。
     *
     * 只在此处收到「异常退出」信号；主动 stop 走 nativeStopCollect 置 running=false 自然退出，
     * native 侧不会回调本方法。
     *
     * @param reason 1=poll 失败；2=read EOF；3=read 致命错误
     */
    @androidx.annotation.Keep
    public static void onNativeCollectExit(int reason) {
        final CollectExitListener l = collectExitListener;
        if (l != null) {
            l.onExit(reason);
        }
    }

    /** native 采集线程异常退出的监听器（由 PilotShellService 注入） */
    public interface CollectExitListener {
        void onExit(int reason);
    }

    @androidx.annotation.Keep
    private static volatile CollectExitListener collectExitListener = null;

    /** 注入退出监听器；传 null 清空。 */
    public static void setCollectExitListener(CollectExitListener l) {
        collectExitListener = l;
    }

    /** native 采集线程的触点消费者（由 PilotShellService 注入，等价旧 emit(cb,...)） */
    public interface Emitter {
        void emit(int type, float rawX, float rawY, int slot);
    }

    @androidx.annotation.Keep
    private static volatile Emitter emitter = null;

    /** 注入触点消费者；传 null 清空。 */
    public static void setEmitter(Emitter e) {
        emitter = e;
    }

    /** native 采集是否可用（.so 已加载）。 */
    public static boolean isNativeAvailable() {
        ensureLoaded();
        return loaded;
    }

    /**
     * 启动 native 直读采集。
     * @return 0=成功；负数=失败（-1 open 失败；-2 方法缺失；-3 已在跑；-99 .so 未编译）
     */
    public static int startCollect(String path) {
        if (!ensureLoaded()) return -99;
        try {
            return nativeStartCollect(path);
        } catch (Throwable t) {
            Log.w(TAG, "NativeInputProbe.startCollect threw " + t.getClass().getSimpleName()
                    + ": " + t.getMessage());
            return -99;
        }
    }

    /** 停止 native 采集（幂等）。 */
    public static void stopCollect() {
        if (!loaded) return;
        try {
            nativeStopCollect();
        } catch (Throwable t) {
            Log.w(TAG, "NativeInputProbe.stopCollect threw " + t.getClass().getSimpleName());
        }
    }

    /** 延迟加载 .so（与 probe() 共用），成功返回 true。 */
    private static boolean ensureLoaded() {
        if (loaded) return true;
        try {
            System.loadLibrary("native_input_probe");
            loaded = true;
        } catch (UnsatisfiedLinkError e) {
            return false;
        } catch (Throwable t) {
            return false;
        }
        return true;
    }

    /**
     * native 采集自测：启动采集线程，捕获首个 DOWN 即停（最长等待 timeoutMs），
     * 验证「native read → 解析 → JNI 回调 → Java emitter」全链路。
     * 不依赖无障碍服务，可直接在 shell 进程里独立验证。
     *
     * 相比固定 sleep：改为「轮询等首个 DOWN」，消除「4 秒窗口内没摸到屏幕」的
     * 触摸时机问题 —— 用户只要在窗口内摸一下即抓到坐标并立即停止。
     *
     * @param path 设备路径，如 /dev/input/event8
     * @param timeoutMs 最长等待上限（超时未捕获 DOWN 也返回，避免线程卡死）
     * @return 结构化统计文本（DOWN/MOVE/UP 计数 + 首样本坐标）。
     */
    public static String selfTestCollect(String path, int timeoutMs) {
        if (!ensureLoaded()) {
            return "RESULT=NOT_COMPILED (libnative_input_probe.so 未编译进 APK)";
        }
        final int[] counts = new int[3]; // DOWN / MOVE / UP
        final float[][] samples = new float[3][2]; // 每类首样本坐标
        final boolean[] hasSample = new boolean[3];
        final Object lock = new Object();
        setEmitter((type, x, y, slot) -> {
            if (type < 0 || type > 2) return;
            counts[type]++;
            if (!hasSample[type]) {
                samples[type][0] = x;
                samples[type][1] = y;
                hasSample[type] = true;
            }
            // 捕获首个 DOWN 即唤醒等待方（通知其可提前停止）
            if (type == 0) {
                synchronized (lock) { lock.notifyAll(); }
            }
        });
        final int rc = startCollect(path);
        if (rc != 0) {
            setEmitter(null);
            return "RESULT=START_FAIL rc=" + rc + " (open 失败或已在采集)";
        }
        final long deadline = SystemClockMillis() + timeoutMs;
        synchronized (lock) {
            while (counts[0] == 0) {
                final long remain = deadline - SystemClockMillis();
                if (remain <= 0) break;
                try {
                    lock.wait(Math.min(remain, 500L));
                } catch (InterruptedException ignored) {
                    break;
                }
            }
        }
        stopCollect();
        setEmitter(null);
        StringBuilder sb = new StringBuilder();
        sb.append("RESULT=COLLECTED path=").append(path)
          .append(" down=").append(counts[0])
          .append(" move=").append(counts[1])
          .append(" up=").append(counts[2]).append('\n');
        if (hasSample[0]) sb.append("  DOWN sample x=").append(samples[0][0]).append(" y=").append(samples[0][1]).append('\n');
        if (hasSample[1]) sb.append("  MOVE sample x=").append(samples[1][0]).append(" y=").append(samples[1][1]).append('\n');
        if (hasSample[2]) sb.append("  UP   sample x=").append(samples[2][0]).append(" y=").append(samples[2][1]).append('\n');
        if (counts[0] == 0) {
            sb.append("  提示：").append(timeoutMs).append("ms 内未捕获 DOWN，请确认采集期间触摸了屏幕，且设备确为触摸屏。\n");
        }
        return sb.toString();
    }

    /** 单调时钟毫秒（自测等待用，避免依赖 System.currentTimeMillis 的墙钟跳变） */
    private static long SystemClockMillis() {
        return android.os.SystemClock.elapsedRealtime();
    }
}
