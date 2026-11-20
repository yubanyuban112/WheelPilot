package com.pilot.wheel.diag

/**
 * 旁路触摸的全局计数聚合（53 号 · 维度四）。
 *
 * 设计约束：**只写计数，不做 I/O**。所有自增都在调用点所在线程完成，
 * 快照线程只读 `@Volatile` 值 —— 因此不会在主线程/采集线程引入任何阻塞，
 * 也不会出现「为了可观测性把主线程拖慢」的回退。
 */
object TouchStats {

    // ---- 分流（routing 线程写） ----
    @Volatile
    var routeCalls: Long = 0L

    @Volatile
    var dispatchToMain: Long = 0L

    // ---- 边缘（routing 线程写） ----
    @Volatile
    var edgeExclude: Long = 0L

    @Volatile
    var edgeCancelObserved: Long = 0L

    // ---- 回放（inject-dispatch 线程写） ----
    @Volatile
    var replayAttempt: Long = 0L

    @Volatile
    var replaySuccess: Long = 0L

    @Volatile
    var replayTimeout: Long = 0L

    @Volatile
    var replayDropped: Long = 0L

    @Volatile
    var replayIncapable: Long = 0L

    @Volatile
    var replaySemanticLost: Long = 0L

    @Volatile
    var injectThreadState: String = "IDLE"

    // ---- 遮挡 / 坐标（主线程写） ----
    @Volatile
    var overlayRisk: Long = 0L

    @Volatile
    var overlayLastVerdict: String = "unset"

    @Volatile
    var downOutOfBounds: Long = 0L

    @Volatile
    var coordDomain: String = "unset"

    /** 当前 evdev 设备指纹：node|xMax|yMax；未启用时 none */
    @Volatile
    var evdevDescriptor: String = "none"

    /** Spy（monitorGestureInput）可行性探测结论：ok / unavailable / skipped / unknown */
    @Volatile
    var spyStatus: String = "unknown"

    /** 59 号：命中热区但因「方向生效范围」被拦下的次数 */
    @Volatile
    var orientationBlocked: Long = 0L

    /**
     * 62.1：无障碍通道最近一次收到触摸的时刻（elapsedRealtime）。
     *
     * 用途：与旁路的 `lastEventAt` 比对。**a11y 收到了、旁路没收到**，就是「旁路已死」的
     * 直接实证 —— 旁路自己收不到事件时，它自己拿不出任何证据，只能互相印证。
     * 这条时间戳同时驱动「接管本次手势」，避免用户白滑一下。
     */
    @Volatile
    var a11yMotionAt: Long = 0L

    fun bumpRoute() {
        routeCalls++
    }

    fun bumpDispatchToMain() {
        dispatchToMain++
    }
}
