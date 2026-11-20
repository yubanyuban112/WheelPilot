package com.pilot.wheel;

/**
 * 旁路触点回调：驻留 shell 进程拿到全局触摸事件后，把事件推回用户态。
 * 用户态不消费触摸，只在本地解析判定热区命中，系统仍照常把触摸发给下层应用。
 */
interface IRawTouchCallback {

    /**
     * 单个触点事件（getevent 旁路，原始坐标，未归一化）。
     *
     * @param type 事件类型：0=DOWN(按下，含 slot 建立)、1=MOVE、2=UP(抬起)
     * @param rawX/rawY 原始坐标（未归一化，范围由设备 max 定义）
     * @param slot 触点槽位（多指时区分手指；单指恒 0）
     */
    void onRawTouch(int type, float rawX, float rawY, int slot) = 1;

    /**
     * 采集状态回调（可选）。state: 0=设备打开成功、1=读取线程异常退出。
     * detail 为可读原因，用户态据此做降级/重试。
     */
    void onCollectorState(int state, String detail) = 2;

    /**
     * 全局触摸事件（monitorInput 旁路，屏幕逻辑坐标，已归一化/旋转，左上原点）。
     *
     * @param action MotionEvent 的 action（DOWN=0 / UP=1 / MOVE=2 / CANCEL=3，含 masked）
     * @param x/y 屏幕逻辑坐标（px）
     * @param eventTime 事件时间戳（ms）
     */
    void onMotionEvent(int action, float x, float y, long eventTime) = 3;
}
