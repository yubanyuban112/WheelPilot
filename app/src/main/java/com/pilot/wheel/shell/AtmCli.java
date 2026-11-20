package com.pilot.wheel.shell;

import java.lang.reflect.Method;

/**
 * shell 身份下的窗口操作入口，由 {@code app_process} 加载本 APK 后执行。
 *
 * <p>存在理由：MagicOS 把 {@code am task resize} 子命令砍了（实测抛
 * NumberFormatException，连 "resize" 这个 token 都被当整数解析），
 * {@code am stack resize} 在 Android 12+ 早已移除。而 IActivityTaskManager 的
 * binder 接口仍然完好，且 shell uid 持有 MANAGE_ACTIVITY_TASKS。
 *
 * <p>关键差别：{@code setTaskWindowingMode} 是 <b>task 级</b>操作，不经过
 * Activity 的 exported 检查 —— 因此可以把一个已经全屏的 task（含微信分享页、
 * 小红书详情页这类未导出的内部 Activity）原地转成浮窗，既不重投 Intent，
 * 也不打断 resultTo 回传链。这是 {@code am start -n <栈顶Activity>} 做不到的。
 *
 * <p>约束：本类必须能被 R8 keep（见 proguard-rules.pro），且只依赖反射，
 * 不 import 任何 android.* 符号 —— 它在非 app 进程里运行，没有 Context。
 *
 * <p>输出约定：成功打印 {@code OK ...} 且退出码 0；失败打印 {@code ERR ...}。
 */
public final class AtmCli {

    private static final String ATM = "android.app.ActivityTaskManager";
    private static final String IFACE = "android.app.IActivityTaskManager";
    private static final String RECT = "android.graphics.Rect";
    private static final String BINDER = "android.os.IBinder";

    private AtmCli() {
    }

    public static void main(String[] args) {
        String out;
        int code;
        try {
            out = run(args);
            code = out.startsWith("OK") ? 0 : 2;
        } catch (Throwable t) {
            out = "ERR " + t.getClass().getSimpleName() + ": " + message(t);
            code = 1;
        }
        System.out.println(out);
        System.out.flush();
        System.exit(code);
    }

    private static String run(String[] a) throws Exception {
        if (a.length < 3) {
            return "ERR usage: <windowing|resize> <taskId> <mode|rect>";
        }
        String op = a[0];
        int taskId = Integer.parseInt(a[1]);
        Object atm = service();
        if (atm == null) {
            return "ERR activity_task service unavailable";
        }

        if ("windowing".equals(op)) {
            int mode = Integer.parseInt(a[2]);
            Object ret = invoke(atm, "setTaskWindowingMode",
                    new Class<?>[]{int.class, int.class, boolean.class},
                    new Object[]{taskId, mode, Boolean.TRUE});
            return "OK windowing task=" + taskId + " mode=" + mode
                    + (ret == null ? "" : " ret=" + ret);
        }

        if ("resize".equals(op)) {
            String[] p = a[2].split(",");
            if (p.length != 4) {
                return "ERR rect needs l,t,r,b";
            }
            Class<?> rectCls = Class.forName(RECT);
            Object rect = rectCls
                    .getConstructor(int.class, int.class, int.class, int.class)
                    .newInstance(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()),
                            Integer.parseInt(p[2].trim()), Integer.parseInt(p[3].trim()));
            Object ret = resizeTask(atm, rectCls, taskId, rect);
            return "OK resize task=" + taskId + " rect=" + a[2]
                    + (ret == null ? "" : " ret=" + ret);
        }

        return "ERR unknown op " + op;
    }

    /**
     * 拿 IActivityTaskManager。优先用 ATMS 自己的 getService()（一步到位），
     * 拿不到再退回 ServiceManager + Stub.asInterface（部分 ROM 改了前者签名）。
     */
    private static Object service() throws Exception {
        try {
            Method m = Class.forName(ATM).getDeclaredMethod("getService");
            m.setAccessible(true);
            Object svc = m.invoke(null);
            if (svc != null) {
                return svc;
            }
        } catch (Throwable ignored) {
            // 落到下面的 ServiceManager 兜底
        }
        Class<?> sm = Class.forName("android.os.ServiceManager");
        Method get = sm.getDeclaredMethod("getService", String.class);
        get.setAccessible(true);
        Object binder = get.invoke(null, "activity_task");
        if (binder == null) {
            return null;
        }
        Class<?> stub = Class.forName(IFACE + "$Stub");
        Method asInterface = stub.getDeclaredMethod("asInterface", Class.forName(BINDER));
        asInterface.setAccessible(true);
        return asInterface.invoke(null, binder);
    }

    private static Object invoke(Object target, String name, Class<?>[] sig, Object[] args)
            throws Exception {
        Method m = Class.forName(IFACE).getMethod(name, sig);
        m.setAccessible(true);
        return m.invoke(target, args);
    }

    /**
     * resizeTask 在不同 API 上有 3 参和 4 参两种重载（后者多一个 preserveWindow），
     * 按参数个数优先选 3 参版本，避免不同 ROM 上签名不一致导致找不到方法。
     */
    private static Object resizeTask(Object atm, Class<?> rectCls, int taskId, Object rect)
            throws Exception {
        Method exact = null;
        Method loose = null;
        for (Method m : Class.forName(IFACE).getMethods()) {
            if (!"resizeTask".equals(m.getName())) {
                continue;
            }
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length < 3 || ps[0] != int.class || ps[2] != int.class) {
                continue;
            }
            if (!ps[1].isAssignableFrom(rectCls)) {
                continue;
            }
            if (ps.length == 3) {
                exact = m;
                break;
            }
            if (loose == null) {
                loose = m;
            }
        }
        Method use = exact != null ? exact : loose;
        if (use == null) {
            throw new NoSuchMethodException("resizeTask(int,Rect,int) not found");
        }
        Class<?>[] ps = use.getParameterTypes();
        Object[] vals = new Object[ps.length];
        vals[0] = taskId;
        vals[1] = rect;
        for (int i = 2; i < ps.length; i++) {
            vals[i] = ps[i] == boolean.class ? Boolean.FALSE : Integer.valueOf(0);
        }
        use.setAccessible(true);
        return use.invoke(atm, vals);
    }

    private static String message(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return msg == null ? cur.getClass().getName() : msg;
    }
}
