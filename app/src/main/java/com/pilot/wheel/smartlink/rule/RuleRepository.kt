package com.pilot.wheel.smartlink.rule

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 导入结果：成功给出条数与新增/覆盖分布，失败给出可直接展示的中文原因 */
sealed interface ImportResult {
    data class Ok(val total: Int, val added: Int, val updated: Int) : ImportResult
    data class Err(val reason: String) : ImportResult

    fun message(): String = when (this) {
        is Ok -> "导入成功：共 $total 条（新增 $added，覆盖 $updated）"
        is Err -> "导入失败：$reason"
    }
}

/**
 * 规则存储：SharedPreferences 单 key（JSON 字符串）+ version 字段，默认规则放 assets。
 * 不引入 Room/迁移负担，version 不匹配自动回退默认规则。
 *
 * 三种来源的优先级：**用户导入（prefs）> assets 默认表**。升 [VERSION] 会让二者都重新回到
 * assets——这是「换默认表」的手段，也是导入规则在升级时被覆盖的唯一时机，UI 已注明。
 */
class RuleRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile private var cache: List<LinkRule>? = null

    fun getAll(): List<LinkRule> {
        cache?.let { return it }
        val list = loadFromPrefs() ?: loadDefaults()
        cache = list
        return list
    }

    fun count(): Int = getAll().size

    fun save(rules: List<LinkRule>) {
        val arr = JSONArray()
        rules.forEach { arr.put(it.toJson()) }
        prefs.edit()
            .putString(KEY_RULES, arr.toString())
            .putInt(KEY_VERSION, VERSION)
            .apply()
        cache = rules
    }

    /** 热更新入口：新 JSON 字符串覆盖（保留给旧调用方，等价于整体替换） */
    fun updateFromJson(json: String): Boolean =
        importJson(json, merge = false) is ImportResult.Ok

    /**
     * 导入规则（用户模板 / 分享文件 / adb 接口三个入口共用）。
     *
     * - [merge] = true：按 ruleId 合并，同 id 覆盖、新 id 追加，不删未提及的规则（默认）；
     * - [merge] = false：整体替换。
     *
     * **整批校验**：只要有一条不合格就整批拒绝，不落库——逐条静默丢弃会让用户以为导入成功。
     */
    fun importJson(raw: String, merge: Boolean = true): ImportResult {
        val parsed = runCatching { parseRules(raw) }
            .getOrElse { return ImportResult.Err(it.message ?: "JSON 无法解析") }
        if (parsed.isEmpty()) return ImportResult.Err("规则为空")

        val report = RuleValidator.validate(parsed)
        if (!report.ok) return ImportResult.Err(report.summary())

        val existing = getAll()
        val byId = LinkedHashMap<String, LinkRule>()
        var added = 0
        var updated = 0
        if (merge) {
            existing.forEach { byId[it.ruleId] = it }
            parsed.forEach { r ->
                if (byId.containsKey(r.ruleId)) updated++ else added++
                byId[r.ruleId] = r
            }
        } else {
            added = parsed.size
            parsed.forEach { byId[it.ruleId] = it }
        }
        val merged = byId.values.toList()
        val finalReport = RuleValidator.validate(merged)
        if (!finalReport.ok) return ImportResult.Err(finalReport.summary())

        save(merged)
        return ImportResult.Ok(total = merged.size, added = added, updated = updated)
    }

    /** 导出当前生效规则为标准 JSON（可直接再导入） */
    fun exportJson(): String {
        val arr = JSONArray()
        getAll().forEach { arr.put(it.toJson()) }
        return JSONObject()
            .put("version", VERSION)
            .put("rules", arr)
            .toString(2)
    }

    /** 模板原文（assets），供 UI「复制模板」用 */
    fun templateJson(): String = runCatching {
        context.assets.open(TEMPLATE_ASSET).bufferedReader().use { it.readText() }
    }.getOrDefault("""{"version":2,"rules":[]}""")

    /** 恢复默认：清掉 prefs 层，重新读 assets 并写回 */
    fun resetToDefaults(): Int {
        prefs.edit().remove(KEY_RULES).remove(KEY_VERSION).apply()
        cache = null
        return getAll().size
    }

    private fun loadFromPrefs(): List<LinkRule>? {
        val version = prefs.getInt(KEY_VERSION, -1)
        if (version != VERSION) return null
        val json = prefs.getString(KEY_RULES, null) ?: return null
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { LinkRule.fromJson(arr.getJSONObject(it)) }
        }.getOrNull()
    }

    private fun loadDefaults(): List<LinkRule> {
        val json = runCatching {
            context.assets.open(ASSET).bufferedReader().use { it.readText() }
        }.getOrDefault("""{"version":1,"rules":[]}""")
        val obj = JSONObject(json)
        val arr = obj.optJSONArray("rules") ?: JSONArray()
        val list = (0 until arr.length()).map { LinkRule.fromJson(arr.getJSONObject(it)) }
        save(list)
        return list
    }

    /** 兼容三种输入：`{"rules":[...]}`、`{"version":2,"rules":[...]}`、裸数组 `[...]` */
    private fun parseRules(raw: String): List<LinkRule> {
        val text = raw.trim().removePrefix("\uFEFF").trim()
        if (text.isEmpty()) throw IllegalArgumentException("内容为空")
        val arr = if (text.startsWith("[")) {
            JSONArray(text)
        } else {
            val obj = runCatching { JSONObject(text) }
                .getOrElse { throw IllegalArgumentException("不是合法 JSON：${it.message}") }
            obj.optJSONArray("rules")
                ?: throw IllegalArgumentException("缺少 rules 数组（模板格式见「复制模板」）")
        }
        val list = ArrayList<LinkRule>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
                ?: throw IllegalArgumentException("第 ${i + 1} 条不是 JSON 对象")
            val rule = runCatching { LinkRule.fromJson(o) }.getOrElse {
                throw IllegalArgumentException("第 ${i + 1} 条字段缺失或类型不对：${it.message}")
            }
            list += rule
        }
        return list
    }

    companion object {
        private const val PREFS_NAME = "smartlink"
        private const val KEY_RULES = "rules"
        private const val KEY_VERSION = "version"
        private const val ASSET = "smartlink_rules.json"
        private const val TEMPLATE_ASSET = "smartlink_rules_template.json"

        /**
         * 2：默认规则表扩到 38 条（覆盖主流 App）+ 匹配改 host 后缀。
         * **改 assets 必须同时升此值**，否则 [loadFromPrefs] 命中旧 version，
         * 已装机用户永远读旧表——assets 改了等于没改。
         *
         * 副作用（已知）：升版会丢弃用户导入的规则，回到 assets 默认表。
         */
        const val VERSION = 2
    }
}
