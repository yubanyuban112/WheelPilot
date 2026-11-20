package com.pilot.wheel.core

/**
 * 配置备份的纯文本编解码。
 *
 * 为什么不用 JSON：Android 自带的 org.json 在 JVM 单测里是空实现（调用即抛异常），
 * 为了能对备份格式做单元测试，这里用「行格式 + 类型前缀」，不引入任何解析依赖。
 *
 * 文件形如：
 * ```
 * wheelpilot-backup
 * version=1
 * S:apps=com.tencent.mm
 * B:left_enabled=true
 * I:slots=8
 * L:close_duration=450
 * F:ring_radius=199.0
 * ```
 * 值里的反斜杠、换行、回车分别转义成 `\\`、`\n`、`\r`，保证一条配置只占一行。
 */
object BackupCodec {

    const val HEADER = "wheelpilot-backup"
    const val VERSION = 1

    /** 支持的值类型：字符串 / 布尔 / 整数 / 长整数 / 单精度小数 */
    const val TYPE_STRING = 'S'
    const val TYPE_BOOLEAN = 'B'
    const val TYPE_INT = 'I'
    const val TYPE_LONG = 'L'
    const val TYPE_FLOAT = 'F'

    private val TYPES = setOf(TYPE_STRING, TYPE_BOOLEAN, TYPE_INT, TYPE_LONG, TYPE_FLOAT)
    private val KEY_PATTERN = Regex("[A-Za-z0-9_.-]{1,64}")

    data class Entry(val key: String, val type: Char, val value: String)

    data class Snapshot(val version: Int, val entries: List<Entry>) {
        fun value(key: String): String? = entries.firstOrNull { it.key == key }?.value
        fun size(): Int = entries.size
    }

    fun encode(entries: List<Entry>): String = buildString {
        append(HEADER).append('\n')
        append("version=").append(VERSION).append('\n')
        entries.forEach { entry ->
            require(entry.type in TYPES) { "未知类型：${entry.type}" }
            require(KEY_PATTERN.matches(entry.key)) { "非法键名：${entry.key}" }
            validate(entry.type, entry.value)
            append(entry.type).append(':').append(entry.key).append('=').append(escape(entry.value))
            append('\n')
        }
    }

    /** 解不出来时返回失败，[Result.exceptionOrNull] 里是可直接展示给用户的中文原因 */
    fun decode(text: String): Result<Snapshot> = runCatching {
        val lines = text.replace("\r\n", "\n").split('\n')
        require(lines.isNotEmpty() && lines[0].trim() == HEADER) { "不是 WheelPilot 配置文件" }

        val versionLine = lines.getOrNull(1)?.trim().orEmpty()
        require(versionLine.startsWith("version=")) { "缺少版本行" }
        val version = versionLine.removePrefix("version=").toIntOrNull()
            ?: throw IllegalArgumentException("版本号无法识别")
        require(version in 1..VERSION) { "备份来自不支持的版本 v$version" }

        val entries = mutableListOf<Entry>()
        val seen = mutableSetOf<String>()
        for (raw in lines.drop(2)) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            require(line.length >= 4 && line[1] == ':') { "行格式错误：$line" }
            val type = line[0]
            require(type in TYPES) { "未知类型：$type" }
            val body = line.substring(2)
            val eq = body.indexOf('=')
            require(eq > 0) { "行格式错误：$line" }
            val key = body.substring(0, eq)
            require(KEY_PATTERN.matches(key)) { "非法键名：$key" }
            require(seen.add(key)) { "重复键：$key" }
            val value = unescape(body.substring(eq + 1))
            validate(type, value)
            entries += Entry(key, type, value)
        }
        Snapshot(version, entries)
    }

    private fun validate(type: Char, value: String) {
        when (type) {
            TYPE_STRING -> Unit
            TYPE_BOOLEAN -> require(value == "true" || value == "false") { "布尔值非法：$value" }
            TYPE_INT -> require(value.toIntOrNull() != null) { "整数非法：$value" }
            TYPE_LONG -> require(value.toLongOrNull() != null) { "长整数非法：$value" }
            TYPE_FLOAT -> require(value.toFloatOrNull() != null) { "小数非法：$value" }
            else -> throw IllegalArgumentException("未知类型：$type")
        }
    }

    private fun escape(value: String): String = buildString {
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(ch)
            }
        }
    }

    private fun unescape(value: String): String = buildString {
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (ch == '\\' && i + 1 < value.length) {
                when (val next = value[i + 1]) {
                    '\\' -> append('\\')
                    'n' -> append('\n')
                    'r' -> append('\r')
                    else -> append(next)
                }
                i += 2
            } else {
                append(ch)
                i += 1
            }
        }
    }
}
