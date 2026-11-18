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
 * - 黑屏二段回退：仅保留软件 MediaCodec（排除硬解）
 * - 清晰度选择侧再强偏 AVC，避免 HEVC 硬解黑屏
 */
object WatchDecodePolicy {
    private const val WATCH_MAX_SHORT_SIDE_PX = 480
    private const val WATCH_MAX_LONG_SIDE_PX = 640

    /** 脆弱 SoC / 低内存默认清晰度上限（≤480p AVC）。 */
    const val FRAGILE_DEFAULT_MAX_QUALITY = 480

    /** 低内存阈值（MB）；CD12 Max / UWS6152 实测 memClass=192。 */
    const val LOW_MEM_CLASS_MB = 192

    enum class VideoCodecMode {
        /** 系统默认顺序 */
        Default,

        /** 软件实现排前，硬解仍可作 fallback */
        SoftwareFirst,

        /** 仅软件实现；无软解时才退回原列表 */
        SoftwareOnly,
    }

    fun shouldPreferSoftwareDecode(context: Context): Boolean {
        if (isWatchLike(context)) return true
        if (isFragileSoc()) return true
        val mem = memoryClassMb(context)
        return mem in 1..LOW_MEM_CLASS_MB
    }

    /** 脆弱 SoC / 手表：首帧超时更短，尽快切到 AVC + 更强软解。 */
    fun firstFrameTimeoutMs(context: Context): Long {
        return if (shouldPreferSoftwareDecode(context)) 3_500L else 5_000L
    }

    /** 脆弱设备开播即硬性 AVC-only（无 AVC 则失败，不再回落 bytevc1）。 */
    fun shouldForceAvcOnlyInitially(context: Context): Boolean {
        return isFragileSoc() || isWatchLike(context)
    }

    /**
     * 脆弱 SoC + 低内存：有效清晰度上限压到 ≤480p（仍可选更低；用户设 720 也会被钳制）。
     * 其它设备尊重用户设置。
     */
    fun effectiveMaxQuality(context: Context, userMaxQuality: Int): Int =
        effectiveMaxQuality(
            userMaxQuality = userMaxQuality,
            fragileSoc = isFragileSoc(),
            memClassMb = memoryClassMb(context),
        )

    fun effectiveMaxQuality(userMaxQuality: Int, fragileSoc: Boolean, memClassMb: Int): Int {
        val user = userMaxQuality.coerceIn(240, 1080)
        if (!fragileSoc) return user
        return if (memClassMb in 1..LOW_MEM_CLASS_MB) {
            minOf(user, FRAGILE_DEFAULT_MAX_QUALITY)
        } else {
            user
        }
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
            "uws", // CD12 Max / UWS6152（paste kzty6）
            "sc98",
            "sc77",
            "sp7731",
            "sp9832",
            "cd12",
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
            "dove_evb" in identity ||
            "cd12" in identity
        ) {
            return true
        }
        if (widthPixels <= 0 || heightPixels <= 0) return false
        val shortSide = minOf(widthPixels, heightPixels)
        val longSide = maxOf(widthPixels, heightPixels)
        return shortSide <= WATCH_MAX_SHORT_SIDE_PX && longSide <= WATCH_MAX_LONG_SIDE_PX
    }

    @OptIn(UnstableApi::class)
    fun renderersFactory(context: Context, mode: VideoCodecMode): DefaultRenderersFactory {
        val factory = DefaultRenderersFactory(context.applicationContext)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
        when (mode) {
            VideoCodecMode.Default -> Unit
            VideoCodecMode.SoftwareFirst ->
                factory.setMediaCodecSelector(softwareFirstVideoSelector())
            VideoCodecMode.SoftwareOnly ->
                factory.setMediaCodecSelector(softwareOnlyVideoSelector())
        }
        return factory
    }

    /** 兼容旧调用：preferSoftware → SoftwareFirst。 */
    @OptIn(UnstableApi::class)
    fun renderersFactory(context: Context, preferSoftwareVideo: Boolean): DefaultRenderersFactory =
        renderersFactory(
            context,
            if (preferSoftwareVideo) VideoCodecMode.SoftwareFirst else VideoCodecMode.Default,
        )

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

    /**
     * 排除硬解：黑屏二段回退用。若过滤后为空则回退原列表，避免完全无法播。
     */
    @OptIn(UnstableApi::class)
    fun softwareOnlyVideoSelector(): MediaCodecSelector =
        MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
            val infos = MediaCodecUtil.getDecoderInfos(
                mimeType,
                requiresSecureDecoder,
                requiresTunnelingDecoder,
            )
            if (!MimeTypes.isVideo(mimeType)) return@MediaCodecSelector infos
            val soft = infos.filter { softwareRank(it) >= 2 }
            if (soft.isEmpty()) infos else soft.sortedBy { it.name }
        }

    @OptIn(UnstableApi::class)
    fun softwareRank(info: androidx.media3.exoplayer.mediacodec.MediaCodecInfo): Int {
        if (info.softwareOnly) return 3
        val name = info.name.lowercase(Locale.US)
        return when {
            name.startsWith("c2.android.") || name.startsWith("omx.google.") -> 3
            ".sw." in name || name.endsWith(".sw") || name.contains("soft") -> 2
            info.hardwareAccelerated -> 0
            else -> 1
        }
    }

    fun deviceSnapshot(context: Context): Map<String, String> {
        val metrics = context.resources.displayMetrics
        return linkedMapOf(
            "model" to Build.MODEL.orEmpty(),
            "brand" to Build.BRAND.orEmpty(),
            "manufacturer" to Build.MANUFACTURER.orEmpty(),
            "device" to Build.DEVICE.orEmpty(),
            "product" to Build.PRODUCT.orEmpty(),
            "hardware" to Build.HARDWARE.orEmpty(),
            "board" to Build.BOARD.orEmpty(),
            "soc" to buildString {
                append(Build.HARDWARE.orEmpty())
                append(' ')
                append(Build.BOARD.orEmpty())
                if (Build.VERSION.SDK_INT >= 31) {
                    append(' ')
                    @Suppress("NewApi")
                    append(Build.SOC_MODEL.orEmpty())
                }
            },
            "abi" to Build.SUPPORTED_ABIS.joinToString(","),
            "sdk" to Build.VERSION.SDK_INT.toString(),
            "release" to Build.VERSION.RELEASE.orEmpty(),
            "screen" to "${metrics.widthPixels}x${metrics.heightPixels}",
            "watchLike" to isWatchLike(context).toString(),
            "fragileSoc" to isFragileSoc().toString(),
            "preferSoftDefault" to shouldPreferSoftwareDecode(context).toString(),
            "memClassMb" to memoryClassMb(context).toString(),
        )
    }

    private fun memoryClassMb(context: Context): Int =
        runCatching {
            (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
                ?.memoryClass
                ?: 0
        }.getOrDefault(0)
}
