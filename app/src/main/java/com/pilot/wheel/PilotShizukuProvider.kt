package com.pilot.wheel

import android.content.Context
import android.content.pm.PackageManager
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import priv.kit.core.PrivilegeStartupLogListener
import priv.kit.ui.PrivilegeUiExternalStartSnapshot
import priv.kit.ui.PrivilegeUiStreamingExternalStartProvider
import rikka.shizuku.Shizuku

/**
 * 把 Shizuku 注册成 priv-kit 的一条"外部启动方式"。
 * 这样在有 Shizuku 的机器上就不必走 ADB 配对，直接借 Shizuku 的 shell 权限拉起私服。
 */
object PilotShizukuProvider : PrivilegeUiStreamingExternalStartProvider {

    override val id: String = "shizuku"
    override val label: CharSequence = "Shizuku"

    override suspend fun snapshot(context: Context): PrivilegeUiExternalStartSnapshot =
        runCatching { shizukuSnapshot() }
            .getOrElse {
                PrivilegeUiExternalStartSnapshot(
                    message = it.message ?: it.javaClass.name,
                )
            }

    override suspend fun requestAuthorization(
        context: Context,
    ): PrivilegeUiExternalStartSnapshot {
        val current = snapshot(context)
        if (!current.available || current.canStart || Shizuku.shouldShowRequestPermissionRationale()) {
            return current
        }

        return suspendCancellableCoroutine { continuation ->
            val completed = AtomicBoolean(false)
            lateinit var listener: Shizuku.OnRequestPermissionResultListener

            fun finish(result: PrivilegeUiExternalStartSnapshot) {
                if (!completed.compareAndSet(false, true)) return
                Shizuku.removeRequestPermissionResultListener(listener)
                if (continuation.isActive) continuation.resume(result)
            }

            listener = Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
                if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
                    finish(shizukuSnapshot())
                }
            }
            continuation.invokeOnCancellation {
                if (completed.compareAndSet(false, true)) {
                    Shizuku.removeRequestPermissionResultListener(listener)
                }
            }
            Shizuku.addRequestPermissionResultListener(listener)
            try {
                if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    finish(shizukuSnapshot())
                } else {
                    Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
                }
            } catch (t: Throwable) {
                finish(
                    PrivilegeUiExternalStartSnapshot(
                        message = t.message ?: t.javaClass.name,
                    ),
                )
            }
        }
    }

    override suspend fun start(context: Context, commandLine: String) {
        PilotShizukuStarter(context).use { it.start(commandLine) }
    }

    override suspend fun start(
        context: Context,
        commandLine: String,
        startupLogListener: PrivilegeStartupLogListener,
    ) {
        PilotShizukuStarter(context).use { it.start(commandLine, startupLogListener) }
    }

    private fun shizukuSnapshot(): PrivilegeUiExternalStartSnapshot {
        if (!Shizuku.pingBinder()) {
            return PrivilegeUiExternalStartSnapshot(message = "Shizuku 未运行")
        }
        if (Shizuku.isPreV11()) {
            return PrivilegeUiExternalStartSnapshot(message = "Shizuku 版本过低")
        }

        val version = Shizuku.getVersion()
        val uid = Shizuku.getUid().takeIf { it >= 0 }

        if (version < PilotShizukuStarter.SHIZUKU_USER_SERVICE_MIN_VERSION) {
            return PrivilegeUiExternalStartSnapshot(
                available = false,
                uid = uid,
                version = version,
                message = "Shizuku 版本不支持 UserService",
            )
        }

        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            return PrivilegeUiExternalStartSnapshot(
                available = true,
                authorized = true,
                uid = uid,
                version = version,
                message = "Shizuku 就绪",
            )
        }

        if (Shizuku.shouldShowRequestPermissionRationale()) {
            return PrivilegeUiExternalStartSnapshot(
                available = true,
                uid = uid,
                version = version,
                message = "Shizuku 权限被拒绝",
            )
        }

        return PrivilegeUiExternalStartSnapshot(
            available = true,
            uid = uid,
            version = version,
            message = "需要 Shizuku 授权",
        )
    }

    private const val SHIZUKU_PERMISSION_REQUEST_CODE = 4001
}
