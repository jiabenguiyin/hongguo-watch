package com.hongguo.watch.player

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import java.util.Locale

/**
 * 枚举本机 AVC / HEVC 解码器能力（名称、软硬、profile/level、最大分辨率），写入诊断报告。
 */
object MediaCodecCaps {
    data class DecoderLine(
        val mime: String,
        val name: String,
        val software: Boolean,
        val hardware: Boolean,
        val profiles: String,
        val maxWidth: Int,
        val maxHeight: Int,
        val maxInstances: Int,
    ) {
        fun toLogLine(): String {
            val kind = when {
                software && !hardware -> "sw"
                hardware && !software -> "hw"
                software && hardware -> "sw+hw?"
                else -> "?"
            }
            return "decoder mime=$mime name=$name kind=$kind " +
                "max=${maxWidth}x$maxHeight profiles=$profiles instances=$maxInstances"
        }
    }

    fun enumerateAvcHevc(): List<DecoderLine> {
        val mimeTargets = listOf(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            MediaFormat.MIMETYPE_VIDEO_HEVC,
        )
        val out = ArrayList<DecoderLine>(8)
        val list = MediaCodecList(MediaCodecList.ALL_CODECS)
        for (info in list.codecInfos) {
            if (info.isEncoder) continue
            for (mime in info.supportedTypes) {
                val normalized = mime.lowercase(Locale.US)
                if (mimeTargets.none { it.equals(normalized, ignoreCase = true) }) continue
                out += describe(info, normalized)
            }
        }
        return out.sortedWith(
            compareBy<DecoderLine> { it.mime }
                .thenByDescending { if (it.software) 1 else 0 }
                .thenBy { it.name },
        )
    }

    /** 本机是否有可用 HEVC 硬解（不含软解伪装名）。CD_Watch_A 等 fragileSoc 通常为 false。 */
    fun hasHevcHardware(): Boolean =
        enumerateAvcHevc().any { it.mime.contains("hevc") && it.hardware && !it.software }

    fun summaryLines(): List<String> {
        val lines = enumerateAvcHevc()
        if (lines.isEmpty()) {
            return listOf("decoder none for AVC/HEVC")
        }
        val avc = lines.filter { it.mime.contains("avc") }
        val hevc = lines.filter { it.mime.contains("hevc") }
        val summary = buildString {
            append("decoder summary avc=${avc.size} hevc=${hevc.size}")
            append(" avcSw=${avc.count { it.software }} avcHw=${avc.count { it.hardware }}")
            append(" hevcSw=${hevc.count { it.software }} hevcHw=${hevc.count { it.hardware }}")
            append(" hasHevcHw=${hasHevcHardware()}")
            append(
                " usableHevcSoft=${hevc.any { it.software && it.maxWidth >= 640 && it.maxHeight >= 360 }}",
            )
        }
        return listOf(summary) + lines.map { it.toLogLine() }
    }

    private fun describe(info: MediaCodecInfo, mime: String): DecoderLine {
        val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull()
        val video = caps?.videoCapabilities
        val maxW = video?.supportedWidths?.upper ?: 0
        val maxH = video?.supportedHeights?.upper ?: 0
        val profiles = caps?.profileLevels
            ?.joinToString(",") { pl ->
                "p${pl.profile}/l${pl.level}"
            }
            ?.ifBlank { "-" }
            ?: "-"
        val software = isSoftware(info)
        val hardware = isHardware(info, software)
        val instances = if (Build.VERSION.SDK_INT >= 23) {
            caps?.maxSupportedInstances ?: -1
        } else {
            -1
        }
        return DecoderLine(
            mime = mime,
            name = info.name,
            software = software,
            hardware = hardware,
            profiles = profiles,
            maxWidth = maxW,
            maxHeight = maxH,
            maxInstances = instances,
        )
    }

    private fun isSoftware(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= 29) {
            @Suppress("NewApi")
            if (info.isSoftwareOnly) return true
        }
        val name = info.name.lowercase(Locale.US)
        return name.startsWith("c2.android.") ||
            name.startsWith("omx.google.") ||
            ".sw." in name ||
            name.endsWith(".sw") ||
            name.contains("soft")
    }

    private fun isHardware(info: MediaCodecInfo, software: Boolean): Boolean {
        if (Build.VERSION.SDK_INT >= 29) {
            @Suppress("NewApi")
            if (info.isHardwareAccelerated) return true
        }
        return !software
    }
}
