package com.pilot.wheel;

/**
 * 常驻 shell 身份通道。
 *
 * 跑在 Shizuku 拉起的 UserService 进程里（uid 2000），进程常驻，每次调用只是一次
 * binder 往返 —— 取代「每次 app_process 冷启一个 JVM」的老路（数百毫秒）。
 *
 * 返回值统一：0 = 成功，负数 = 失败原因码（见 PilotShellService 的常量）。
 */
interface IPilotShellService {

    /** 把已有 task 原地改成指定窗口模式。modeOnTop=true 时同时把 task 提到前台。 */
    int setTaskWindowingMode(int taskId, int mode, boolean onTop) = 1;

    /** 只改几何：把 task 拉到指定矩形，不动窗口模式。 */
    int resizeTask(int taskId, int left, int top, int right, int bottom) = 2;

    /**
     * 重聚焦：先 setFocusedTask(-1) 失焦、再设成目标 task。
     * 用于「已在后台的 task 转窗后回到入口页」问题 —— moveTaskToFront 会把栈重置。
     */
    int refocusTask(int taskId) = 3;

    /**
     * 以 shell 身份直调 startActivityAsUser：启动源头就是目标窗口模式，
     * 不再「先全屏铺开、再缩成窗」。quiet=true 关窗口动画。
     */
    int startActivityAsUser(
        String pkg,
        String cls,
        int userId,
        int windowingMode,
        int left,
        int top,
        int right,
        int bottom,
        boolean quiet
    ) = 4;

    /** 移除 task（清理残留 freeform 窗口用，等价 am task remove）。 */
    int removeTask(int taskId) = 5;

    /** dumpsys activity activities 全文。走驻留进程的 sh，省掉一次 priv-kit 往返。 */
    String dumpTasks() = 6;

    /**
     * 探测触摸屏输入设备：执行 getevent -lp，返回支持 ABS_MT_POSITION_X/Y 的设备路径
     * 及其坐标 max 值。格式一行一个设备：`/dev/input/eventN|xMax|yMax`。
     * 无可用设备返回空串。这是「旁路读原始触摸」的前置步骤，只读不消费。
     */
    String probeTouchDevices() = 7;

    /**
     * 探测诊断：返回 `getevent -lp` 的 stdout 长度、stderr 前 300 字、以及
     * /dev/input 目录 ls -l 前 500 字，用 `\n` 分段。供主进程直接打日志，
     * 避免跨进程 Log 不可见导致无法定位「没权限」还是「解析不匹配」。
     */
    String probeTouchDiagnostics() = 11;

    /**
     * 启动旁路触摸采集：在驻留进程里 `getevent -lt <device>` 并持续解析，
     * 把归一化前的原始触点流通过 [com.pilot.wheel.PilotShellClient] 的注册回调推给用户态。
     * 返回值：0=已启动，负数为失败（无设备/无读权限/已在跑）。
     */
    int startRawTouch(String device) = 8;

    /** 停止旁路触摸采集，关闭 getevent 子进程与读取线程。 */
    void stopRawTouch() = 9;

    /**
     * 注册原始触点回调（用户态持有 IRawTouchCallback binder，驻留进程反调）。
     * 必须在 startRawTouch 之前调用一次；传 null 注销。
     */
    void attachRawTouchCallback(in com.pilot.wheel.IRawTouchCallback cb) = 10;

    /**
     * 最小可行性验证：在 shell 身份进程里反射调用 InputManager.monitorInput，
     * 注册全局输入监听并尝试收 MotionEvent。返回结构化结果字符串（含失败层级：
     * NO_SUCH_METHOD / SECURITY_EXCEPTION / INVOCATION_TARGET 等），供主进程打日志。
     * 不改任何业务逻辑，仅探测可用性。
     */
    String startInputMonitorProbe() = 12;

    /** 停止 InputMonitor 探测，释放 channel 与 monitor。 */
    void stopInputMonitorProbe() = 13;

    /**
     * 启动全局输入旁路监听（monitorInput 方案，不读 /dev/input）。
     * 在 shell 身份进程里反射调用 InputManager.monitorInput，收到 MotionEvent 后
     * 通过已注册的 IRawTouchCallback.onMotionEvent 回传屏幕逻辑坐标。
     * 返回 0=成功；负数=失败（无权限/方法缺失/返回 null）。
     */
    int startMonitorTouch() = 14;

    /** 停止全局输入旁路监听，释放 InputMonitor 与接收线程。 */
    void stopMonitorTouch() = 15;

    /**
     * 系统性触摸感知通道探测（诊断入口，不改业务）。
     * 在 shell 身份进程里依次执行：
     *   1) Native 直读 /dev/input/event*（O_RDONLY|O_NONBLOCK，失败打印 errno 字符串；
     *      未编译 .so 时如实标注 NOT_COMPILED）；
     *   2) 反射 InputManager 逐一探测 monitorGestureInput(String,int)、
     *      monitorInput(String,int,int)、getInputMonitor() 三个签名，
     *      每个单独 try-catch 打印完整异常链。
     * 返回结构化纯文本（含 errno / avc 提示 / 异常链），供主进程打日志。
     */
    String probeAllTouchChannels() = 16;

    /**
     * 落盘诊断日志（荣耀 Android 17 过滤第三方 logcat，主进程无法直写 /data/local/tmp）。
     * 由 uid 2000 的 UserService 追加写到 /data/local/tmp/wheelpilot_trace.log，
     * adb shell cat 可读。返回 0=成功，负数=失败。仅诊断用途，不影响业务。
     */
    int writeTrace(String line) = 17;

    /**
     * 以 shell 身份读剪贴板文本（58 号）。
     *
     * Android 10+ 把后台应用读剪贴板判为空，`OnPrimaryClipChangedListener` 在后台也常不回调；
     * shell（uid 2000）不受该限制，故由常驻进程代读。实现走
     * `ServiceManager("clipboard") → IClipboard.getPrimaryClip` 反射。
     *
     * 返回：文本；剪贴板是 Uri 时返回 `URI:<uri>`；空剪贴板返回空串；
     * 失败返回 `ERR:<原因>`（供主进程落盘区分「读不到」与「没有内容」）。
     */
    String readClipboardText() = 18;

    /** 关掉常驻进程。 */
    void destroy() = 16777114;
}
