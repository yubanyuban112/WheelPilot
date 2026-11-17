package com.pilot.wheel

import android.app.Activity
import android.os.Bundle

/**
 * 透明入口，专门给荣耀 AI 键 / 桌面快捷方式用。
 *
 * 系统只能把按键映射到"应用"或"快捷方式"，没法直接唤起无障碍服务，
 * 所以给出这个零界面的 Activity：被拉起后立刻把轮盘弹出来，然后马上退出，
 * 用户看到的只有轮盘，不会闪一下设置页。
 */
class QuickWheelActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PilotService.instance?.previewWheel()
        finishAndRemoveTask()
    }
}
