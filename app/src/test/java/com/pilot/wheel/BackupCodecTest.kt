package com.pilot.wheel

import com.pilot.wheel.core.BackupCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 备份编解码：往返一致 + 坏输入必须失败（而不是静默读出半份配置） */
class BackupCodecTest {

    private fun entry(key: String, type: Char, value: String) = BackupCodec.Entry(key, type, value)

    @Test
    fun `round trip keeps every type`() {
        val source = listOf(
            entry("apps", BackupCodec.TYPE_STRING, "com.tencent.mm\ncom.eg.android.AlipayGphone"),
            entry("left_enabled", BackupCodec.TYPE_BOOLEAN, "true"),
            entry("slots", BackupCodec.TYPE_INT, "8"),
            entry("close_duration", BackupCodec.TYPE_LONG, "450"),
            entry("ring_radius", BackupCodec.TYPE_FLOAT, "199.0"),
        )
        val decoded = BackupCodec.decode(BackupCodec.encode(source)).getOrThrow()
        assertEquals(BackupCodec.VERSION, decoded.version)
        assertEquals(source, decoded.entries)
    }

    @Test
    fun `newlines and backslashes survive`() {
        val value = "第一行\n第二行\\反斜杠\r结束"
        val text = BackupCodec.encode(listOf(entry("note", BackupCodec.TYPE_STRING, value)))
        // 一条配置只能占一行，值里的换行必须是转义后的两个字面字符
        assertEquals(3, text.trimEnd('\n').split('\n').size)
        assertEquals(value, BackupCodec.decode(text).getOrThrow().value("note"))
    }

    @Test
    fun `empty backup decodes to empty snapshot`() {
        val decoded = BackupCodec.decode(BackupCodec.encode(emptyList())).getOrThrow()
        assertEquals(0, decoded.size())
    }

    @Test
    fun `foreign file is rejected`() {
        val result = BackupCodec.decode("{\"apps\":\"com.foo\"}")
        assertTrue(result.isFailure)
        assertEquals("不是 WheelPilot 配置文件", result.exceptionOrNull()?.message)
    }

    @Test
    fun `newer version is rejected`() {
        val text = "${BackupCodec.HEADER}\nversion=${BackupCodec.VERSION + 1}\nS:apps=com.foo\n"
        assertTrue(BackupCodec.decode(text).isFailure)
    }

    @Test
    fun `unknown type is rejected`() {
        assertTrue(BackupCodec.decode("${BackupCodec.HEADER}\nversion=1\nZ:apps=x\n").isFailure)
    }

    @Test
    fun `non numeric int is rejected`() {
        assertTrue(BackupCodec.decode("${BackupCodec.HEADER}\nversion=1\nI:slots=八\n").isFailure)
    }

    @Test
    fun `illegal boolean is rejected`() {
        assertTrue(BackupCodec.decode("${BackupCodec.HEADER}\nversion=1\nB:left_enabled=yes\n").isFailure)
    }

    @Test
    fun `duplicate key is rejected`() {
        val text = "${BackupCodec.HEADER}\nversion=1\nI:slots=8\nI:slots=6\n"
        assertTrue(BackupCodec.decode(text).isFailure)
    }

    @Test
    fun `bad key name is rejected`() {
        val text = "${BackupCodec.HEADER}\nversion=1\nS:两个词=x\n"
        assertTrue(BackupCodec.decode(text).isFailure)
    }

    @Test
    fun `blank lines between entries are ignored`() {
        val text = "${BackupCodec.HEADER}\nversion=1\n\nI:slots=6\n\n"
        assertEquals("6", BackupCodec.decode(text).getOrThrow().value("slots"))
    }
}
