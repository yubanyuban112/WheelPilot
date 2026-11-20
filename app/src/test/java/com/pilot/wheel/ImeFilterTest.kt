package com.pilot.wheel

import com.pilot.wheel.core.ImeFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 输入法过滤。这一层挡不住的话，用户点输入框就会把键盘转成浮窗、当场打不了字，
 * 是轻量打开里后果最严重的一类误判，所以每条分支都单独钉住。
 */
class ImeFilterTest {

    @Test
    fun `解析已启用输入法串只取包名`() {
        val raw = "com.tencent.wetype/.WetypeIme:com.sohu.inputmethod.sogou/.SogouIME"
        val set = ImeFilter.parsePackageList(raw)
        assertEquals(setOf("com.tencent.wetype", "com.sohu.inputmethod.sogou"), set)
    }

    @Test
    fun `解析默认输入法单条`() {
        assertEquals(
            setOf("com.tencent.wetype"),
            ImeFilter.parsePackageList("com.tencent.wetype/com.tencent.wetype.ImeService"),
        )
    }

    @Test
    fun `空串与垃圾串返回空集`() {
        assertTrue(ImeFilter.parsePackageList(null).isEmpty())
        assertTrue(ImeFilter.parsePackageList("").isEmpty())
        assertTrue(ImeFilter.parsePackageList("  ").isEmpty())
        // 没有点号的不像包名，不当成输入法
        assertTrue(ImeFilter.parsePackageList("wetype").isEmpty())
    }

    @Test
    fun `输入法窗口事件被跳过`() {
        assertTrue(
            ImeFilter.shouldSkip(
                pkg = "com.tencent.wetype",
                className = "android.inputmethodservice.SoftInputWindow",
                imePackages = setOf("com.tencent.wetype"),
                imeWindowOnScreen = true,
            ),
        )
    }

    @Test
    fun `不在启用列表里但包名像输入法也跳过`() {
        assertTrue(
            ImeFilter.shouldSkip(
                pkg = "com.sohu.inputmethod.sogou",
                className = null,
                imePackages = emptySet(),
                imeWindowOnScreen = false,
            ),
        )
    }

    @Test
    fun `只有类名时靠类名识别`() {
        assertTrue(ImeFilter.looksLikeImeWindow("android.inputmethodservice.SoftInputWindow"))
        assertTrue(ImeFilter.looksLikeImeWindow("com.android.internal.policy.SoftInputWindow"))
        assertFalse(ImeFilter.looksLikeImeWindow("com.tencent.mm.ui.LauncherUI"))
        assertFalse(ImeFilter.looksLikeImeWindow(null))
    }

    @Test
    fun `键盘在屏时任何窗口变化都不判`() {
        assertTrue(
            ImeFilter.shouldSkip(
                pkg = "com.taobao.taobao",
                className = "com.taobao.tao.welcome.Welcome",
                imePackages = setOf("com.tencent.wetype"),
                imeWindowOnScreen = true,
            ),
        )
    }

    @Test
    fun `普通应用切换不受影响`() {
        assertFalse(
            ImeFilter.shouldSkip(
                pkg = "com.taobao.taobao",
                className = "com.taobao.tao.welcome.Welcome",
                imePackages = setOf("com.tencent.wetype"),
                imeWindowOnScreen = false,
            ),
        )
    }

    @Test
    fun `包名分段带 time 的应用不会被误判成输入法`() {
        // 子串判断会把 "com.example.time.app" 里的 "ime." 当命中，这里必须按分段比对
        assertFalse(ImeFilter.looksLikeImePackage("com.example.time.app"))
        assertFalse(ImeFilter.looksLikeImePackage("com.tencent.mm"))
        assertFalse(ImeFilter.looksLikeImePackage(null))
        // 真正的 ime 分段要能认出
        assertTrue(ImeFilter.looksLikeImePackage("com.xiaomi.ime"))
    }

    @Test
    fun `空包名无类名且无键盘时不拦`() {
        assertFalse(
            ImeFilter.shouldSkip(
                pkg = null,
                className = null,
                imePackages = setOf("com.tencent.wetype"),
                imeWindowOnScreen = false,
            ),
        )
    }
}
