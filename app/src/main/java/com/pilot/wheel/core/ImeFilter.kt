package com.pilot.wheel.core

/**
 * 输入法窗口识别。
 *
 * 为什么必须单独拦：输入法是与宿主应用无关的独立窗口。键盘弹出时无障碍会报一条
 * `packageName = com.tencent.wetype` 之类的窗口状态变化，判据看到的形态是
 * 「原应用 → 输入法包名」，与「在 A 里点开 B」完全同形，于是把输入法当成目标应用
 * 执行转窗 —— 键盘被 resize 成小窗，用户直接没法打字。
 *
 * 这里只做纯字符串与集合判断，取系统数据的地方在 [com.pilot.wheel.LightOpenWatcher]。
 */
object ImeFilter {

    /**
     * 包名兜底：Secure 设置读不到（部分 ROM 限制）时用。
     * 只放明确的厂商/产品名，避免把普通应用误判成输入法。
     */
    private val KNOWN_IME_HINTS = listOf(
        "wetype",          // 微信输入法
        "sogou",           // 搜狗
        "baidu.input",     // 百度输入法
        "iflytek",         // 讯飞
        "swiftkey",
        "gboard",
        "google.android.inputmethod",
        "openwnn",
        "touchpal",
        "kika",
        "hibitinput",
        "miui.inputmethod",
        "sec.android.inputmethod",
    )

    /** 输入法进程里常见、且不会出现在普通应用里的包名分段 */
    private val IME_SEGMENTS = setOf("ime", "inputmethods", "inputmethodservice")

    /**
     * 解析 `Settings.Secure.ENABLED_INPUT_METHODS` / `DEFAULT_INPUT_METHOD`。
     * 格式是 `包名/类名:包名/类名`，包名取 `/` 之前的部分。
     */
    fun parsePackageList(raw: String?): Set<String> {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return emptySet()
        val out = LinkedHashSet<String>()
        for (entry in text.split(':')) {
            val pkg = entry.substringBefore('/').trim()
            // 包名至少要有两段，避免把空串或单个单词当成包名
            if (pkg.count { it == '.' } >= 1) out.add(pkg)
        }
        return out
    }

    /** 类名兜底：部分 ROM 只给类名不给包名，或包名报成 systemui */
    fun looksLikeImeWindow(className: String?): Boolean {
        val name = className?.lowercase() ?: return false
        return name.contains("inputmethod") || name.contains("softinput")
    }

    /** 包名兜底：不在启用列表里但看着就是输入法（例如刚装还没启用） */
    fun looksLikeImePackage(pkg: String?): Boolean {
        val name = pkg?.trim()?.lowercase().orEmpty()
        if (name.isEmpty()) return false
        if (KNOWN_IME_HINTS.any { name.contains(it) }) return true
        // 按分段比对，不用子串 —— "com.example.time.app" 里含有 "ime." 子串，子串判断会误伤
        return name.split('.').any { it in IME_SEGMENTS }
    }

    /**
     * 该窗口事件是否应视作输入法、从而完全跳过场景判定。
     *
     * [imeWindowOnScreen] 来自 `AccessibilityWindowInfo.TYPE_INPUT_METHOD` 的实时探测：
     * 键盘在屏上时，这一刻的前台变化一律不判。宁可少转一次窗，也不能把键盘转成浮窗 ——
     * 误转的后果是用户当场无法打字。
     */
    fun shouldSkip(
        pkg: String?,
        className: String?,
        imePackages: Set<String>,
        imeWindowOnScreen: Boolean,
    ): Boolean {
        val name = pkg?.trim().orEmpty()
        if (name.isNotEmpty()) {
            if (name in imePackages) return true
            if (looksLikeImePackage(name)) return true
        }
        if (looksLikeImeWindow(className)) return true
        return imeWindowOnScreen
    }
}
