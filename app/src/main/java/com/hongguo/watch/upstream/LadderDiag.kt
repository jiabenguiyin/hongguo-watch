// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.watch.upstream

/**
 * 上游 video_list 候选档位诊断行：保留原始字段，说明为何入选/被滤掉。
 * 用于证明「真没有 AVC」还是「有但被误解析/误分类」。
 */
data class LadderEntryDiag(
    val index: Int,
    val listKey: String,
    val codecType: String,
    val gearDesKey: String,
    val videoMetaCodec: String,
    val videoMetaSummary: String,
    val width: Int?,
    val height: Int?,
    val quality: Int,
    val normalizedCodec: String,
    val isAvc: Boolean,
    val isHevc: Boolean,
    val hasUrl: Boolean,
    val accepted: Boolean,
    val filterReason: String,
) {
    fun toLogLine(): String {
        val res = when {
            width != null && height != null -> "${width}x${height}"
            height != null -> "?x$height"
            width != null -> "${width}x?"
            else -> "-"
        }
        val status = if (accepted) "KEEP" else "DROP"
        return buildString {
            append("ladder[$index] $status")
            append(" q=${if (quality > 0) quality else "?"}")
            append(" res=$res")
            append(" codec_type=${codecType.ifBlank { "-" }}")
            append(" gear_des_key=${gearDesKey.ifBlank { "-" }}")
            append(" video_meta.codec=${videoMetaCodec.ifBlank { "-" }}")
            append(" video_meta={$videoMetaSummary}")
            append(" norm=${normalizedCodec.ifBlank { "-" }}")
            append(" avc=$isAvc hevc=$isHevc url=$hasUrl")
            append(" reason=$filterReason")
            if (listKey.isNotBlank()) append(" key=$listKey")
        }
    }
}

data class LadderParseResult(
    val accepted: List<Rendition>,
    val diag: List<LadderEntryDiag>,
) {
    val avcAccepted: Int get() = accepted.count { it.isAvc }
    val hevcAccepted: Int get() = accepted.count { it.isHevc }
    val dropped: Int get() = diag.count { !it.accepted }

    fun summaryLine(
        maxQuality: Int,
        preferAvc: Boolean,
        avcOnly: Boolean,
    ): String {
        val rawAvcHints = diag.count {
            CodecLabels.isAvc(it.codecType) ||
                CodecLabels.isAvc(it.gearDesKey) ||
                CodecLabels.isAvc(it.videoMetaCodec)
        }
        val verdict = when {
            avcAccepted > 0 -> "AVC_PRESENT"
            rawAvcHints > 0 -> "AVC_HINT_BUT_DROPPED_OR_MISCLASSIFIED"
            diag.any { it.isHevc || CodecLabels.isHevc(it.codecType) || CodecLabels.isHevc(it.gearDesKey) } ->
                "AVC_MISSING_HEVC_ONLY"
            diag.isEmpty() -> "EMPTY_VIDEO_LIST"
            else -> "NO_AVC_NO_CLEAR_HEVC"
        }
        return "ladder summary total=${diag.size} keep=${accepted.size} drop=$dropped " +
            "avcKeep=$avcAccepted hevcKeep=$hevcAccepted rawAvcHints=$rawAvcHints " +
            "cap=${maxQuality}p preferAvc=$preferAvc avcOnly=$avcOnly verdict=$verdict"
    }
}
