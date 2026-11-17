package com.pilot.wheel

/**
 * 取应用名的拼音首字母（用于通讯录式分组）。
 * 用 GB2312 区位码区间估算，不引入拼音库；英文字母直接返回，其他字符归入 #。
 */
object LetterUtil {

    private val ranges = arrayOf(
        intArrayOf(0xB0A1, 0xB0C4), // A
        intArrayOf(0xB0C5, 0xB2C0), // B
        intArrayOf(0xB2C1, 0xB4ED), // C
        intArrayOf(0xB4EE, 0xB6E9), // D
        intArrayOf(0xB6EA, 0xB7A1), // E
        intArrayOf(0xB7A2, 0xB8C0), // F
        intArrayOf(0xB8C1, 0xB9FD), // G
        intArrayOf(0xB9FE, 0xBBF6), // H
        intArrayOf(0xBBF7, 0xBFA5), // J
        intArrayOf(0xBFA6, 0xC0AB), // K
        intArrayOf(0xC0AC, 0xC2E7), // L
        intArrayOf(0xC2E8, 0xC4C2), // M
        intArrayOf(0xC4C3, 0xC5B5), // N
        intArrayOf(0xC5B6, 0xC5BD), // O
        intArrayOf(0xC5BE, 0xC6D9), // P
        intArrayOf(0xC6DA, 0xC8BA), // Q
        intArrayOf(0xC8BB, 0xC8F5), // R
        intArrayOf(0xC8F6, 0xCBFA), // S
        intArrayOf(0xCBFB, 0xCDD9), // T
        intArrayOf(0xCDDA, 0xCEF3), // W
        intArrayOf(0xCEF4, 0xD1B8), // X
        intArrayOf(0xD1B9, 0xD4D0), // Y
        intArrayOf(0xD4D1, 0xD7F9), // Z
    )
    // 注意：labels 与 ranges 一一对应，23 个字母（拼音无 I/U/V 开头，跳过）
    private val labels = "ABCDEFGHJKLMNOPQRSTWXYZ"

    fun firstLetter(str: String): String {
        val c = str.firstOrNull() ?: return "#"
        if (c in 'A'..'Z') return c.toString()
        if (c in 'a'..'z') return c.uppercaseChar().toString()
        if (c.code < 128) return "#"
        val bytes = runCatching { "$c".toByteArray(charset("GBK")) }.getOrElse { return "#" }
        if (bytes.size < 2) return "#"
        val code = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
        for (i in ranges.indices) {
            if (code >= ranges[i][0] && code <= ranges[i][1]) return labels[i].toString()
        }
        return "#"
    }
}
