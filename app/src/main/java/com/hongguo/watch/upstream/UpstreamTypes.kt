// SPDX-License-Identifier: GPL-3.0-only
// Types extracted from N3urda/hongguoTV ContentRepository.kt for watch App-path use.
package com.hongguo.watch.upstream

import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

internal fun JSONArray.objects() = (0 until length()).mapNotNull { optJSONObject(it) }

internal fun JSONObject.str(key: String) = if (isNull(key)) "" else optString(key, "").trim()

internal fun cleanUrl(s: String) =
    s.replace("\\/", "/")
        .replace("\\u002F", "/", ignoreCase = true)
        .replace("\\u0026", "&", ignoreCase = true)
        .replace("&amp;", "&")
        .trim()

data class StreamInfo(val url: String, val key: ByteArray?, val quality: String)

data class Rendition(
    val quality: Int,
    val codec: String,
    val width: Int?,
    val height: Int?,
    val url: String,
    val key: ByteArray?,
) {
    val isAvc: Boolean get() = CodecLabels.isAvc(codec)
    val isHevc: Boolean get() = CodecLabels.isHevc(codec)

    fun toStreamInfo() = StreamInfo(url, key, if (quality > 0) "${quality}P" else "自动")
}

class StreamLadder(val renditions: List<Rendition>) {
    private companion object {
        /** 同池内：清晰度优先，其次 AVC 优于 HEVC/其它。 */
        val byQualityThenAvc =
            compareBy<Rendition> { it.quality }.thenBy { if (it.isAvc) 1 else 0 }
    }

    /**
     * @param preferAvc 默认 true：上限内只要有 AVC 就选 AVC（手表 SoC 上 HEVC 硬解常有声无画）。
     * @param avcOnly 仅保留 AVC；无 AVC 时返回 null（由调用方决定是否放宽）。
     */
    fun select(
        maxQuality: Int,
        preferAvc: Boolean = true,
        avcOnly: Boolean = false,
        supported: (Rendition) -> Boolean = { true },
    ): Rendition? {
        val pool = renditions.filter(supported)
        if (pool.isEmpty()) return null
        if (avcOnly) {
            return pick(pool.filter { it.isAvc }, maxQuality)
        }
        if (preferAvc) {
            pick(pool.filter { it.isAvc }, maxQuality)?.let { return it }
        }
        return pick(pool, maxQuality)
    }

    private fun pick(candidates: List<Rendition>, maxQuality: Int): Rendition? {
        if (candidates.isEmpty()) return null
        return candidates.filter { it.quality in 1..maxQuality }.maxWithOrNull(byQualityThenAvc)
            ?: candidates.filter { it.quality <= 0 }.maxWithOrNull(byQualityThenAvc)
            ?: candidates.maxWithOrNull(byQualityThenAvc)
    }
}

class ContentHttpException(val statusCode: Int) : IOException("内容请求失败（HTTP $statusCode）")

class EmptyPlaybackResponseException(
    val detail: String? = null,
    internal val allCompatibilityModelsEmpty: Boolean = false,
) : IOException(detail?.let { "播放响应为空（$it）" } ?: "播放响应为空")

internal fun qualityNumber(value: String): Int =
    Regex("2160|1440|1080|720|576|540|480|360").find(value)?.value?.toInt()
        ?: mapOf("1920" to 1080, "1280" to 720, "1024" to 576, "854" to 480, "640" to 360)[value]
        ?: value.toIntOrNull()
        ?: 0
