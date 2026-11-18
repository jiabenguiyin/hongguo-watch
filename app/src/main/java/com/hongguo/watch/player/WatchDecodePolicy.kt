package com.hongguo.watch.player

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import java.util.Locale

/**
 * 手表 / 展讯·联发低配 SoC 上的解码策略：
 * - 默认优先软件视频解码（硬解常「成功」却只出黑帧、音频正常）
 * - 清晰度选择侧再强偏 AVC，避免 HEVC 硬解黑屏
 */
object WatchDecodePolicy {
    private const val WATCH_MAX_SHORT_SIDE_PX = 480
    private const val WATCH_MAX_LONG_SIDE_PX = 640

    fun shouldPreferSoftwareDecode(context: Context): Boolean {
        if (isWatchLike(context)) return true
        if (isFragileSoc()) return true
        val mem = memoryClassMb(context)
        return mem in 1..192
    }

    fun isFragileSoc(
        manufacturer: String = Build.MANUFACTURER.orEmpty(),
        brand: String = Build.BRAND.orEmpty(),
        hardware: String = Build.HARDWARE.orEmpty(),
        board: String = Build.BOARD.orEmpty(),
        model: String = Build.MODEL.orEmpty(),
    ): Boolean {
        val id = listOf(manufacturer, brand, hardware, board, model)
            .joinToString(" ")
            .lowercase(Locale.US)
        return listOf(
            "unisoc",
            "spreadtrum",
            "sprd",
            "w527",
            "ums",
            "sc98",
            "sc77",
            "sp7731",
            "sp9832",
        ).any { it in id }
    }

    fun isWatchLike(context: Context): Boolean {
        val pm = context.packageManager
        if (pm.hasSystemFeature(PackageManager.FEATURE_WATCH)) return true
        val metrics = context.resources.displayMetrics
        return isWatchLike(
            Build.MODEL,
            Build.BRAND,
            Build.MANUFACTURER,
            Build.PRODUCT,
            metrics.widthPixels,
            metrics.heightPixels,
        )
    }

    fun isWatchLike(
        model: String?,
        brand: String?,
        manufacturer: String?,
        product: String?,
        widthPixels: Int,
        heightPixels: Int,
    ): Boolean {
        val identity = listOf(model, brand, manufacturer, product)
            .joinToString(" ") { it.orEmpty() }
            .lowercase(Locale.US)
        if (
            "watch" in identity ||
            "smartwatch" in identity ||
            "wear" in identity ||
            "aqsh" in identity ||
            "dove_evb" in identity
        ) {
            return true
        }
        if (widthPixels <= 0 || heightPixels <= 0) return false
        val shortSide = minOf(widthPixels, heightPixels)
        val longSide = maxOf(widthPixels, heightPixels)
        return shortSide <= WATCH_MAX_SHORT_SIDE_PX && longSide <= WATCH_MAX_LONG_SIDE_PX
    }

    @OptIn(UnstableApi::class)
    fun renderersFactory(context: Context, preferSoftwareVideo: Boolean): DefaultRenderersFactory {
        val factory = DefaultRenderersFactory(context.applicationContext)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
        if (preferSoftwareVideo) {
            factory.setMediaCodecSelector(softwareFirstVideoSelector())
        }
        return factory
    }

    @OptIn(UnstableApi::class)
    fun softwareFirstVideoSelector(): MediaCodecSelector =
        MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
            val infos = MediaCodecUtil.getDecoderInfos(
                mimeType,
                requiresSecureDecoder,
                requiresTunnelingDecoder,
            )
            if (!MimeTypes.isVideo(mimeType) || infos.size <= 1) {
                return@MediaCodecSelector infos
            }
            infos.sortedWith(
                compareByDescending<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> {
                    softwareRank(it)
                }.thenBy { it.name },
            )
        }

    @OptIn(UnstableApi::class)
    private fun softwareRank(info: androidx.media3.exoplayer.mediacodec.MediaCodecInfo): Int {
        if (info.softwareOnly) return 3
        val name = info.name.lowercase(Locale.US)
        return when {
            name.startsWith("c2.android.") || name.startsWith("omx.google.") -> 3
            ".sw." in name || name.endsWith(".sw") || name.contains("soft") -> 2
            info.hardwareAccelerated -> 0
            else -> 1
        }
    }

    private fun memoryClassMb(context: Context): Int =
        runCatching {
            (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
                ?.memoryClass
                ?: 0
        }.getOrDefault(0)
}
