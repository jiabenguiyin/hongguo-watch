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

    /** 脆弱 SoC / 低内存默认清晰度上限（≤480p；短播不偷偷压到 360）。 */
    const val FRAGILE_DEFAULT_MAX_QUALITY = 480

    /** 低内存阈值（MB）；CD12 Max / UWS6152 实测 memClass=192。 */
    const val LOW_MEM_CLASS_MB = 192

    /** 软解久播卡顿后，会话内一次性降一档的目标（480→360）；不改用户设置。 */
    const val SOFT_LAG_STEP_DOWN_QUALITY = 360

    /** 软解连续播放多久后，若再遇久卡才允许会话内降一档（毫秒）。 */
    const val SOFT_LAG_STEP_DOWN_AFTER_MS = 3 * 60_000L

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

    /**
     * 0.4.6：停止因 Unisoc/手表 alone 永久禁 HEVC。
     * 同机其它 IJK+FFmpeg 客户端可播，说明根因是内核差，不是「永远不能碰 HEVC」。
     * 黑屏回退仍可阶段性 avcOnly；选流默认 preferAvc。
     */
    @Suppress("UNUSED_PARAMETER")
    fun shouldForceAvcOnlyInitially(
        kernel: PlayerKernel = PlayerKernel.MEDIA3,
    ): Boolean {
        // 保留参数便于调用点显式声明当前内核；当前策略：初始均不永久禁 HEVC
        return false
    }

    @Deprecated("Use shouldForceAvcOnlyInitially(kernel)")
    @Suppress("UNUSED_PARAMETER", "DEPRECATION")
    fun shouldForceAvcOnlyInitially(context: Context): Boolean =
        shouldForceAvcOnlyInitially(PlayerKernel.MEDIA3)

    fun resolveKernel(context: Context, preference: PlayerKernel): PlayerKernel {
        val resolved = when (preference) {
            PlayerKernel.MEDIA3 -> PlayerKernel.MEDIA3
            PlayerKernel.IJK -> PlayerKernel.IJK
            PlayerKernel.AUTO ->
                if (shouldPreferSoftwareDecode(context)) PlayerKernel.IJK else PlayerKernel.MEDIA3
        }
        if (resolved == PlayerKernel.IJK && !IjkPlayerEngine.isAbiSupported()) {
            return PlayerKernel.MEDIA3
        }
        return resolved
    }

    fun resolveSoftDecode(context: Context, preference: DecodePath): Boolean {
        return when (preference) {
            DecodePath.HARDWARE -> false
            DecodePath.SOFTWARE -> true
            DecodePath.AUTO -> shouldPreferSoftwareDecode(context)
        }
    }

    /**
     * 脆弱 SoC + 低内存：有效清晰度上限 ≤480p（与 0.5.6 一致）。
     * 软解 HEVC 久播卡顿改由会话内降档（→360），短播不强制低压。
     * 其它设备尊重用户设置。选流仍 preferAvc。
     */
    @Suppress("UNUSED_PARAMETER")
    fun effectiveMaxQuality(
        context: Context,
        userMaxQuality: Int,
        softDecode: Boolean = true,
    ): Int =
        effectiveMaxQuality(
            userMaxQuality = userMaxQuality,
            fragileSoc = isFragileSoc(),
            memClassMb = memoryClassMb(context),
        )

    fun effectiveMaxQuality(
        userMaxQuality: Int,
        fragileSoc: Boolean,
        memClassMb: Int,
    ): Int {
        val user = userMaxQuality.coerceIn(240, 1080)
        if (!fragileSoc) return user
        return if (memClassMb in 1..LOW_MEM_CLASS_MB) {
            minOf(user, FRAGILE_DEFAULT_MAX_QUALITY)
        } else {
            user
        }
    }

    /** IJK 软解在脆弱 SoC 上减轻解码/缓冲压力（丢帧；不砍默认清晰度）。 */
    fun preferLightIjkSoftLoad(context: Context, softDecode: Boolean): Boolean =
        softDecode && (isFragileSoc() || shouldPreferSoftwareDecode(context))

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
