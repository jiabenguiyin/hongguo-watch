package com.hongguo.watch.upstream

import java.util.Locale

/** 归一化上游 codec_type，便于清晰度阶梯里偏置 AVC、避开脆弱 HEVC。 */
object CodecLabels {
    private val AVC = setOf("h264", "avc", "avc1", "avc3")
    private val HEVC = setOf("h265", "hevc", "hev1", "hvc1", "bytevc1", "bvc1")

    fun normalize(raw: String): String =
        raw.trim().lowercase(Locale.US).substringBefore('.').substringBefore(',')

    fun isAvc(raw: String): Boolean {
        val n = normalize(raw)
        if (n in AVC) return true
        return AVC.any { it in n } && !isHevc(n)
    }

    fun isHevc(raw: String): Boolean {
        val n = normalize(raw)
        if (n in HEVC) return true
        return HEVC.any { it in n }
    }
}
