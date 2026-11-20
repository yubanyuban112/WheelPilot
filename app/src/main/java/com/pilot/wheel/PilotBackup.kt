package com.pilot.wheel

import android.content.Context
import android.widget.Toast
import com.pilot.wheel.core.BackupCodec

/**
 * 配置文件读写：把 SharedPreferences 里的全部条目转成 [BackupCodec] 的行格式，
 * 或反过来写回。用于换机、重置前留档、以及调参调乱了回滚。
 *
 * 恢复策略是「逐项覆盖」，不先清空：备份里没有的键（例如新版新增的）保持原值，
 * 避免旧备份把新配置一起抹掉。
 */
object PilotBackup {

    const val FILE_NAME_PREFIX = "wheelpilot-backup"

    fun export(context: Context): String {
        val entries = Prefs.rawEntries(context)
            .mapNotNull { (key, value) -> toEntry(key, value) }
            .sortedBy { it.key }
        return BackupCodec.encode(entries)
    }

    /** 返回写回的条目数；格式不对时抛 [IllegalArgumentException] */
    fun import(context: Context, text: String): Int {
        val snapshot = BackupCodec.decode(text).getOrThrow()
        Prefs.restoreEntries(context, snapshot.entries)
        return snapshot.size()
    }

    /** 导出文件默认名，带日期，避免多次导出互相覆盖 */
    fun suggestedFileName(timestamp: String): String = "$FILE_NAME_PREFIX-$timestamp.txt"

    private fun toEntry(key: String, value: Any): BackupCodec.Entry? = when (value) {
        is String -> BackupCodec.Entry(key, BackupCodec.TYPE_STRING, value)
        is Boolean -> BackupCodec.Entry(key, BackupCodec.TYPE_BOOLEAN, value.toString())
        is Int -> BackupCodec.Entry(key, BackupCodec.TYPE_INT, value.toString())
        is Long -> BackupCodec.Entry(key, BackupCodec.TYPE_LONG, value.toString())
        is Float -> BackupCodec.Entry(key, BackupCodec.TYPE_FLOAT, value.toString())
        else -> null
    }

    /** 读文件内容并恢复；抛出的异常已带中文原因，调用处直接展示 */
    fun importFromUri(context: Context, uri: android.net.Uri): Int {
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                ?: throw IllegalArgumentException("无法读取该文件")
        }.getOrElse { throw IllegalArgumentException("无法读取该文件：${it.message}") }
        return import(context, text)
    }

    fun exportToUri(context: Context, uri: android.net.Uri): Int {
        val text = export(context)
        runCatching {
            context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                out.write(text.toByteArray())
            } ?: throw IllegalArgumentException("无法写入该位置")
        }.getOrElse { throw IllegalArgumentException("无法写入该位置：${it.message}") }
        return text.length
    }

    fun toast(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}
