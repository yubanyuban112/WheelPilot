package com.pilot.wheel.smartlink.detect

import com.pilot.wheel.core.WindowRect

/**
 * 57 号：一次屏幕扫描的候选链接（纯数据，便于 JVM 单测）。
 *
 * 与 [DetectedLink] 的区别：这里只描述「扫到的东西」，不做置信度/通道判断；
 * 编排层再把选中的候选转成 [DetectedLink]。
 */
data class LinkCandidate(
    val url: String,
    val text: String,
    val bounds: WindowRect,
    /** 同一节点内的字符偏移（多链接节点按出现顺序排） */
    val textOffset: Int = 0,
)

/**
 * 57 号：扫描结果的去重与排序规则（纯函数，可单测）。
 *
 * 规则来源与差异说明：参考实现（LinkGo `SelectToSpeakService.sortAndFilterResults`）
 * 用「URL + 首矩形」去重、按 10px 的 top 桶分桶排序；这里沿用同一思路，但把
 * 「同 URL 只保留一处」的判据改成 `url + 左上角`，避免同一链接被外层容器与内层文本各算一次。
 */
object LinkScanRules {

    /** 排序用的行桶高度（px）：同一视觉行内的小位移视为同一行 */
    const val LINE_BUCKET_PX = 10

    /** 去重：同 URL 同位置只留一条 */
    fun dedupe(candidates: List<LinkCandidate>): List<LinkCandidate> =
        candidates.distinctBy { it.url + "@" + it.bounds.left + "," + it.bounds.top }

    /**
     * 最终排序：先按视觉行（top 桶）自上而下，再按 left 自左向右，最后按节点内偏移。
     * 这样胶囊/高亮在阅读顺序上稳定，不会因为节点树遍历顺序抖动而跳位。
     */
    fun sort(candidates: List<LinkCandidate>): List<LinkCandidate> =
        candidates.sortedWith(
            compareBy(
                { it.bounds.top / LINE_BUCKET_PX },
                { it.bounds.left },
                { it.textOffset },
            ),
        )

    /** 去重 + 排序，并截断到 [limit] 条（扫描结果只用于交互提示，不需要全量） */
    fun finalize(candidates: List<LinkCandidate>, limit: Int = 8): List<LinkCandidate> =
        sort(dedupe(candidates)).take(limit.coerceAtLeast(1))
}
