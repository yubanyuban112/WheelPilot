package com.pilot.wheel

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 详细日志（设置里可开关）。
 *
 * 只在设置里打开「详细日志」后才写，落在应用私有目录，不上传。
 * 超过 [MAX_BYTES] 时保留后半段，避免长时间运行把存储空间吃满。
 */
object PilotLog {

    private const val FILE = "wheelpilot.log"
    private const val MAX_BYTES = 256 * 1024
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())

    private fun file(context: Context) = File(context.filesDir, FILE)

    fun d(context: Context, tag: String, message: String) {
        if (!Prefs.verboseLog(context)) return
        append(context, "D", tag, message)
    }

    fun w(context: Context, tag: String, message: String) {
        if (!Prefs.verboseLog(context)) return
        append(context, "W", tag, message)
    }

    fun e(context: Context, tag: String, message: String, throwable: Throwable? = null) {
        if (!Prefs.verboseLog(context)) return
        val detail = throwable?.let { " | ${it.javaClass.simpleName}: ${it.message}" }.orEmpty()
        append(context, "E", tag, message + detail)
    }

    @Synchronized
    private fun append(context: Context, level: String, tag: String, message: String) {
        runCatching {
            val target = file(context)
            if (target.exists() && target.length() > MAX_BYTES) {
                target.writeText(target.readText().takeLast(MAX_BYTES / 2))
            }
            target.appendText("${stamp.format(Date())} $level/$tag $message\n")
        }
    }

    /** 供设置页「复制日志」使用 */
    fun read(context: Context): String =
        runCatching { file(context).readText() }.getOrDefault("")

    fun clear(context: Context) {
        runCatching { file(context).writeText("") }
    }
}
