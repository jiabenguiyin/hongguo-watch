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
    fun toStreamInfo() = StreamInfo(url, key, if (quality > 0) "${quality}P" else "自动")
}

class StreamLadder(val renditions: List<Rendition>) {
    private companion object {
        val AVC_LABELS = setOf("h264", "avc", "avc1")
        val better =
            compareBy<Rendition> { it.quality }.thenBy { if (it.codec in AVC_LABELS) 1 else 0 }
    }

    fun select(maxQuality: Int, supported: (Rendition) -> Boolean = { true }): Rendition? {
        val pool = renditions.filter(supported)
        return pool.filter { it.quality in 1..maxQuality }.maxWithOrNull(better)
            ?: pool.filter { it.quality <= 0 }.maxWithOrNull(better)
            ?: pool.maxWithOrNull(better)
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
