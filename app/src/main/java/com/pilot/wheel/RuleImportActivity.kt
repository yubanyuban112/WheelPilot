package com.pilot.wheel

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.pilot.wheel.smartlink.rule.ImportResult
import com.pilot.wheel.smartlink.rule.RuleRepository

/**
 * 规则导入接口（无界面）。
 *
 * 三个调用方式，共用一个实现：
 *  1. **脚本/自动化**：`adb shell am start -a com.pilot.wheel.IMPORT_RULES --es json '<JSON>'`
 *  2. **分享文件**：把 .json 分享给「轻窗 · 规则导入」（ACTION_SEND + application/json）
 *  3. **打开文件**：文件管理器「用其他应用打开」→ 轻窗（ACTION_VIEW + content://）
 *
 * 安全取舍：外部入口**只做合并**（按 ruleId 覆盖/追加），不提供整体替换——即便被任意
 * 应用调用，最坏结果只是多/改几条跳转规则，用户可在设置里「恢复默认规则」一键回退。
 */
class RuleImportActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // `--ez reset true`：脚本化恢复默认表（与设置页「恢复默认规则」同一实现）
        if (intent?.getBooleanExtra(EXTRA_RESET, false) == true) {
            val n = RuleRepository(applicationContext).resetToDefaults()
            trace("reset", "ok total=$n")
            finishWith("已恢复 $n 条默认规则")
            return
        }
        val payload = runCatching { extractPayload(intent) }
            .getOrElse {
                finishWith("导入失败：读取内容出错（${it.message}）")
                return
            }
        if (payload.isNullOrBlank()) {
            finishWith("没有收到 JSON 内容")
            return
        }
        if (payload.length > MAX_CHARS) {
            finishWith("内容过大（>${MAX_CHARS / 1024} KB），已拒绝")
            return
        }
        val result = RuleRepository(applicationContext).importJson(payload, merge = true)
        val source = intent?.action ?: "-"
        val detail = when (result) {
            is ImportResult.Ok -> "ok total=${result.total} added=${result.added} updated=${result.updated}"
            is ImportResult.Err -> "err=${result.reason}"
        }
        Log.w(TAG, "rule import via $source -> $detail")
        trace(source, detail)
        finishWith(result.message() + "\n（已合并到现有规则；可在设置里恢复默认）")
    }

    /** 落盘 trace 走 shell binder，放后台线程避免主线程被绑定的服务拖住 */
    private fun trace(source: String, detail: String) {
        Thread { runCatching { PilotShellClient.trace("RULE_IMPORT", "src=$source $detail") } }.start()
    }

    private fun finishWith(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun extractPayload(intent: Intent?): String? {
        intent ?: return null
        // 1) 显式参数优先（adb / 自动化最常用）
        intent.getStringExtra(EXTRA_JSON)?.takeIf { it.isNotBlank() }?.let { return it }
        // 2) 分享文本
        when (intent.action) {
            Intent.ACTION_SEND -> {
                intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { return it }
                @Suppress("DEPRECATION")
                val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (stream != null) readUri(stream)?.let { return it }
            }
        }
        // 3) 打开/查看的 URI
        intent.data?.let { return readUri(it) }
        return null
    }

    private fun readUri(uri: Uri): String? = runCatching {
        contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
    }.getOrNull()

    companion object {
        private const val TAG = "WheelPilot"

        /** 显式 action：`am start -a com.pilot.wheel.IMPORT_RULES --es json '<JSON>'` */
        const val ACTION_IMPORT_RULES = "com.pilot.wheel.IMPORT_RULES"

        /** JSON 全文字符串参数 */
        const val EXTRA_JSON = "json"

        /** `--ez reset true`：恢复 assets 默认规则表 */
        const val EXTRA_RESET = "reset"

        /** 上限 512 KB，远大于 200 条规则的实际体量，只用来挡误投递的大文件 */
        private const val MAX_CHARS = 512 * 1024
    }
}
