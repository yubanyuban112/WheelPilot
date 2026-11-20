package com.pilot.wheel.smartlink.detect

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.pilot.wheel.core.WindowRect
import java.util.regex.Pattern

/**
 * 57 号：整页取链扫描器。
 *
 * 与旧实现（`LinkDetector.extractFromNode(event.source)`）的差异，都是**覆盖率**问题：
 *
 *  1. `event.source` 常常是容器节点而不是链接节点，链接文本取不全 → 改为遍历
 *     `service.windows` 的全部窗口根，失败再回退 `rootInActiveWindow`；
 *  2. 旧实现把整棵子树的文本拼成一个字符串，只能给出「有/没有一个链接」→ 改为
 *     逐节点提取并在命中节点上取 `getBoundsInScreen`，**每条链接都有坐标**；
 *  3. 父子同链重复：DFS 回溯时把子节点已产出的 URL 集合带上来，父节点同 URL 直接丢弃
 *     （参考 LinkGo 的层次剪枝），避免一个链接在容器与文本节点各算一次。
 *
 * 成本护栏：深度 ≤24、节点数 ≤800、只认 `isVisibleToUser`、跳过自身包与无障碍浮层窗口。
 * 全部开销在 IO 线程执行（编排层负责调度），主线程不参与。
 */
class ScreenLinkScanner {

    data class Result(
        val hits: List<LinkCandidate>,
        val nodesVisited: Int,
        val windowsScanned: Int,
        val costMs: Long,
    )

    private val urlPattern: Pattern = Pattern.compile(URL_REGEX, Pattern.CASE_INSENSITIVE)

    fun scan(service: AccessibilityService, limit: Int = 8): Result {
        val t0 = SystemClock.uptimeMillis()
        val selfPkg = service.packageName
        val raw = ArrayList<LinkCandidate>(16)
        val counter = intArrayOf(0)
        var windowsScanned = 0

        val windows = runCatching { service.windows }.getOrNull()
        if (!windows.isNullOrEmpty()) {
            for (w in windows) {
                if (w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
                val root = runCatching { w.root }.getOrNull() ?: continue
                if (root.packageName?.toString() == selfPkg) continue
                windowsScanned++
                walk(root, 0, counter, raw, selfPkg)
            }
        }

        // 窗口列表拿不到（部分 ROM 限制 `windows`）时回退到活动窗口
        if (raw.isEmpty()) {
            val active = runCatching { service.rootInActiveWindow }.getOrNull()
            if (active != null && active.packageName?.toString() != selfPkg) {
                windowsScanned++
                walk(active, 0, counter, raw, selfPkg)
            }
        }

        val hits = LinkScanRules.finalize(raw, limit)
        return Result(hits, counter[0], windowsScanned, SystemClock.uptimeMillis() - t0)
    }

    /**
     * 深度优先遍历。返回后调用方从 [out] 的新增区间里收集本次子树的 URL 集合，
     * 用于「子节点已含同 URL，父节点不保留」的剪枝。
     */
    private fun walk(
        node: AccessibilityNodeInfo?,
        depth: Int,
        counter: IntArray,
        out: MutableList<LinkCandidate>,
        selfPkg: String,
    ) {
        node ?: return
        if (depth > MAX_DEPTH || counter[0] >= MAX_NODES) return
        counter[0]++

        val childUrls = HashSet<String>()
        for (i in 0 until node.childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            val before = out.size
            walk(child, depth + 1, counter, out, selfPkg)
            for (k in before until out.size) childUrls.add(out[k].url)
        }

        if (!runCatching { node.isVisibleToUser }.getOrDefault(false)) return
        if (node.packageName?.toString() == selfPkg) return

        val text = nodeText(node)
        if (text.isBlank()) return

        val m = urlPattern.matcher(text)
        var found = 0
        while (m.find() && found < MAX_LINKS_PER_NODE) {
            val url = normalize(m.group() ?: continue)
            if (url.isEmpty()) continue
            if (url in childUrls) continue // 层次剪枝：子节点已产出同一链接
            val r = Rect()
            runCatching { node.getBoundsInScreen(r) }
            if (r.width() <= 0 || r.height() <= 0) continue
            out.add(
                LinkCandidate(
                    url = url,
                    text = text,
                    bounds = WindowRect(r.left, r.top, r.right, r.bottom),
                    textOffset = m.start(),
                ),
            )
            found++
        }
    }

    /** 只取节点自身的 text / contentDescription，不用子孙文本 —— 保证坐标落在链接本身 */
    private fun nodeText(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        runCatching { node.text?.toString() }.getOrNull()?.takeIf { it.isNotBlank() }?.let {
            sb.append(it)
        }
        runCatching { node.contentDescription?.toString() }.getOrNull()
            ?.takeIf { it.isNotBlank() && !sb.contains(it) }
            ?.let { if (sb.isNotEmpty()) sb.append(' '); sb.append(it) }
        return sb.toString().trim()
    }

    /** 补全 www. 前缀、去尾部标点（与 LinkDetector 同一口径） */
    private fun normalize(raw: String): String {
        var s = raw.trim().trimEnd('.', ',', ';', '!', '?', ')', ']', '}', '，', '。', '、')
        if (s.length <= 4) return ""
        if (s.startsWith("www.", ignoreCase = true) && !s.lowercase().startsWith("http")) {
            s = "https://$s"
        }
        return s
    }

    companion object {
        private const val TAG = "WheelPilot"
        private const val MAX_DEPTH = 24
        private const val MAX_NODES = 800
        private const val MAX_LINKS_PER_NODE = 6

        /** 与 LinkDetector 保持一致：http(s)/www 开头，遇空白与包裹符号止 */
        private const val URL_REGEX = "(?i)\\b(?:https?://|www\\.)[^\\s<>\"']+"

        /** 诊断：把一次扫描压成一行，落 trace.log（SmartLink 默认关，只能靠日志验收） */
        fun describe(result: Result): String =
            "hits=${result.hits.size} nodes=${result.nodesVisited} wins=${result.windowsScanned} " +
                "cost=${result.costMs}ms" +
                result.hits.firstOrNull()?.let { " first=${it.url.take(60)}@${it.bounds.left},${it.bounds.top}" }
                    .orEmpty()

        fun trace(tag: String, message: String) {
            Log.w(TAG, "$tag $message")
        }
    }
}
