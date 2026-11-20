package com.pilot.wheel.shell;

import android.hardware.input.InputManager;
import android.os.Looper;
import android.util.Log;
import android.view.InputChannel;
import android.view.InputMonitor;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * shell 身份（uid 2000）下的 InputManager.monitorInput 最小验证入口，
 * 由 {@code app_process} 加载本 APK 后执行 —— 与 Shizuku UserService 完全相同的
 * shell 身份，用于绕开「Shizuku 未激活」直接验证 monitorInput 可用性。
 *
 * <p>用法（设备端）：
 * <pre>
 *   export CLASSPATH=/data/local/tmp/wheel.apk
 *   app_process /data/local/tmp com.pilot.wheel.shell.InputMonitorProbeCli
 * </pre>
 *
 * <p>只依赖反射 + 桩类签名（InputMonitor/InputChannel 运行时由系统真实类顶替）。
 * 输出约定：成功打印 {@code OK ...} 且退出码 0；失败打印 {@code ERR ...}。
 */
public final class InputMonitorProbeCli {

    private static final String TAG = "WheelPilot";

    private InputMonitorProbeCli() {
    }

    public static void main(String[] args) {
        // 补 hidden API 豁免（app_process 直接跑，没有 PilotShellService.init 的豁免逻辑）
        try {
            Class<?> bypass = Class.forName("org.lsposed.hiddenapibypass.HiddenApiBypass");
            bypass.getMethod("addHiddenApiExemptions", String[].class)
                    .invoke(null, (Object) new String[]{"L"});
        } catch (Throwable t) {
            System.out.println("warn: hidden api exemption failed: " + t);
        }
        String out;
        try {
            out = run();
        } catch (Throwable t) {
            out = "ERR " + t.getClass().getName() + ": " + message(t);
        }
        System.out.println(out);
        System.out.flush();
        Log.w(TAG, "InputMonitorProbeCli: " + out);
        System.exit(0);
    }

    private static String run() throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("== InputMonitorProbeCli (uid=").append(myUid()).append(") ==\n");
        try {
            Class<?> imCls = Class.forName("android.hardware.input.InputManager");

            // 1) 枚举 InputManager 全部方法（定位 MagicOS 实际提供了什么）
            sb.append("InputManager 全部方法：\n");
            for (Method m : imCls.getDeclaredMethods()) {
                boolean isStatic = (m.getModifiers() & java.lang.reflect.Modifier.STATIC) != 0;
                sb.append("  ").append(isStatic ? "static " : "")
                        .append(m.getName()).append('(');
                for (Class<?> p : m.getParameterTypes()) sb.append(p.getSimpleName()).append(',');
                sb.append(") -> ").append(m.getReturnType().getSimpleName()).append('\n');
            }

            // 2) 尝试获取 InputManager 实例（getInstance 无参 / getInstance(Context)）
            Object im = null;
            for (Method m : imCls.getDeclaredMethods()) {
                if (m.getName().equals("getInstance")) {
                    try {
                        m.setAccessible(true);
                        Object[] a = m.getParameterTypes().length == 0
                                ? new Object[0] : new Object[]{null};
                        im = m.invoke(null, a);
                        sb.append("取得实例 via ").append(m.getName()).append("(")
                                .append(m.getParameterTypes().length).append("参)\n");
                        break;
                    } catch (Throwable t) {
                        sb.append("getInstance 调用失败: ").append(t.getClass().getSimpleName())
                                .append(": ").append(t.getMessage()).append('\n');
                    }
                }
            }
            if (im == null) {
                // 途径 B：ServiceManager.getService("input") -> IInputManager
                sb.append("尝试 ServiceManager 拿 IInputManager...\n");
                try {
                    Class<?> sm = Class.forName("android.os.ServiceManager");
                    Method get = sm.getDeclaredMethod("getService", String.class);
                    get.setAccessible(true);
                    Object binder = get.invoke(null, "input");
                    sb.append("input binder = ").append(binder).append('\n');
                    Class<?> iim = Class.forName("android.hardware.input.IInputManager");
                    Class<?> stub = Class.forName("android.hardware.input.IInputManager$Stub");
                    Method asInterface = stub.getDeclaredMethod("asInterface",
                            Class.forName("android.os.IBinder"));
                    asInterface.setAccessible(true);
                    Object iimObj = asInterface.invoke(null, binder);
                    sb.append("IInputManager = ").append(iimObj).append('\n');
                    if (iimObj != null) {
                        // 直接调 IInputManager.monitorGestureInput(IBinder token, String, int)
                        for (Method m : iim.getDeclaredMethods()) {
                            if (m.getName().equals("monitorGestureInput")) {
                                m.setAccessible(true);
                                sb.append("调用 IInputManager.").append(m.getName()).append("...\n");
                                try {
                                    Object mon = m.invoke(iimObj, null, "pilot-monitor-probe", 0);
                                    if (mon == null) {
                                        sb.append("RESULT monitorGestureInput = NULL_RETURN\n");
                                    } else {
                                        sb.append("RESULT monitorGestureInput = OK -> ")
                                                .append(mon.getClass().getName()).append('\n');
                                        if (mon instanceof InputMonitor) {
                                            InputChannel ch = ((InputMonitor) mon).getInputChannel();
                                            sb.append("  getInputChannel() -> ").append(ch).append('\n');
                                            if (ch != null) {
                                                sb.append("  RESULT=REGISTERED channel=").append(ch.getName()).append('\n');
                                                sb.append(sampleEvents(ch));
                                            }
                                        }
                                    }
                                } catch (InvocationTargetException e) {
                                    Throwable c = e.getCause();
                                    sb.append("RESULT monitorGestureInput = INVOCATION_TARGET cause=")
                                            .append(c == null ? "null" : c.getClass().getName() + ": " + c.getMessage())
                                            .append('\n');
                                }
                            }
                        }
                    }
                } catch (Throwable t) {
                    sb.append("ServiceManager 路径失败: ").append(t.getClass().getSimpleName())
                            .append(": ").append(t.getMessage()).append('\n');
                }
                sb.append("RESULT=NO_INSTANCE (无法取得 InputManager 实例)\n");
                return sb.toString();
            }

            // 3) 探测 monitorInput 与 monitorGestureInput
            for (String name : new String[]{"monitorInput", "monitorGestureInput"}) {
                Method m = null;
                for (Method cand : imCls.getDeclaredMethods()) {
                    if (cand.getName().equals(name)) {
                        m = cand;
                        break;
                    }
                }
                if (m == null) {
                    sb.append("RESULT ").append(name).append(" = NO_SUCH_METHOD\n");
                    continue;
                }
                m.setAccessible(true);
                Class<?>[] ps = m.getParameterTypes();
                Object[] args = new Object[ps.length];
                boolean strSeen = false;
                for (int i = 0; i < ps.length; i++) {
                    if (ps[i] == String.class && !strSeen) {
                        args[i] = "pilot-monitor-probe";
                        strSeen = true;
                    } else if (ps[i] == int.class) {
                        args[i] = 0;
                    } else {
                        args[i] = null;
                    }
                }
                try {
                    Object ret = m.invoke(im, args);
                    if (ret == null) {
                        sb.append("RESULT ").append(name).append(" = NULL_RETURN\n");
                    } else {
                        sb.append("RESULT ").append(name).append(" = OK, 返回 ")
                                .append(ret.getClass().getName()).append('\n');
                        if (ret instanceof InputMonitor) {
                            InputChannel ch = ((InputMonitor) ret).getInputChannel();
                            sb.append("  getInputChannel() -> ").append(ch).append('\n');
                            if (ch != null) {
                                sb.append("  RESULT=REGISTERED channel=").append(ch.getName()).append('\n');
                                sb.append(sampleEvents(ch));
                            }
                        }
                    }
                } catch (InvocationTargetException e) {
                    Throwable c = e.getCause();
                    sb.append("RESULT ").append(name).append(" = INVOCATION_TARGET cause=")
                            .append(c == null ? "null" : c.getClass().getName() + ": " + c.getMessage())
                            .append('\n');
                }
            }
        } catch (ClassNotFoundException e) {
            sb.append("RESULT=CLASS_NOT_FOUND ").append(e.getMessage()).append('\n');
        } catch (SecurityException e) {
            sb.append("RESULT=SECURITY_EXCEPTION ").append(e.getMessage()).append('\n');
        } catch (Throwable t) {
            sb.append("RESULT=OTHER ").append(t.getClass().getName()).append(": ")
                    .append(t.getMessage()).append('\n');
        }
        return sb.toString();
    }

    /** 起 Looper + InputEventReceiver 收事件，采样 6 秒，统计收到的 DOWN/MOVE/UP */
    private static String sampleEvents(final InputChannel channel) {
        final StringBuilder sb = new StringBuilder();
        final int[] count = new int[]{0};
        Thread loop = new Thread(new Runnable() {
            @Override
            public void run() {
                Looper.prepare();
                Looper looper = Looper.myLooper();
                try {
                    com.pilot.wheel.InputEventReceiverBridge receiver =
                            new com.pilot.wheel.InputEventReceiverBridge(channel, looper,
                                    new com.pilot.wheel.InputEventReceiverBridge.Listener() {
                                        @Override
                                        public void onTouch(int action, float x, float y, long eventTime) {
                                            android.util.Log.w("WheelPilot",
                                                    "probe event action=" + action + " x=" + x + " y=" + y);
                                        }
                                    });
                    sb.append("InputEventReceiverBridge registered, sampling 6s (touch screen now)...\n");
                    new android.os.Handler(looper).postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            sb.append("RESULT=SAMPLED (见 logcat WheelPilot 的 probe event# 行)\n");
                            Looper.myLooper().quit();
                        }
                    }, 6000L);
                    Looper.loop();
                } catch (Throwable t) {
                    sb.append("RESULT=RECEIVER_FAILED ")
                            .append(t.getClass().getName()).append(": ").append(t.getMessage())
                            .append('\n');
                }
            }
        }, "input-monitor-loop");
        loop.setDaemon(true);
        loop.start();
        try {
            loop.join(8000L);
        } catch (InterruptedException ignored) {
        }
        return sb.toString();
    }

    private static int myUid() {
        try {
            Method m = Class.forName("android.os.Process").getDeclaredMethod("myUid");
            m.setAccessible(true);
            return (Integer) m.invoke(null);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static String message(Throwable t) {
        StringBuilder sb = new StringBuilder();
        while (t != null) {
            if (sb.length() > 0) sb.append(" <- ");
            sb.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage());
            t = t.getCause();
        }
        return sb.toString();
    }
}
