package com.pilot.wheel;

import android.util.Log;
import android.view.InputChannel;
import android.view.InputEvent;
import android.view.InputEventReceiver;

/**
 * 继承 hidden 类 InputEventReceiver 的桥接子类，用于接收 monitorInput 的事件流。
 *
 * 编译期：InputEventReceiver / InputChannel / InputMonitor 均由 src/main/java/android/view/
 * 下的桩类提供签名（compile-only，不打包）；运行时由真机 framework 的真实类顶替。
 *
 * 收到事件后在 onInputEvent 里解析 MotionEvent 的 action / 坐标 / 事件时间，
 * 转成轻量回调交给 [Listener]，然后 finishInputEvent(event, false) 表示「未消费」——
 * 这保证事件仍会正常派发给真正的目标窗口（纯旁路监听，不拦截、不影响下层点击）。
 *
 * 53 号改动（维度一）：
 *  1. 反射 Method 改为**静态缓存**：原实现每个事件做 1 次 Class.forName + 4 次 getMethod，
 *     120–240Hz 下每秒上千次反射查表全压在采集线程上；
 *  2. 新增 finish 耗时环形统计与 [health]：「通道建立成功」升级为「通道仍在消费且消费得动」。
 *
 * 线程归属：onInputEvent 在采集线程（shell 进程 input-monitor-loop）执行；
 * health() 可被任意线程读取（synchronized 保护环形缓冲）。
 */
@androidx.annotation.Keep
public final class InputEventReceiverBridge extends InputEventReceiver {

    /** 事件监听器：把 MotionEvent 精简成 (action, x, y, eventTime) 回调。 */
    public interface Listener {
        void onTouch(int action, float x, float y, long eventTime);
    }

    private static final String TAG = "WheelPilot";
    private static final String ME_CLASS = "android.view.MotionEvent";

    /** 反射结果静态缓存：类与方法只解析一次，之后纯 invoke */
    private static final Class<?> ME;
    private static final java.lang.reflect.Method M_ACTION;
    private static final java.lang.reflect.Method M_RAW_X;
    private static final java.lang.reflect.Method M_RAW_Y;
    private static final java.lang.reflect.Method M_EVENT_TIME;

    static {
        Class<?> c = null;
        java.lang.reflect.Method a = null, x = null, y = null, t = null;
        try {
            c = Class.forName(ME_CLASS);
            a = c.getMethod("getAction");
            x = c.getMethod("getRawX");
            y = c.getMethod("getRawY");
            t = c.getMethod("getEventTime");
        } catch (Throwable ignore) {
            // framework 缺失时保持 null，dispatch 里直接跳过，不抛
        }
        ME = c;
        M_ACTION = a;
        M_RAW_X = x;
        M_RAW_Y = y;
        M_EVENT_TIME = t;
    }

    // ---- 消费闭环统计（维度一 · 第 2 项） ----
    private static final int RING_SIZE = 64;
    private static final int[] RING = new int[RING_SIZE];
    private static final Object LOCK = new Object();
    private static long sCount = 0L;
    private static long sLastFinishAt = 0L;
    private static int sMax = 0;

    private final Listener listener;

    public InputEventReceiverBridge(InputChannel inputChannel, android.os.Looper looper, Listener listener) {
        super(inputChannel, looper);
        this.listener = listener;
    }

    @Override
    public void onInputEvent(InputEvent event) {
        long t0 = android.os.SystemClock.uptimeMillis();
        try {
            dispatch(event);
        } catch (Throwable t) {
            Log.w(TAG, "input-monitor: dispatch threw " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        // 未消费：事件照常派发给真正的目标窗口（旁路监听，不拦截下层）
        finishInputEvent(event, false);
        long cost = android.os.SystemClock.uptimeMillis() - t0;
        synchronized (LOCK) {
            RING[(int) (sCount % RING_SIZE)] = (int) cost;
            if (cost > sMax) sMax = (int) cost;
            sCount++;
            sLastFinishAt = t0;
        }
    }

    @Override
    public void onBatchedInputEventPending() {
        consumeBatchedInputEvents(-1);
    }

    private void dispatch(InputEvent event) throws Exception {
        if (event == null || listener == null) return;
        if (ME == null || M_ACTION == null || M_RAW_X == null || M_RAW_Y == null || M_EVENT_TIME == null) return;
        if (!ME.isInstance(event)) {
            // 非触摸事件（如 KeyEvent），旁路不关心
            return;
        }
        int action = (Integer) M_ACTION.invoke(event);
        float x = (Float) M_RAW_X.invoke(event);
        float y = (Float) M_RAW_Y.invoke(event);
        long eventTime = (Long) M_EVENT_TIME.invoke(event);
        listener.onTouch(action, x, y, eventTime);
    }

    /**
     * 健康快照：`count / p95 / max / idle`（毫秒）。
     *
     * 判读：p95 持续偏高 → 本端消费变慢（dispatcher 侧会背压）；
     * idle 过大 → 通道建好了却收不到事件（OEM 裁剪派发）。
     * 这两种情况都无法用「started && receiver != null」表达，故单独暴露。
     */
    public static String health() {
        int n;
        int p95;
        int max;
        long idle;
        synchronized (LOCK) {
            n = (int) Math.min(sCount, RING_SIZE);
            max = sMax;
            idle = sLastFinishAt == 0L ? -1L : android.os.SystemClock.uptimeMillis() - sLastFinishAt;
            if (n == 0) {
                p95 = 0;
            } else {
                int[] copy = new int[n];
                System.arraycopy(RING, 0, copy, 0, n);
                java.util.Arrays.sort(copy);
                int idx = Math.min(n - 1, (int) Math.ceil(n * 0.95) - 1);
                p95 = copy[Math.max(0, idx)];
            }
        }
        return "count=" + n + " p95=" + p95 + " max=" + max + " idle=" + idle;
    }

    /** 通道重启时清零，避免旧统计把新通道误判成 DEGRADED */
    public static void resetHealth() {
        synchronized (LOCK) {
            java.util.Arrays.fill(RING, 0);
            sCount = 0L;
            sLastFinishAt = 0L;
            sMax = 0;
        }
    }
}
