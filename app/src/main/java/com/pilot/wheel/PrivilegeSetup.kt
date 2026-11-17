package com.pilot.wheel

import android.app.Application
import priv.kit.ui.PrivilegeUiConfig
import priv.kit.ui.PrivilegeUiStartupMode
import priv.kit.ui.PrivilegeUiViewModel

/**
 * 进程级单例。文档要求 Config 只创建一次，前台 ViewModel 和静默启动共用同一实例，
 * 否则 Provider ID 之类的状态会不一致。
 */
val privilegeUiConfig by lazy {
    PrivilegeUiConfig(
        startupModes = listOf(
            PrivilegeUiStartupMode.ROOT,
            PrivilegeUiStartupMode.ADB,
            PrivilegeUiStartupMode.MANUAL_SHELL,
        ),
        // Shizuku 作为外部启动方式：有 Shizuku 时不必走 ADB 配对
        externalStartProviders = listOf(PilotShizukuProvider),
    )
}

/**
 * PrivilegeScaffold 需要一个 ViewModel，它负责配对、启动、状态展示的全部实际逻辑。
 * 这里只覆写返回键行为，其它全部交给 priv-kit 的默认实现。
 */
class PilotPrivilegeViewModel(
    application: Application,
) : PrivilegeUiViewModel(application, privilegeUiConfig) {

    override fun onBackClick(): Boolean = true
}
