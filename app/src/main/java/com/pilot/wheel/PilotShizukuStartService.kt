package com.pilot.wheel

import android.os.ParcelFileDescriptor
import android.os.ResultReceiver
import androidx.annotation.Keep
import kotlin.system.exitProcess
import priv.kit.core.PrivilegeExternalStartupHost

/**
 * 跑在 Shizuku 拉起的 UserService 进程里，收到命令后交给 priv-kit 的 Host 执行。
 * 这个类的全名要和 Starter 里 UserServiceArgs 的 ComponentName 保持一致。
 */
@Keep
class PilotShizukuStartService : IPilotShizukuStartService.Stub() {

    private val host = PrivilegeExternalStartupHost()

    override fun start(
        commandLine: String,
        stdout: ParcelFileDescriptor,
        stderr: ParcelFileDescriptor,
        resultReceiver: ResultReceiver,
    ) {
        host.start(
            commandLine = commandLine,
            stdout = stdout,
            stderr = stderr,
            resultReceiver = resultReceiver,
        )
    }

    override fun destroy() {
        host.close()
        exitProcess(0)
    }
}
