package com.pilot.wheel

import android.app.Application
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.lsposed.hiddenapibypass.HiddenApiBypass
import priv.kit.ui.PrivilegeUi

/**
 * hidden API 豁免必须在这里做，晚于 attachBaseContext 就来不及了。
 * priv-kit 的服务端启动依赖这项能力。
 */
class App : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("L")
        }
    }

    override fun onCreate() {
        super.onCreate()
        // 触发区默认尺寸/内缩一次性迁移（只改仍为旧默认值的项）
        Prefs.migrateLegacyCornerDefaults(this)
        // 常驻 shell 通道（Shizuku UserService）需要 applicationContext 才能绑定
        PilotShellClient.init(this)
        // 工具下线后清理轮盘里残留的 tool: 项
        Prefs.pruneToolKeys(this)
        // 桌面图标与当前主题对齐：alias 的启用状态由系统持久化，升级/恢复备份后可能残留，
        // 每次启动对齐一次，保证桌面只有一个入口且图标是当前主题那套
        runCatching { ThemeIcon.sync(this, Prefs.uiTheme(this)) }
        // 按上次成功的方式静默恢复特权服务，手机重启后不需要再手动点一次
        appScope.launch {
            // 先把常驻 shell 通道绑好（A1）：首次转窗不必再付绑定时间
            runCatching { PilotShellClient.warmUp() }
            runCatching {
                PrivilegeUi.startSilently(config = privilegeUiConfig)
            }
        }
    }

    companion object {
        /**
         * 跨 Activity 生命周期的一次性动作。
         * 「更多」面板启动应用后立刻 finish，用 Activity 的 lifecycleScope 会被取消，
         * 命令还没发出去就断了——这类动作统一丢到这里执行。
         */
        val workScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    }
}
