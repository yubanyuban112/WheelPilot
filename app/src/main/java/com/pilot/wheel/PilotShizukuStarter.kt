package com.pilot.wheel

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.IBinder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import priv.kit.core.PrivilegeExternalStartup
import priv.kit.core.PrivilegeStartupException
import priv.kit.core.PrivilegeStartupLogListener
import rikka.shizuku.Shizuku
import java.io.Closeable
import kotlin.time.Duration.Companion.milliseconds

/**
 * 通过 Shizuku 的 UserService 拿到一个 shell 身份的执行环境，
 * 再把 priv-kit 的 native starter 命令塞进去跑。
 * 这样私服进程是 Shizuku 拉起来的，不需要本应用自己配 ADB 密钥。
 */
class PilotShizukuStarter(context: Context) : Closeable {

    private val applicationContext = context.applicationContext

    @Volatile
    private var service: IPilotShizukuStartService? = null

    @Volatile
    private var serviceConnection: ServiceConnection? = null

    fun isAvailable(): Boolean =
        Shizuku.pingBinder() &&
            !Shizuku.isPreV11() &&
            Shizuku.getVersion() >= SHIZUKU_USER_SERVICE_MIN_VERSION &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    suspend fun start(commandLine: String): String =
        startAndWait(commandLine, startupLogListener = null)

    suspend fun start(
        commandLine: String,
        startupLogListener: PrivilegeStartupLogListener,
    ) {
        startAndWait(commandLine, startupLogListener)
    }

    private suspend fun startAndWait(
        commandLine: String,
        startupLogListener: PrivilegeStartupLogListener?,
    ): String {
        if (!isAvailable()) {
            throw PrivilegeStartupException("Shizuku 未运行或未授权")
        }
        val activeService = bindOrGetService()
        return PrivilegeExternalStartup.runThroughBridge(
            commandLine = commandLine,
            bridge = { bridgeCommand, stdout, stderr, resultReceiver ->
                activeService.start(bridgeCommand, stdout, stderr, resultReceiver)
            },
            startupLogListener = startupLogListener,
        )
    }

    override fun close() {
        val connection = serviceConnection ?: return
        serviceConnection = null
        service = null
        removeUserService(connection)
    }

    private suspend fun bindOrGetService(): IPilotShizukuStartService {
        service?.takeIf { svc ->
            runCatching { svc.asBinder().pingBinder() }.getOrDefault(false)
        }?.let { return it }

        val binding = CompletableDeferred<IPilotShizukuStartService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                val startService = IPilotShizukuStartService.Stub.asInterface(binder)
                if (startService == null) {
                    binding.completeExceptionally(
                        PrivilegeStartupException("Shizuku UserService 返回了无效 Binder"),
                    )
                } else {
                    binding.complete(startService)
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                service = null
                binding.completeExceptionally(
                    PrivilegeStartupException("Shizuku UserService 绑定期间断开"),
                )
            }
        }

        try {
            serviceConnection = connection
            Shizuku.bindUserService(userServiceArgs(), connection)
        } catch (t: Throwable) {
            serviceConnection = null
            throw PrivilegeStartupException("绑定 Shizuku UserService 失败", t)
        }

        val boundService = try {
            withTimeoutOrNull(SHIZUKU_BIND_TIMEOUT_MILLIS.milliseconds) { binding.await() }
                ?: throw PrivilegeStartupException("绑定 Shizuku UserService 超时")
        } catch (t: Throwable) {
            removeUserService(connection)
            serviceConnection = null
            if (t is CancellationException) throw t
            if (t is PrivilegeStartupException) throw t
            throw PrivilegeStartupException("绑定 Shizuku UserService 失败", t)
        }
        service = boundService
        return boundService
    }

    private fun userServiceArgs(): Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(
            ComponentName(
                applicationContext.packageName,
                PilotShizukuStartService::class.java.name,
            ),
        )
            .daemon(false)
            .tag(SHIZUKU_START_TAG)
            .processNameSuffix(SHIZUKU_START_PROCESS_SUFFIX)
            .debuggable(applicationContext.isDebuggable())
            .version(SHIZUKU_START_SERVICE_VERSION)

    private fun removeUserService(connection: ServiceConnection) {
        val args = userServiceArgs()
        runCatching { Shizuku.unbindUserService(args, connection, false) }
        runCatching { Shizuku.unbindUserService(args, connection, true) }
    }

    private fun Context.isDebuggable(): Boolean =
        applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    companion object {
        const val SHIZUKU_USER_SERVICE_MIN_VERSION = 10
        private const val SHIZUKU_BIND_TIMEOUT_MILLIS = 10_000L
        private const val SHIZUKU_START_TAG = "pilot-shizuku-start"
        private const val SHIZUKU_START_PROCESS_SUFFIX = "pilot-shizuku-start"
        private const val SHIZUKU_START_SERVICE_VERSION = 1
    }
}
