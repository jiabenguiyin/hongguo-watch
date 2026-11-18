// SPDX-License-Identifier: GPL-3.0-only
// Adapted from N3urda/hongguoTV PlaybackModels.kt for watch App-path use.
package com.hongguo.watch.upstream

import java.io.IOException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

/** A deterministic source failure, unlike a transient transport failure. */
class PlaybackSourceException(val reason: Reason, val upstreamCode: Int? = null) : IOException(
    when (reason) {
        Reason.REJECTED -> "上游拒绝播放请求（错误码 $upstreamCode），请稍后手动重试"
        Reason.HTTP_REJECTED -> "播放请求被拒绝（HTTP $upstreamCode），请稍后手动重试"
        Reason.INVALID_RESPONSE -> "播放信息格式异常，请稍后手动重试"
        Reason.NO_PLAYABLE_SOURCE -> "没有可播放的视频资源，可尝试其他清晰度或剧集"
    },
) {
    enum class Reason { REJECTED, HTTP_REJECTED, INVALID_RESPONSE, NO_PLAYABLE_SOURCE }
}

/** Pure parsing only: no device registration, requests or entitlement changes. */
internal object PlaybackModels {
    fun checkStatus(data: JSONObject) {
        val code = data.optInt("Code", data.optInt("code", 0))
        if (code != 0) throw PlaybackSourceException(PlaybackSourceException.Reason.REJECTED, code)
    }

    fun objectValue(value: Any?): JSONObject? = when (value) {
        is JSONObject -> value
        is String -> runCatching { JSONObject(value) }.getOrNull()
        else -> null
    }

    fun model(data: JSONObject, id: String): JSONObject? {
        val entry = data.optJSONObject(id) ?: data.takeIf { it.has("video_model") }
        return objectValue(entry?.opt("video_model"))
    }

    private fun url(raw: String): String? = cleanUrl(raw).trim().toHttpUrlOrNull()?.takeIf {
        it.username.isEmpty() && it.password.isEmpty()
    }?.toString()

    fun renditions(model: JSONObject): List<Rendition> = parseLadder(model).accepted

    /** 解析 video_list，同时产出完整候选诊断（含被滤掉的档）。 */
    fun parseLadder(model: JSONObject): LadderParseResult {
        val list = model.opt("video_list")
        val entries = when (list) {
            is JSONObject -> list.keys().asSequence().mapNotNull { key ->
                list.optJSONObject(key)?.let { key to it }
            }.toList()
            is JSONArray -> list.objects().map { "" to it }
            else -> emptyList()
        }
        if (entries.isEmpty()) {
            return LadderParseResult(emptyList(), emptyList())
        }
        val accepted = ArrayList<Rendition>(entries.size)
        val diag = ArrayList<LadderEntryDiag>(entries.size)
        entries.forEachIndexed { index, (name, entry) ->
            val parsed = parseEntry(model, name, entry, index)
            diag += parsed.diag
            parsed.rendition?.let { accepted += it }
        }
        return LadderParseResult(accepted, diag)
    }

    private data class ParsedEntry(
        val rendition: Rendition?,
        val diag: LadderEntryDiag,
    )

    private fun parseEntry(
        model: JSONObject,
        name: String,
        entry: JSONObject,
        index: Int,
    ): ParsedEntry {
        val codecType = entry.str("codec_type")
        val gearDesKey = entry.str("gear_des_key")
        val meta = entry.optJSONObject("video_meta")
        val videoMetaCodec = meta?.str("codec_type").orEmpty()
        val videoMetaSummary = summarizeMeta(meta)
        val width = entry.optInt("vwidth", meta?.optInt("vwidth", 0) ?: 0).takeIf { it > 0 }
        val height = entry.optInt("vheight", meta?.optInt("vheight", 0) ?: 0).takeIf { it > 0 }
        val dimensions = if (width != null && height != null) {
            minOf(width, height).toString()
        } else {
            ""
        }
        val quality = qualityNumber(
            entry.str("quality_desc").ifEmpty {
                meta?.str("definition").orEmpty()
            }.ifEmpty {
                dimensions.ifEmpty {
                    entry.str("height").ifEmpty {
                        entry.str("vheight").ifEmpty {
                            entry.str("quality").ifEmpty { name }
                        }
                    }
                }
            },
        )
        val labelHint = listOf(codecType, videoMetaCodec, gearDesKey)
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
            .lowercase()
        val label = when {
            CodecLabels.isAvc(labelHint) -> CodecLabels.normalize(labelHint).ifBlank { "h264" }
                .let { if (it == "avc") "h264" else it }
            CodecLabels.isHevc(labelHint) -> CodecLabels.normalize(labelHint).ifBlank { "bytevc1" }
            else -> labelHint
        }
        val isAvc = CodecLabels.isAvc(label)
        val isHevc = CodecLabels.isHevc(label)
        fun drop(reason: String, hasUrl: Boolean = false) = ParsedEntry(
            rendition = null,
            diag = LadderEntryDiag(
                index = index,
                listKey = name,
                codecType = codecType,
                gearDesKey = gearDesKey,
                videoMetaCodec = videoMetaCodec,
                videoMetaSummary = videoMetaSummary,
                width = width,
                height = height,
                quality = quality,
                normalizedCodec = label,
                isAvc = isAvc,
                isHevc = isHevc,
                hasUrl = hasUrl,
                accepted = false,
                filterReason = reason,
            ),
        )

        return try {
            val codecBlob = listOf(codecType, gearDesKey, videoMetaCodec)
                .joinToString(" ")
                .lowercase()
            if (codecBlob.contains("bytevc2") ||
                Regex("(^|[^a-z0-9])bvc2([^a-z0-9]|$)").containsMatchIn(codecBlob)
            ) {
                return drop("unsupported_bytevc2")
            }
            val seed = model.str("key_seed").takeIf { it.isNotEmpty() }?.let(::decode64)
            val encryption = entry.optJSONObject("encrypt_info")
            val key = encryption?.str("spade_a").orEmpty().ifEmpty { entry.str("spade_a") }
                .takeIf { it.isNotEmpty() }?.let(MediaCrypto::deriveKey)
            if (encryption != null && key == null &&
                encryption.opt("encrypt") !in listOf(false, 0, "0", "false")
            ) {
                return drop("encrypt_key_missing")
            }
            val resolved = listOf("main_url", "play_url", "backup_url_1").firstNotNullOfOrNull { field ->
                runCatching {
                    val raw = cleanUrl(entry.str(field))
                    if (raw.isEmpty()) {
                        null
                    } else {
                        url(raw) ?: url(
                            if (seed != null) MediaCrypto.decryptUrl(raw, seed)
                            else String(decode64(raw), Charsets.UTF_8),
                        )
                    }
                }.getOrNull()
            }
            if (resolved == null) {
                return drop("no_playable_url")
            }
            val rendition = Rendition(
                quality,
                label,
                width,
                height,
                resolved,
                key,
            )
            ParsedEntry(
                rendition = rendition,
                diag = LadderEntryDiag(
                    index = index,
                    listKey = name,
                    codecType = codecType,
                    gearDesKey = gearDesKey,
                    videoMetaCodec = videoMetaCodec,
                    videoMetaSummary = videoMetaSummary,
                    width = width,
                    height = height,
                    quality = quality,
                    normalizedCodec = label,
                    isAvc = rendition.isAvc,
                    isHevc = rendition.isHevc,
                    hasUrl = true,
                    accepted = true,
                    filterReason = "accepted",
                ),
            )
        } catch (err: Exception) {
            drop("parse_exception:${err.javaClass.simpleName}")
        }
    }

    private fun summarizeMeta(meta: JSONObject?): String {
        if (meta == null) return "-"
        val keys = listOf(
            "codec_type",
            "definition",
            "quality",
            "vwidth",
            "vheight",
            "bitrate",
            "fps",
            "format",
        )
        return keys.mapNotNull { key ->
            val value = when {
                meta.isNull(key) -> null
                else -> meta.opt(key)?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
            }
            value?.let { "$key=$it" }
        }.joinToString(",").ifBlank { "-" }
    }
}
