package com.pilot.wheel.core

/**
 * 轻量打开的转窗结果码。
 *
 * 重构前是 11 个 `const val String` 加一处裸字面量 `"busy"`：
 * - 「这次算不算成功」要靠 [LightOpenController.SUCCESS_RESULTS] 集合人工维护，
 *   新增一个结果码忘了登记，实况通知就会漏发；
 * - `"busy"` 没有常量，唯一一处字符串字面量躲在 `convert()` 里；
 * - 结果码字符串被全工程当成对外协议，实际上只有 `LightOpenWatcher` 一处消费。
 *
 * 改成枚举后：成功判定由 [windowed] 承担（编译期强制填写），日志仍输出 [code]，
 * 历史日志的检索方式不变。
 */
enum class LightOpenOutcome(val code: String, val windowed: Boolean) {

    /** binder 直改窗口模式成功（task 级，不动栈） */
    TASK_MODE("ok-task-mode", true),

    /** 同实例 + SINGLE_TOP 模式推送成功 */
    TOP("ok-top", true),

    /** 推进分屏成功（形态不如浮窗，但已脱离全屏） */
    SPLIT("ok-split", true),

    /** 入口 Activity 重新拉起成功 */
    LAUNCH("ok-launch", true),

    /** 只改几何成功 */
    RESIZE("ok-resize", true),

    /** 先转后台再以浮窗拉起成功 */
    RELAUNCH("ok-relaunch", true),

    /** ActivityOptions / 荣耀 oneStep 通道成功 */
    INTENT("ok-intent", true),

    /** 转窗前已经是浮窗，无需动作 */
    ALREADY("already-freeform", true),

    /** 上一次转窗还没结束 */
    BUSY("busy", false),

    /** 没有特权通道，只能走普通启动且未进浮窗 */
    NO_PRIVILEGE("no-privilege", false),

    /** 特权通道可用但所有级别都失败 */
    FAILED("failed", false),

    /** 执行过程中抛异常（异常类名单独进日志，统计只记 error） */
    ERROR("error", false),

    /** 为保全流程主动放弃转窗，保持全屏（分享 / 授权场景） */
    KEPT("kept-fullscreen", false),
    ;

    companion object {
        /** 兼容按字符串码查表（日志回放、测试断言用） */
        fun of(code: String): LightOpenOutcome? = entries.firstOrNull { it.code == code }
    }
}
