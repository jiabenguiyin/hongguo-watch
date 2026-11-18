package com.hongguo.watch.player

import android.content.Context
import android.graphics.SurfaceTexture
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import com.hongguo.watch.BuildConfig
import com.hongguo.watch.data.model.StreamInfo
import com.hongguo.watch.upstream.RemoteVideo
import tv.danmaku.ijk.media.player.IMediaPlayer
import tv.danmaku.ijk.media.player.IjkMediaPlayer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 轻量 IJK 会话：TextureView 出画、软/硬解开关、速度、seek。
 * 画面用 [VideoAspectHelper] 按 [VideoResizeMode] + 用户缩放变换。
 * 不重写主播放器架构；由 PlayerActivity 在手表路径选用。
 */
class IjkPlayerEngine(
    private val context: Context,
    private val textureView: TextureView,
    private val softDecode: Boolean,
    private val listener: Listener,
) {
    interface Listener {
        fun onPrepared()
        fun onFirstFrame()
        fun onBuffering(buffering: Boolean)
        fun onCompletion()
        fun onError(what: Int, extra: Int, message: String?)
        fun onVideoSize(width: Int, height: Int, sarNum: Int, sarDen: Int)
    }

    private var player: IjkMediaPlayer? = null
    private var surface: Surface? = null
    private var remote: RemoteVideo? = null
    private val released = AtomicBoolean(false)
    private var firstFrameNotified = false
    private var videoWidth = 0
    private var videoHeight = 0
    private var sarNum = 1
    private var sarDen = 1
    private var resizeMode: VideoResizeMode = VideoResizeMode.FIT
    private var userZoom: Float = VideoAspectHelper.MIN_USER_ZOOM

    fun start(
        stream: StreamInfo,
        remoteVideo: RemoteVideo?,
        positionMs: Long,
        speed: Float,
    ) {
        releaseInternal(keepRemote = false)
        released.set(false)
        firstFrameNotified = false
        videoWidth = 0
        videoHeight = 0
        sarNum = 1
        sarDen = 1
        remote = remoteVideo
        ensureLibs()
        val ijk = IjkMediaPlayer()
        player = ijk
        applyOptions(ijk, softDecode)
        ijk.setOnPreparedListener {
            if (released.get()) return@setOnPreparedListener
            if (positionMs > 0) ijk.seekTo(positionMs)
            ijk.setSpeed(speed.coerceIn(0.5f, 2.5f))
            // 先 start 再回调：否则 Activity 读 isPlaying 仍为 false，播放键会卡在「播放」态
            ijk.start()
            listener.onPrepared()
        }
        ijk.setOnInfoListener { _, what, _ ->
            when (what) {
                IMediaPlayer.MEDIA_INFO_BUFFERING_START -> listener.onBuffering(true)
                IMediaPlayer.MEDIA_INFO_BUFFERING_END -> listener.onBuffering(false)
                IMediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START -> {
                    if (!firstFrameNotified) {
                        firstFrameNotified = true
                        listener.onFirstFrame()
                    }
                }
            }
            false
        }
        ijk.setOnCompletionListener { listener.onCompletion() }
        ijk.setOnErrorListener { _, what, extra ->
            listener.onError(what, extra, "ijk error what=$what extra=$extra")
            true
        }
        ijk.setOnVideoSizeChangedListener { _, width, height, num, den ->
            if (width <= 0 || height <= 0) return@setOnVideoSizeChangedListener
            videoWidth = width
            videoHeight = height
            sarNum = if (num > 0) num else 1
            sarDen = if (den > 0) den else 1
            applyAspect()
            listener.onVideoSize(width, height, sarNum, sarDen)
        }
        bindSurface(ijk)
        if (remoteVideo != null) {
            ijk.setDataSource(IjkRemoteDataSource(remoteVideo))
        } else {
            val headers = HashMap<String, String>()
            stream.userAgent?.takeIf { it.isNotBlank() }?.let { headers["User-Agent"] = it }
            stream.referer?.takeIf { it.isNotBlank() }?.let { headers["Referer"] = it }
            ijk.setDataSource(context, Uri.parse(stream.url), headers)
        }
        PlayerDiagLog.i(
            "ijk prepare soft=$softDecode codec=${stream.codec.ifBlank { "?" }} q=${stream.quality}",
        )
        ijk.prepareAsync()
    }

    fun setResizeMode(mode: VideoResizeMode) {
        resizeMode = mode
        applyAspect()
    }

    fun setUserZoom(zoom: Float) {
        userZoom = zoom.coerceIn(VideoAspectHelper.MIN_USER_ZOOM, VideoAspectHelper.MAX_USER_ZOOM)
        applyAspect()
    }

    fun setAspect(mode: VideoResizeMode, zoom: Float) {
        resizeMode = mode
        userZoom = zoom.coerceIn(VideoAspectHelper.MIN_USER_ZOOM, VideoAspectHelper.MAX_USER_ZOOM)
        applyAspect()
    }

    /** 旋转 / 布局 / 模式 / 缩放变化后由 Activity 再调。 */
    fun applyAspect() {
        if (released.get()) return
        if (videoWidth <= 0 || videoHeight <= 0) return
        val apply = {
            VideoAspectHelper.applyTransform(
                textureView,
                resizeMode,
                videoWidth,
                videoHeight,
                sarNum,
                sarDen,
                userZoom,
            )
        }
        if (textureView.width > 0 && textureView.height > 0) {
            apply()
        } else {
            textureView.post(apply)
        }
    }

    /** @deprecated 使用 [applyAspect] */
    fun applyAspectFit() = applyAspect()

    fun play() {
        player?.start()
    }

    fun pause() {
        player?.pause()
    }

    fun isPlaying(): Boolean = player?.isPlaying == true

    fun currentPosition(): Long = player?.currentPosition?.coerceAtLeast(0L) ?: 0L

    fun duration(): Long = player?.duration?.coerceAtLeast(0L) ?: 0L

    fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs.coerceAtLeast(0L))
    }

    fun setSpeed(speed: Float) {
        player?.setSpeed(speed.coerceIn(0.5f, 2.5f))
    }

    fun release() {
        releaseInternal(keepRemote = false)
    }

    private fun releaseInternal(keepRemote: Boolean) {
        released.set(true)
        runCatching { player?.stop() }
        runCatching { player?.reset() }
        runCatching { player?.release() }
        player = null
        surface?.release()
        surface = null
        if (!keepRemote) {
            remote?.close()
            remote = null
        }
    }

    private fun bindSurface(ijk: IjkMediaPlayer) {
        val st = textureView.surfaceTexture
        if (st != null) {
            surface = Surface(st)
            ijk.setSurface(surface)
            applyAspect()
            return
        }
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, w: Int, h: Int) {
                if (released.get()) return
                surface?.release()
                surface = Surface(surfaceTexture)
                player?.setSurface(surface)
                applyAspect()
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, w: Int, h: Int) {
                applyAspect()
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                player?.setSurface(null)
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
    }

    companion object {
        private val libsLoaded = AtomicBoolean(false)

        fun ensureLibs() {
            if (libsLoaded.get()) return
            synchronized(libsLoaded) {
                if (libsLoaded.get()) return
                IjkMediaPlayer.loadLibrariesOnce(null)
                IjkMediaPlayer.native_setLogLevel(IjkMediaPlayer.IJK_LOG_WARN)
                libsLoaded.set(true)
            }
        }

        fun applyOptions(player: IjkMediaPlayer, softDecode: Boolean) {
            // 手表路径默认软解：mediacodec=0 → FFmpeg 软解（含标准 HEVC）
            player.setOption(
                IjkMediaPlayer.OPT_CATEGORY_PLAYER,
                "mediacodec",
                if (softDecode) 0L else 1L,
            )
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate", 0)
            player.setOption(
                IjkMediaPlayer.OPT_CATEGORY_PLAYER,
                "mediacodec-handle-resolution-change",
                0,
            )
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "opensles", 0)
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 4)
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 0)
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "soundtouch", 1)
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 1)
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max-buffer-size", 6 * 1024 * 1024)
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max_cached_duration", 6_000)
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "min-frames", 5)
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "skip_loop_filter", 48)
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", 100)
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "dns_cache_clear", 1)
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "fflags", "flush_packets")
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect", 1)
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "timeout", 8_000_000L)
            player.setOption(
                IjkMediaPlayer.OPT_CATEGORY_FORMAT,
                "user_agent",
                "HongguoWatch/${BuildConfig.VERSION_NAME}",
            )
        }

        fun isAbiSupported(): Boolean {
            val abis = android.os.Build.SUPPORTED_ABIS
            return abis.any {
                it.equals("armeabi-v7a", true) || it.equals("arm64-v8a", true)
            }
        }
    }
}
