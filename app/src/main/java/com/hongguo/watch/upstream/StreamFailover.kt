// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.watch.upstream

/**
 * 播放失败时沿清晰度阶梯自动切流：排除已失败档，再走 [StreamLadder.select]。
 * 不改用户清晰度设置；会话内最多 [MAX_ATTEMPTS] 次切流/兼容回退合计。
 */
object StreamFailover {
    /** 单集会话内自动恢复上限（切流 + 解码策略切换合计）。 */
    const val MAX_ATTEMPTS = 4

    /** 首帧后卡缓冲无进度超时（毫秒）。 */
    const val STALL_TIMEOUT_MS = 12_000L

    fun key(rendition: Rendition): String =
        "${rendition.quality}|${CodecLabels.normalize(rendition.codec)}|${rendition.url}"

    fun key(qualityLabel: String, codec: String, url: String): String {
        val q = Regex("(\\d{3,4})").find(qualityLabel)?.value?.toIntOrNull()
            ?: qualityNumber(qualityLabel)
        return "$q|${CodecLabels.normalize(codec)}|$url"
    }

    /** Selects a mirror of the same rendition without changing quality or codec. */
    fun pickBackup(rendition: Rendition, excludeKeys: Set<String>): Rendition? {
        val remaining = rendition.backupUrls.distinct().filter { url ->
            url != rendition.url && key(rendition.copy(url = url)) !in excludeKeys
        }
        val url = remaining.firstOrNull() ?: return null
        return rendition.copy(url = url, backupUrls = remaining.drop(1), isCdnBackup = true)
    }

    /**
     * 在排除已失败档后选下一档。preferAvc 时先尽 AVC（含降档），再才 HEVC。
     * @return null 表示当前策略下已无候选。
     */
    fun pick(
        renditions: List<Rendition>,
        maxQuality: Int,
        excludeKeys: Set<String>,
        preferAvc: Boolean = true,
        avcOnly: Boolean = false,
    ): Rendition? {
        if (renditions.isEmpty()) return null
        val pool = renditions.filter { key(it) !in excludeKeys }
        if (pool.isEmpty()) return null
        return StreamLadder(pool).select(
            maxQuality = maxQuality.coerceIn(240, 1080),
            preferAvc = preferAvc,
            avcOnly = avcOnly,
        )
    }

    fun hasCandidate(
        renditions: List<Rendition>,
        maxQuality: Int,
        excludeKeys: Set<String>,
        preferAvc: Boolean = true,
        avcOnly: Boolean = false,
    ): Boolean = pick(renditions, maxQuality, excludeKeys, preferAvc, avcOnly) != null

    /** 下一档为 HEVC/bytevc1 时，优先走 IJK 软解更安全。 */
    fun shouldPreferIjkSoft(next: Rendition): Boolean = next.isHevc

    fun describe(rendition: Rendition): String {
        val q = if (rendition.quality > 0) "${rendition.quality}p" else "auto"
        val codec = rendition.codec.ifBlank { "?" }
        return "$q · $codec"
    }
}
