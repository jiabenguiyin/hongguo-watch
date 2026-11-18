package com.hongguo.watch.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.provider.Settings
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.recyclerview.widget.GridLayoutManager
import com.hongguo.watch.HongguoWatchApp
import com.hongguo.watch.R
import com.hongguo.watch.data.DiagUploader
import com.hongguo.watch.data.SettingsStore
import com.hongguo.watch.data.model.Episode
import com.hongguo.watch.data.model.RelatedWork
import com.hongguo.watch.data.model.SeriesSource
import com.hongguo.watch.data.model.StreamInfo
import com.hongguo.watch.databinding.ActivityPlayerBinding
import com.hongguo.watch.player.DecodePath
import com.hongguo.watch.player.IjkPlayerEngine
import com.hongguo.watch.player.PlayerDiagLog
import com.hongguo.watch.player.PlayerGestureZones
import com.hongguo.watch.player.PlayerKernel
import com.hongguo.watch.player.VideoAspectHelper
import com.hongguo.watch.player.VideoDataSource
import com.hongguo.watch.player.VideoResizeMode
import com.hongguo.watch.player.WatchDecodePolicy
import com.hongguo.watch.upstream.CodecLabels
import com.hongguo.watch.upstream.RemoteVideo
import com.hongguo.watch.upstream.StreamInfo as UpstreamStreamInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 手表全屏播放：锁定、左亮度/右音量、横滑进度、图标控件；
 * 末集结束按 hongguoTV 模式无缝跨季连播（预取元数据，不预取流）。
 */
class PlayerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private var ijkEngine: IjkPlayerEngine? = null
    private var activeKernel: PlayerKernel = PlayerKernel.MEDIA3
    private var kernelPreference: PlayerKernel = PlayerKernel.AUTO
    private var decodePathPreference: DecodePath = DecodePath.AUTO
    private var softDecodePreferred: Boolean = true
    private var progressJob: Job? = null
    private var playJob: Job? = null
    private var hideControlsJob: Job? = null
    /** 锁定态：小解锁钮自动隐藏。 */
    private var hideUnlockJob: Job? = null
    private var hintJob: Job? = null
    private var seasonJob: Job? = null
    /** 播放中短暂缓冲：延迟显示「加载中」，避免闪烁。 */
    private var loadingShowJob: Job? = null
    private var loadingWanted = false
    private var remoteVideo: RemoteVideo? = null
    private var userSeeking = false
    private var controlsVisible = true
    private var locked = false
    /** 锁定且解锁钮可见（可被点按；隐藏后点空白再唤出）。 */
    private var unlockChipVisible = false
    /** 递增以作废过期的取流结果（快速切集 / 退后台）。 */
    private var playGeneration = 0
    private var playerForeground = false

    private val repo get() = (application as HongguoWatchApp).container.repository
    private val settings get() = (application as HongguoWatchApp).container.settings

    private var seriesId = ""
    private var seriesTitle = ""
    private var cover = ""
    private var source = SeriesSource.APP
    private var episodeIds: List<String> = emptyList()
    private var episodeNumbers: List<Int> = emptyList()
    private var episodeIndex = 0
    private var startPositionMs = 0L
    private var currentQuality = 480
    private var currentSpeed = 1.0f
    /** 画面比例：适应 / 铺满 / 拉伸。 */
    private var resizeMode: VideoResizeMode = VideoResizeMode.FIT
    /** 双指缩放倍率（≥1）。 */
    private var userZoom: Float = VideoAspectHelper.MIN_USER_ZOOM

    /** Media3 视频解码模式：默认 → 软解优先 → 仅软解。 */
    private var videoCodecMode = WatchDecodePolicy.VideoCodecMode.Default
    /** 黑屏回退：仅取 AVC 档（Media3 路径）；IJK 路径默认不永久禁 HEVC。 */
    private var forceAvcOnly = false
    private var videoFrameRendered = false
    private var firstFrameWatchJob: Job? = null
    /** 0=未回退；1=切 IJK 软解；2=Media3 仅软解/AVC；≥2 后上传诊断。 */
    private var blackScreenRecoveryStage = 0
    private var diagUploadJob: Job? = null

    private lateinit var episodeAdapter: EpisodeAdapter
    private lateinit var audioManager: AudioManager
    private lateinit var scaleDetector: ScaleGestureDetector
    private var touchSlop = 0
    private var downX = 0f
    private var downY = 0f
    /** ACTION_DOWN 时相对 playerRoot 内容区（去 padding）的 X，用于左右分区。 */
    private var downContentX = 0f
    private var scrubbing = false
    private var adjustingBrightness = false
    private var adjustingVolume = false
    private var scrubStartPos = 0L
    private var brightnessStart = 0f
    private var volumeStart = 0
    private var gestureConsumedClick = false
    private var scaling = false
    private var nextSeasonWork: RelatedWork? = null
    private var seasonGeneration = 0
    private var seasonPrepared = false
    /** 手表默认竖屏；可切横屏（传感器横屏，允许倒装）。 */
    private var landscapePreferred = false

    private enum class GestureMode { NONE, SEEK, BRIGHTNESS, VOLUME }

    private var gestureMode = GestureMode.NONE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        playerForeground = true
        landscapePreferred = savedInstanceState?.getBoolean(STATE_LANDSCAPE, false) == true
        applyRequestedOrientation(announce = false)
        WatchUi.applyImmersive(this)
        WatchUi.applySafePadding(binding.playerRoot)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        seriesId = intent.getStringExtra(EXTRA_SERIES_ID).orEmpty()
        seriesTitle = intent.getStringExtra(EXTRA_SERIES_TITLE).orEmpty()
        cover = intent.getStringExtra(EXTRA_COVER).orEmpty()
        source = runCatching {
            SeriesSource.valueOf(intent.getStringExtra(EXTRA_SOURCE) ?: SeriesSource.APP.name)
        }.getOrDefault(SeriesSource.APP)
        startPositionMs = intent.getLongExtra(EXTRA_START_POSITION_MS, 0L)

        episodeIds = intent.getStringArrayListExtra(EXTRA_EPISODE_IDS)?.toList().orEmpty()
        episodeNumbers = intent.getIntegerArrayListExtra(EXTRA_EPISODE_NUMBERS)?.toList().orEmpty()

        val initialEpisodeId = intent.getStringExtra(EXTRA_EPISODE_ID)
        val initialNumber = intent.getIntExtra(EXTRA_EPISODE_NUMBER, 1)

        touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        scaleDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    if (locked) return false
                    scaling = true
                    gestureConsumedClick = true
                    gestureMode = GestureMode.NONE
                    scrubbing = false
                    adjustingBrightness = false
                    adjustingVolume = false
                    binding.brightnessMeter.visibility = View.GONE
                    binding.volumeMeter.visibility = View.GONE
                    hideControlsJob?.cancel()
                    return true
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val next = (userZoom * detector.scaleFactor)
                        .coerceIn(VideoAspectHelper.MIN_USER_ZOOM, VideoAspectHelper.MAX_USER_ZOOM)
                    if (abs(next - userZoom) < 0.001f) return true
                    userZoom = next
                    applyVideoTransform()
                    showGestureHint(
                        getString(R.string.zoom_hint, (userZoom * 100f).roundToInt()),
                    )
                    return true
                }

                override fun onScaleEnd(detector: ScaleGestureDetector) {
                    scaling = false
                    if (userZoom < 1.05f) {
                        userZoom = VideoAspectHelper.MIN_USER_ZOOM
                        applyVideoTransform()
                    }
                    scheduleHideControls()
                }
            },
        )
        binding.playerView.useController = false
        // TextureView + 保留最后一帧，减轻 OEM SurfaceView 黑屏
        binding.playerView.setKeepContentOnPlayerReset(true)
        applyVideoTransform()
        applyRoundPlayerChrome()
        bindControls()
        bindSheets()
        bindGestures()

        lifecycleScope.launch {
            currentQuality = settings.maxQualityNow()
            currentSpeed = settings.playbackSpeedNow()
            kernelPreference = settings.playerKernelNow()
            decodePathPreference = settings.decodePathNow()
            resetDecodePolicyForDevice()
            PlayerDiagLog.clearSession()
            PlayerDiagLog.i(
                "kernel=$activeKernel soft=$softDecodePreferred mode=$videoCodecMode " +
                    "avcOnly=$forceAvcOnly fragileSoc=${WatchDecodePolicy.isFragileSoc()} " +
                    "ijkAbi=${IjkPlayerEngine.isAbiSupported()}",
            )
            refreshQualityUi()
            refreshSpeedUi()
            if (episodeIds.isEmpty()) {
                runCatching { repo.detail(seriesId) }
                    .onSuccess { detail ->
                        episodeIds = detail.episodes.map { it.id }
                        episodeNumbers = detail.episodes.map { it.number }
                        seriesTitle = detail.series.title
                        cover = detail.series.cover
                    }
            }
            episodeIndex = episodeIds.indexOf(initialEpisodeId).takeIf { it >= 0 }
                ?: (initialNumber - 1).coerceAtLeast(0)
            if (episodeIds.isEmpty()) {
                Toast.makeText(this@PlayerActivity, R.string.error_no_play_url, Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            refreshEpisodePicker()
            loadCurrent()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) WatchUi.applyImmersive(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_LANDSCAPE, landscapePreferred)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        WatchUi.applyImmersive(this)
        WatchUi.applySafePadding(binding.playerRoot)
        applyRoundPlayerChrome()
        // 横竖切换后重算 IJK / Media3 比例与缩放
        binding.playerRoot.post {
            applyVideoTransform()
            refreshEpisodeGridSpan()
        }
    }

    private fun bindControls() {
        binding.backButton.setOnClickListener { finish() }
        binding.lockButton.setOnClickListener { setLocked(true) }
        binding.unlockButton.setOnClickListener {
            // 点在解锁钮上：真正解锁（空白处只负责显隐）
            setLocked(false)
        }
        binding.aspectButton.setOnClickListener {
            if (locked) return@setOnClickListener
            openAspectSheet()
        }
        binding.rotateButton.setOnClickListener {
            if (locked) return@setOnClickListener
            landscapePreferred = !landscapePreferred
            applyRequestedOrientation(announce = true)
            scheduleHideControls()
        }
        binding.prevButton.setOnClickListener {
            switchEpisode(episodeIndex - 1)
            scheduleHideControls()
        }
        binding.nextButton.setOnClickListener {
            switchEpisode(episodeIndex + 1)
            scheduleHideControls()
        }
        binding.playPauseButton.setOnClickListener {
            if (!hasActiveEngine()) return@setOnClickListener
            if (isActivelyPlaying()) {
                pauseActive()
                saveProgress(force = true)
            } else {
                playActive()
            }
            updatePlayPause()
            scheduleHideControls()
        }
        binding.retryButton.setOnClickListener {
            // 手动重试：允许再走一遍兼容回退
            resetDecodePolicyForDevice()
            loadCurrent()
        }
        binding.qualityButton.setOnClickListener { openQualitySheet() }
        binding.speedButton.setOnClickListener { openSpeedSheet() }
        binding.episodesButton.setOnClickListener { openEpisodesSheet() }
        binding.controlsMidTap.setOnClickListener {
            if (!gestureConsumedClick && !locked) toggleControls()
            gestureConsumedClick = false
        }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val duration = activeDuration().takeIf { it > 0 } ?: return
                binding.timeCurrent.text = formatTime(duration * progress / 1000)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                userSeeking = true
                hideControlsJob?.cancel()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val bar = seekBar ?: return
                val duration = activeDuration().takeIf { it > 0 } ?: 0L
                if (duration > 0) seekActive(duration * bar.progress / 1000)
                userSeeking = false
                saveProgress(force = true)
                scheduleHideControls()
            }
        })
    }

    private fun bindSheets() {
        episodeAdapter = EpisodeAdapter { episode ->
            val index = episodeIds.indexOf(episode.id)
            if (index >= 0) switchEpisode(index)
            closeSheets()
        }
        binding.episodePicker.layoutManager = GridLayoutManager(this, WatchUi.episodeColumns(this))
        binding.episodePicker.adapter = episodeAdapter

        binding.sheetScrim.setOnClickListener { closeSheets() }
        binding.quality480.setOnClickListener { applyQuality(480) }
        binding.quality720.setOnClickListener { applyQuality(720) }
        binding.quality1080.setOnClickListener { applyQuality(1080) }
        binding.speed075.setOnClickListener { applySpeed(0.75f) }
        binding.speed100.setOnClickListener { applySpeed(1.0f) }
        binding.speed125.setOnClickListener { applySpeed(1.25f) }
        binding.speed150.setOnClickListener { applySpeed(1.5f) }
        binding.speed200.setOnClickListener { applySpeed(2.0f) }
        binding.aspectFit.setOnClickListener { applyResizeMode(VideoResizeMode.FIT) }
        binding.aspectFill.setOnClickListener { applyResizeMode(VideoResizeMode.FILL) }
        binding.aspectStretch.setOnClickListener { applyResizeMode(VideoResizeMode.STRETCH) }
        binding.aspectZoomReset.setOnClickListener {
            userZoom = VideoAspectHelper.MIN_USER_ZOOM
            applyVideoTransform()
            showGestureHint(getString(R.string.zoom_reset_done))
            closeSheets()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bindGestures() {
        val listener = View.OnTouchListener { _, event -> handleGesture(event) }
        binding.gestureLayer.setOnTouchListener(listener)
        binding.controlsMidTap.setOnTouchListener(listener)
    }

    /** 圆盘：加大侧栏内收；锁定时小解锁钮保持居中，避开四角。 */
    private fun applyRoundPlayerChrome() {
        val side = WatchUi.sideMeterMarginPx(this)
        (binding.brightnessMeter.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let { lp ->
            lp.marginStart = side
            binding.brightnessMeter.layoutParams = lp
        }
        (binding.volumeMeter.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let { lp ->
            lp.marginEnd = side
            binding.volumeMeter.layoutParams = lp
        }
        val round = WatchUi.useRoundLayout(this)
        val cornerSafe = WatchUi.needsCornerSafeInset(this)
        val sidePad = when {
            round -> WatchUi.edgeInsetPx(this).coerceAtLeast(WatchUi.dp(this, 14f))
            cornerSafe -> WatchUi.dp(this, 12f)
            else -> WatchUi.dp(this, 10f)
        }
        binding.midControls.setPadding(sidePad, 0, sidePad, 0)
        val bottomPad = when {
            round -> WatchUi.dp(this, 12f)
            cornerSafe -> WatchUi.dp(this, 10f)
            else -> WatchUi.dp(this, 8f)
        }
        binding.bottomButtons.setPadding(
            bottomPad,
            binding.bottomButtons.paddingTop,
            bottomPad,
            binding.bottomButtons.paddingBottom,
        )
        val seasonLp = binding.seasonBanner.layoutParams as? android.widget.FrameLayout.LayoutParams
        if (seasonLp != null) {
            val h = when {
                round -> WatchUi.edgeInsetPx(this)
                cornerSafe -> WatchUi.dp(this, 16f)
                else -> WatchUi.dp(this, 14f)
            }
            seasonLp.marginStart = h
            seasonLp.marginEnd = h
            binding.seasonBanner.layoutParams = seasonLp
        }
        // 圆屏 / 大圆角方屏：顶栏左右再内收，返回钮不贴圆弧
        val topPad = when {
            round -> WatchUi.dp(this, 6f)
            cornerSafe -> WatchUi.dp(this, 4f)
            else -> WatchUi.dp(this, 2f)
        }
        binding.topBar.setPadding(
            topPad,
            binding.topBar.paddingTop,
            topPad,
            binding.topBar.paddingBottom,
        )
    }

    private fun setLocked(value: Boolean) {
        locked = value
        if (locked) {
            closeSheets()
            hideControls()
            // 防误触：藏控件、吞手势；画面保持可见；小解锁钮可自动隐藏
            binding.controlsOverlay.visibility = View.GONE
            binding.miniProgress.visibility = View.GONE
            binding.brightnessMeter.visibility = View.GONE
            binding.volumeMeter.visibility = View.GONE
            binding.gestureHint.visibility = View.GONE
            showUnlockChip(autoHide = true)
        } else {
            hideUnlockChip()
            showControls(autoHide = true)
        }
    }

    private fun applyRequestedOrientation(announce: Boolean) {
        requestedOrientation = if (landscapePreferred) {
            // sensorLandscape：允许倒装横屏，手表腕姿更友好
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        binding.rotateButton.contentDescription = getString(
            if (landscapePreferred) R.string.rotate_portrait else R.string.rotate_landscape,
        )
        if (announce) {
            showGestureHint(
                getString(
                    if (landscapePreferred) R.string.rotate_landscape else R.string.rotate_portrait,
                ),
            )
        }
    }

    private fun refreshEpisodeGridSpan() {
        val lm = binding.episodePicker.layoutManager as? GridLayoutManager ?: return
        lm.spanCount = WatchUi.episodeColumns(this)
    }

    /** 锁定态小解锁钮：数秒后或点空白隐藏；再点任意处唤出。非全屏遮罩。 */
    private fun showUnlockChip(autoHide: Boolean) {
        if (!locked) return
        unlockChipVisible = true
        binding.unlockButton.visibility = View.VISIBLE
        if (autoHide) scheduleHideUnlockChip() else hideUnlockJob?.cancel()
    }

    private fun hideUnlockChip() {
        unlockChipVisible = false
        binding.unlockButton.visibility = View.GONE
        hideUnlockJob?.cancel()
        hideUnlockJob = null
    }

    private fun scheduleHideUnlockChip() {
        hideUnlockJob?.cancel()
        hideUnlockJob = lifecycleScope.launch {
            delay(UNLOCK_CHIP_AUTO_HIDE_MS)
            if (locked && unlockChipVisible) {
                hideUnlockChip()
            }
        }
    }

    private fun handleGesture(event: MotionEvent): Boolean {
        if (locked) {
            // 吞掉亮度/音量/进度；点空白：有解锁钮则藏、无则唤出；真正解锁只靠小按钮
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                }
                MotionEvent.ACTION_UP -> {
                    val dx = abs(event.x - downX)
                    val dy = abs(event.y - downY)
                    if (dx <= touchSlop && dy <= touchSlop) {
                        if (unlockChipVisible) {
                            hideUnlockChip()
                        } else {
                            showUnlockChip(autoHide = true)
                        }
                    }
                }
            }
            return true
        }

        val wasScaling = scaling
        scaleDetector.onTouchEvent(event)
        // 双指缩放期间不走亮度/音量/进度，避免误触
        if (scaling || wasScaling || event.pointerCount > 1) {
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                gestureMode = GestureMode.NONE
                scrubbing = false
                adjustingBrightness = false
                adjustingVolume = false
                binding.brightnessMeter.visibility = View.GONE
                binding.volumeMeter.visibility = View.GONE
                if (!scaling) scheduleHideControls()
            }
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downContentX = eventContentX(event)
                scrubbing = false
                adjustingBrightness = false
                adjustingVolume = false
                gestureMode = GestureMode.NONE
                gestureConsumedClick = false
                scrubStartPos = activePosition()
                brightnessStart = currentBrightness()
                volumeStart = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (gestureMode == GestureMode.NONE) {
                    if (abs(dx) > touchSlop * 1.5f || abs(dy) > touchSlop * 1.5f) {
                        gestureMode = classifyGesture(downContentX, dx, dy)
                        gestureConsumedClick = true
                        hideControlsJob?.cancel()
                    }
                }
                when (gestureMode) {
                    GestureMode.SEEK -> {
                        scrubbing = true
                        showControls(autoHide = false)
                        val duration = activeDuration().takeIf { it > 0 } ?: return true
                        val width = playerContentWidth()
                        val delta = ((dx / width) * duration * 0.5f).toLong()
                        val target = (scrubStartPos + delta).coerceIn(0L, duration)
                        binding.seekBar.progress = ((target * 1000) / duration).toInt().coerceIn(0, 1000)
                        binding.timeCurrent.text = formatTime(target)
                        val sign = if (delta >= 0) "+" else "−"
                        showGestureHint("$sign${formatTime(abs(delta))}  ${formatTime(target)}")
                    }
                    GestureMode.BRIGHTNESS -> {
                        adjustingBrightness = true
                        val height = playerContentHeight()
                        val delta = (-dy / height) // 上滑增亮
                        val next = (brightnessStart + delta).coerceIn(0.05f, 1f)
                        applyBrightness(next)
                        binding.brightnessMeter.visibility = View.VISIBLE
                        binding.volumeMeter.visibility = View.GONE
                        binding.brightnessBar.progress = (next * 100).toInt()
                        showGestureHint("${getString(R.string.brightness)} ${(next * 100).toInt()}%")
                    }
                    GestureMode.VOLUME -> {
                        adjustingVolume = true
                        val height = playerContentHeight()
                        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                            .coerceAtLeast(1)
                        val delta = ((-dy / height) * maxVol).toInt()
                        val next = (volumeStart + delta).coerceIn(0, maxVol)
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
                        binding.volumeMeter.visibility = View.VISIBLE
                        binding.brightnessMeter.visibility = View.GONE
                        binding.volumeBar.progress = (next * 100 / maxVol)
                        showGestureHint("${getString(R.string.volume)} ${next * 100 / maxVol}%")
                    }
                    GestureMode.NONE -> Unit
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                when (gestureMode) {
                    GestureMode.SEEK -> {
                        val duration = activeDuration().takeIf { it > 0 }
                        if (duration != null) {
                            val target = duration * binding.seekBar.progress / 1000
                            seekActive(target)
                            saveProgress(force = true)
                        }
                        scrubbing = false
                        scheduleHideControls()
                    }
                    GestureMode.BRIGHTNESS, GestureMode.VOLUME -> {
                        binding.brightnessMeter.visibility = View.GONE
                        binding.volumeMeter.visibility = View.GONE
                        scheduleHideControls()
                    }
                    GestureMode.NONE -> {
                        val dx = abs(event.x - downX)
                        val dy = abs(event.y - downY)
                        if (dx < touchSlop && dy < touchSlop) {
                            if (binding.sheetScrim.visibility == View.VISIBLE) {
                                closeSheets()
                            } else {
                                toggleControls()
                            }
                            gestureConsumedClick = true
                        }
                    }
                }
                gestureMode = GestureMode.NONE
                return true
            }
        }
        return false
    }

    /**
     * 左右亮度/音量分区：坐标与宽度均相对 [playerRoot] **内容区**（去安全边 padding）。
     * 横屏 50%/50%；竖屏仍为左 1/3 / 右 1/3（中间优先进度）。见 [PlayerGestureZones]。
     */
    private fun classifyGesture(startXInContent: Float, dx: Float, dy: Float): GestureMode {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE ||
            landscapePreferred
        return when (
            PlayerGestureZones.classify(
                startXInContent = startXInContent,
                dx = dx,
                dy = dy,
                contentWidth = playerContentWidth(),
                landscape = landscape,
            )
        ) {
            PlayerGestureZones.Mode.BRIGHTNESS -> GestureMode.BRIGHTNESS
            PlayerGestureZones.Mode.VOLUME -> GestureMode.VOLUME
            PlayerGestureZones.Mode.SEEK -> GestureMode.SEEK
        }
    }

    /** 触点相对 playerRoot 内容区左缘的 X（兼容 gestureLayer / controlsMidTap 等不同接收 View）。 */
    private fun eventContentX(event: MotionEvent): Float {
        val loc = IntArray(2)
        binding.playerRoot.getLocationOnScreen(loc)
        return event.rawX - loc[0] - binding.playerRoot.paddingLeft
    }

    private fun playerContentWidth(): Int = PlayerGestureZones.contentWidth(
        binding.playerRoot.width,
        binding.playerRoot.paddingLeft,
        binding.playerRoot.paddingRight,
    )

    private fun playerContentHeight(): Int = PlayerGestureZones.contentHeight(
        binding.playerRoot.height,
        binding.playerRoot.paddingTop,
        binding.playerRoot.paddingBottom,
    )

    private fun currentBrightness(): Float {
        val lp = window.attributes
        return if (lp.screenBrightness in 0f..1f) {
            lp.screenBrightness
        } else {
            runCatching {
                Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
            }.getOrDefault(0.5f)
        }
    }

    private fun applyBrightness(value: Float) {
        val lp = window.attributes
        lp.screenBrightness = value
        window.attributes = lp
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun toggleControls() {
        if (controlsVisible) hideControls() else showControls(autoHide = true)
    }

    private fun showControls(autoHide: Boolean) {
        if (locked) return
        controlsVisible = true
        binding.controlsOverlay.visibility = View.VISIBLE
        binding.miniProgress.visibility = View.GONE
        if (autoHide) scheduleHideControls() else hideControlsJob?.cancel()
    }

    private fun hideControls() {
        if (binding.sheetScrim.visibility == View.VISIBLE) return
        controlsVisible = false
        binding.controlsOverlay.visibility = View.GONE
        binding.miniProgress.visibility = if (locked) View.GONE else View.VISIBLE
        hideControlsJob?.cancel()
        updateSeekUi()
    }

    private fun scheduleHideControls() {
        hideControlsJob?.cancel()
        hideControlsJob = lifecycleScope.launch {
            delay(3_200)
            if (!userSeeking && !scrubbing && !adjustingBrightness && !adjustingVolume &&
                !scaling && isActivelyPlaying() && !locked
            ) {
                hideControls()
            }
        }
    }

    private fun openQualitySheet() {
        if (locked) return
        binding.sheetScrim.visibility = View.VISIBLE
        binding.qualitySheet.visibility = View.VISIBLE
        binding.speedSheet.visibility = View.GONE
        binding.aspectSheet.visibility = View.GONE
        binding.episodesSheet.visibility = View.GONE
        refreshQualityUi()
        hideControlsJob?.cancel()
        showControls(autoHide = false)
    }

    private fun openSpeedSheet() {
        if (locked) return
        binding.sheetScrim.visibility = View.VISIBLE
        binding.speedSheet.visibility = View.VISIBLE
        binding.qualitySheet.visibility = View.GONE
        binding.aspectSheet.visibility = View.GONE
        binding.episodesSheet.visibility = View.GONE
        refreshSpeedUi()
        hideControlsJob?.cancel()
        showControls(autoHide = false)
    }

    private fun openAspectSheet() {
        if (locked) return
        binding.sheetScrim.visibility = View.VISIBLE
        binding.aspectSheet.visibility = View.VISIBLE
        binding.qualitySheet.visibility = View.GONE
        binding.speedSheet.visibility = View.GONE
        binding.episodesSheet.visibility = View.GONE
        refreshAspectUi()
        hideControlsJob?.cancel()
        showControls(autoHide = false)
    }

    private fun openEpisodesSheet() {
        if (locked) return
        refreshEpisodePicker()
        binding.sheetScrim.visibility = View.VISIBLE
        binding.episodesSheet.visibility = View.VISIBLE
        binding.qualitySheet.visibility = View.GONE
        binding.speedSheet.visibility = View.GONE
        binding.aspectSheet.visibility = View.GONE
        hideControlsJob?.cancel()
        showControls(autoHide = false)
        binding.episodePicker.post {
            binding.episodePicker.scrollToPosition(episodeIndex.coerceAtLeast(0))
        }
    }

    private fun closeSheets() {
        binding.sheetScrim.visibility = View.GONE
        binding.qualitySheet.visibility = View.GONE
        binding.speedSheet.visibility = View.GONE
        binding.aspectSheet.visibility = View.GONE
        binding.episodesSheet.visibility = View.GONE
        scheduleHideControls()
    }

    private fun applyResizeMode(mode: VideoResizeMode) {
        resizeMode = mode
        applyVideoTransform()
        refreshAspectUi()
        showGestureHint(
            getString(
                when (mode) {
                    VideoResizeMode.FIT -> R.string.aspect_hint_fit
                    VideoResizeMode.FILL -> R.string.aspect_hint_fill
                    VideoResizeMode.STRETCH -> R.string.aspect_hint_stretch
                },
            ),
        )
        closeSheets()
    }

    private fun refreshAspectUi() {
        binding.aspectFit.isSelected = resizeMode == VideoResizeMode.FIT
        binding.aspectFill.isSelected = resizeMode == VideoResizeMode.FILL
        binding.aspectStretch.isSelected = resizeMode == VideoResizeMode.STRETCH
        binding.aspectButton.contentDescription = getString(
            when (resizeMode) {
                VideoResizeMode.FIT -> R.string.aspect_hint_fit
                VideoResizeMode.FILL -> R.string.aspect_hint_fill
                VideoResizeMode.STRETCH -> R.string.aspect_hint_stretch
            },
        )
    }

    @OptIn(UnstableApi::class)
    private fun applyVideoTransform() {
        val zoom = userZoom.coerceIn(VideoAspectHelper.MIN_USER_ZOOM, VideoAspectHelper.MAX_USER_ZOOM)
        userZoom = zoom
        ijkEngine?.setAspect(resizeMode, zoom)
        binding.playerView.resizeMode = media3ResizeMode(resizeMode)
        val applyPivotAndScale = {
            val w = binding.playerView.width
            val h = binding.playerView.height
            if (w > 0 && h > 0) {
                binding.playerView.pivotX = w / 2f
                binding.playerView.pivotY = h / 2f
            }
            binding.playerView.scaleX = zoom
            binding.playerView.scaleY = zoom
        }
        if (binding.playerView.width > 0) {
            applyPivotAndScale()
        } else {
            binding.playerView.post { applyPivotAndScale() }
        }
    }

    @OptIn(UnstableApi::class)
    private fun media3ResizeMode(mode: VideoResizeMode): Int = when (mode) {
        VideoResizeMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        VideoResizeMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        VideoResizeMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    }

    /**
     * 「加载中」显示控制：
     * - immediate=true：切集 / 起播 / 跨季等强制立刻显示
     * - immediate=false：播放中缓冲防抖，短暂卡顿不闪字
     */
    private fun setLoadingIndicator(visible: Boolean, immediate: Boolean) {
        loadingShowJob?.cancel()
        loadingShowJob = null
        if (!visible) {
            loadingWanted = false
            binding.loadingBox.visibility = View.GONE
            return
        }
        loadingWanted = true
        if (immediate || binding.loadingBox.visibility == View.VISIBLE) {
            binding.loadingBox.visibility = View.VISIBLE
            return
        }
        loadingShowJob = lifecycleScope.launch {
            delay(BUFFERING_INDICATOR_DELAY_MS)
            if (loadingWanted) {
                binding.loadingBox.visibility = View.VISIBLE
            }
        }
    }

    private fun applyQuality(height: Int) {
        lifecycleScope.launch {
            settings.setMaxQuality(height)
            currentQuality = SettingsStore.normalizeQuality(height)
            refreshQualityUi()
            closeSheets()
            startPositionMs = activePosition()
            loadCurrent()
        }
    }

    private fun refreshQualityUi() {
        binding.qualityButton.text = "${currentQuality}p"
        binding.quality480.isSelected = currentQuality == 480
        binding.quality720.isSelected = currentQuality == 720
        binding.quality1080.isSelected = currentQuality == 1080
    }

    private fun refreshSpeedUi() {
        currentSpeed = SettingsStore.normalizeSpeed(currentSpeed)
        binding.speedLabel.text = SettingsStore.formatSpeed(currentSpeed)
        binding.speed075.isSelected = currentSpeed == 0.75f
        binding.speed100.isSelected = currentSpeed == 1.0f
        binding.speed125.isSelected = currentSpeed == 1.25f
        binding.speed150.isSelected = currentSpeed == 1.5f
        binding.speed200.isSelected = currentSpeed == 2.0f
    }

    private fun applySpeed(speed: Float) {
        lifecycleScope.launch {
            settings.setPlaybackSpeed(speed)
            currentSpeed = SettingsStore.normalizeSpeed(speed)
            refreshSpeedUi()
            setActiveSpeed(currentSpeed)
            closeSheets()
            scheduleHideControls()
        }
    }

    private fun refreshEpisodePicker() {
        val episodes = episodeIds.indices.map { i ->
            val number = episodeNumbers.getOrNull(i) ?: (i + 1)
            Episode(
                id = episodeIds[i],
                number = number,
                title = "第 ${number} 集",
                seriesId = seriesId,
                seriesTitle = seriesTitle,
                cover = cover,
            )
        }
        episodeAdapter.submit(episodes, episodeIds.getOrNull(episodeIndex))
        binding.episodesSheetTitle.text =
            getString(R.string.episodes) + "  ${episodeIndex + 1}/${episodeIds.size}"
    }

    private fun switchEpisode(index: Int) {
        if (index !in episodeIds.indices) return
        saveProgress(force = true)
        episodeIndex = index
        startPositionMs = 0L
        seasonPrepared = false
        nextSeasonWork = null
        binding.seasonBanner.visibility = View.GONE
        // 切集重置黑屏回退状态，但保留设备默认软解偏好
        resetDecodePolicyForDevice()
        refreshEpisodePicker()
        loadCurrent()
    }

    private fun resetDecodePolicyForDevice() {
        softDecodePreferred = WatchDecodePolicy.resolveSoftDecode(this, decodePathPreference)
        activeKernel = WatchDecodePolicy.resolveKernel(this, kernelPreference)
        videoCodecMode =
            if (softDecodePreferred) {
                WatchDecodePolicy.VideoCodecMode.SoftwareFirst
            } else {
                WatchDecodePolicy.VideoCodecMode.Default
            }
        // IJK 软解不再永久禁 HEVC；Media3 也不因 Unisoc  alone 永久禁 HEVC
        forceAvcOnly = WatchDecodePolicy.shouldForceAvcOnlyInitially(activeKernel)
        blackScreenRecoveryStage = 0
    }

    private fun loadCurrent() {
        val episodeId = episodeIds.getOrNull(episodeIndex) ?: return
        val episodeNumber = episodeNumbers.getOrNull(episodeIndex) ?: (episodeIndex + 1)
        val requestedSeriesId = seriesId
        val requestedIndex = episodeIndex
        binding.playerTitle.text = "$seriesTitle · 第${episodeNumber}集"
        binding.playerTitle.isSelected = true
        binding.retryButton.visibility = View.GONE
        setLoadingIndicator(visible = true, immediate = true)
        if (!locked) showControls(autoHide = false)

        // 取消上一次取流，避免快速切集时旧结果覆盖当前集
        playJob?.cancel()
        firstFrameWatchJob?.cancel()
        val token = ++playGeneration
        playJob = lifecycleScope.launch {
            if (settings.wifiOnlyNow() && !isWifi()) {
                if (token != playGeneration) return@launch
                Toast.makeText(this@PlayerActivity, R.string.error_wifi_only, Toast.LENGTH_LONG).show()
                binding.retryButton.visibility = View.VISIBLE
                setLoadingIndicator(visible = false, immediate = true)
                return@launch
            }
            try {
                val userMax = settings.maxQualityNow()
                val effectiveMax = WatchDecodePolicy.effectiveMaxQuality(this@PlayerActivity, userMax)
                currentQuality = userMax
                if (token != playGeneration) return@launch
                refreshQualityUi()
                if (effectiveMax < userMax) {
                    PlayerDiagLog.i(
                        "fragileSoc quality clamp user=${userMax}p → effective=${effectiveMax}p",
                    )
                }
                val stream = repo.stream(
                    seriesId = requestedSeriesId,
                    episodeId = episodeId,
                    preferAvc = true,
                    avcOnly = forceAvcOnly,
                    maxHeight = effectiveMax,
                )
                if (token != playGeneration) return@launch
                if (!playerForeground) return@launch
                if (seriesId != requestedSeriesId || episodeIndex != requestedIndex) return@launch
                if (episodeIds.getOrNull(episodeIndex) != episodeId) return@launch
                // avcOnly 会话：拒绝把 HEVC/bytevc1 交给播放器（选流层已硬拦，此处再防）
                if (forceAvcOnly && CodecLabels.isHevc(stream.codec)) {
                    PlayerDiagLog.e(
                        "refuse start: avcOnly=true but codec=${stream.codec} q=${stream.quality}",
                    )
                    throw IllegalStateException(
                        "本机仅支持 AVC，当前源为 ${stream.codec.ifBlank { "HEVC" }}",
                    )
                }
                binding.playerNotice.visibility = if (stream.isPreview) View.VISIBLE else View.GONE
                PlayerDiagLog.markPlayStart(
                    codec = stream.codec.ifBlank { "?" },
                    quality = stream.quality,
                    mode = "kernel=$activeKernel soft=$softDecodePreferred " +
                        "media3=$videoCodecMode avcOnly=$forceAvcOnly",
                )
                PlayerDiagLog.i(
                    "selected codec_id=${stream.codec.ifBlank { "?" }} " +
                        "isAvc=${CodecLabels.isAvc(stream.codec)} isHevc=${CodecLabels.isHevc(stream.codec)}",
                )
                playStream(stream, startPositionMs)
            } catch (e: Exception) {
                if (token != playGeneration) return@launch
                if (e is kotlinx.coroutines.CancellationException) throw e
                PlayerDiagLog.e("fetchStream/play failed: ${e.message}", e)
                Toast.makeText(
                    this@PlayerActivity,
                    e.message ?: getString(R.string.error_no_play_url),
                    Toast.LENGTH_LONG,
                ).show()
                binding.retryButton.visibility = View.VISIBLE
                setLoadingIndicator(visible = false, immediate = true)
                uploadDiagnosticsAsync("fetch_or_start_failed")
            }
        }
    }

    @OptIn(UnstableApi::class)
    private suspend fun playStream(stream: StreamInfo, positionMs: Long) {
        val token = playGeneration
        if (!playerForeground || token != playGeneration) return
        releasePlayer(save = false)
        videoFrameRendered = false
        firstFrameWatchJob?.cancel()
        if (activeKernel == PlayerKernel.IJK) {
            playWithIjk(stream, positionMs, token)
        } else {
            playWithMedia3(stream, positionMs, token)
        }
    }

    private suspend fun playWithIjk(stream: StreamInfo, positionMs: Long, token: Int) {
        binding.playerView.visibility = View.GONE
        binding.playerView.player = null
        binding.ijkTexture.visibility = View.VISIBLE
        val key = stream.decryptionKey
        val remote = if (key != null) {
            withContext(Dispatchers.IO) {
                RemoteVideo(
                    mediaHttp,
                    UpstreamStreamInfo(stream.url, key, stream.quality),
                ).prepare()
            }
        } else {
            null
        }
        if (!playerForeground || token != playGeneration) {
            remote?.close()
            return
        }
        remoteVideo = remote
        val engine = IjkPlayerEngine(
            context = this,
            textureView = binding.ijkTexture,
            softDecode = softDecodePreferred,
            listener = object : IjkPlayerEngine.Listener {
                override fun onPrepared() {
                    if (token != playGeneration) return
                    PlayerDiagLog.markVideoDecoder(
                        if (softDecodePreferred) "ijk-ffmpeg-soft" else "ijk-mediacodec",
                    )
                    setLoadingIndicator(visible = false, immediate = true)
                    updatePlayPause()
                    if (!locked) scheduleHideControls()
                    scheduleFirstFrameWatch(token, stream)
                }

                override fun onFirstFrame() {
                    if (token != playGeneration) return
                    videoFrameRendered = true
                    firstFrameWatchJob?.cancel()
                    PlayerDiagLog.markFirstFrame()
                }

                override fun onBuffering(buffering: Boolean) {
                    if (token != playGeneration) return
                    setLoadingIndicator(visible = buffering, immediate = false)
                }

                override fun onCompletion() {
                    if (token != playGeneration) return
                    saveProgress(force = true)
                    if (episodeIndex < episodeIds.lastIndex) {
                        switchEpisode(episodeIndex + 1)
                    } else {
                        finishSeasonOrStop()
                    }
                }

                override fun onError(what: Int, extra: Int, message: String?) {
                    if (token != playGeneration) return
                    PlayerDiagLog.e(message ?: "ijk error")
                    if (tryDecodeRecovery(stream, activePosition(), reason = "ijk_error")) return
                    binding.retryButton.visibility = View.VISIBLE
                    setLoadingIndicator(visible = false, immediate = true)
                    if (!locked) showControls(autoHide = false)
                    Toast.makeText(
                        this@PlayerActivity,
                        message ?: "播放失败",
                        Toast.LENGTH_LONG,
                    ).show()
                    uploadDiagnosticsAsync("ijk_error")
                }

                override fun onVideoSize(width: Int, height: Int, sarNum: Int, sarDen: Int) {
                    PlayerDiagLog.i(
                        "video size ${width}x$height sar=$sarNum:$sarDen mode=$resizeMode zoom=$userZoom",
                    )
                }
            },
        )
        ijkEngine = engine
        engine.setAspect(resizeMode, userZoom)
        engine.start(stream, remote, positionMs, currentSpeed)
        startProgressLoop()
        updatePlayPause()
        setLoadingIndicator(visible = true, immediate = true)
    }

    @OptIn(UnstableApi::class)
    private suspend fun playWithMedia3(stream: StreamInfo, positionMs: Long, token: Int) {
        binding.ijkTexture.visibility = View.GONE
        binding.playerView.visibility = View.VISIBLE
        applyVideoTransform()
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(1_500, 8_000, 1_000, 1_500)
            .build()
        val renderersFactory = WatchDecodePolicy.renderersFactory(this, videoCodecMode)

        val key = stream.decryptionKey
        val exo = if (key != null) {
            val remote = withContext(Dispatchers.IO) {
                RemoteVideo(
                    mediaHttp,
                    UpstreamStreamInfo(stream.url, key, stream.quality),
                ).prepare()
            }
            if (!playerForeground || token != playGeneration) {
                remote.close()
                return
            }
            remoteVideo = remote
            ExoPlayer.Builder(this, renderersFactory)
                .setMediaSourceFactory(ProgressiveMediaSource.Factory { VideoDataSource(remote) })
                .setLoadControl(loadControl)
                .build()
        } else {
            if (!playerForeground || token != playGeneration) return
            val factory = DefaultHttpDataSource.Factory()
                .setUserAgent(stream.userAgent ?: "HongguoWatch/0.1")
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(8_000)
                .setReadTimeoutMs(12_000)
                .setDefaultRequestProperties(
                    buildMap {
                        if (!stream.referer.isNullOrBlank()) put("Referer", stream.referer)
                    },
                )
            ExoPlayer.Builder(this, renderersFactory)
                .setMediaSourceFactory(DefaultMediaSourceFactory(factory))
                .setLoadControl(loadControl)
                .build()
        }
        if (!playerForeground || token != playGeneration) {
            exo.release()
            remoteVideo?.close()
            remoteVideo = null
            return
        }

        exo.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                PlayerDiagLog.markVideoDecoder(decoderName)
            }

            override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                PlayerDiagLog.markAudioDecoder(decoderName)
            }
        })

        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                updatePlayPause()
                when (playbackState) {
                    Player.STATE_BUFFERING ->
                        setLoadingIndicator(visible = true, immediate = false)
                    Player.STATE_IDLE -> Unit
                    else -> setLoadingIndicator(visible = false, immediate = true)
                }
                if (playbackState == Player.STATE_READY && !locked) scheduleHideControls()
                if (playbackState == Player.STATE_READY) {
                    scheduleFirstFrameWatch(token, stream)
                }
                if (playbackState == Player.STATE_ENDED) {
                    saveProgress(force = true)
                    if (episodeIndex < episodeIds.lastIndex) {
                        switchEpisode(episodeIndex + 1)
                    } else {
                        finishSeasonOrStop()
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlayPause()
                if (isPlaying) {
                    if (!locked) scheduleHideControls()
                    scheduleFirstFrameWatch(token, stream)
                } else if (exo.playWhenReady.not()) {
                    // 仅用户暂停 / playWhenReady=false 时落盘；缓冲导致的 isPlaying=false 不打断控件栏
                    hideControlsJob?.cancel()
                    saveProgress(force = true)
                }
            }

            override fun onRenderedFirstFrame() {
                videoFrameRendered = true
                firstFrameWatchJob?.cancel()
                PlayerDiagLog.markFirstFrame()
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    applyVideoTransform()
                    PlayerDiagLog.i(
                        "video size ${videoSize.width}x${videoSize.height} " +
                            "pw=${videoSize.pixelWidthHeightRatio} mode=$resizeMode zoom=$userZoom",
                    )
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                PlayerDiagLog.e(
                    "player error code=${error.errorCode} msg=${error.message}",
                    error,
                )
                if (tryDecodeRecovery(stream, exo.currentPosition, reason = "player_error")) {
                    return
                }
                binding.retryButton.visibility = View.VISIBLE
                setLoadingIndicator(visible = false, immediate = true)
                if (!locked) showControls(autoHide = false)
                Toast.makeText(this@PlayerActivity, error.message ?: "播放失败", Toast.LENGTH_LONG).show()
                uploadDiagnosticsAsync("player_error")
            }
        })

        binding.playerView.player = exo
        player = exo
        exo.setMediaItem(MediaItem.fromUri(stream.url))
        exo.prepare()
        if (positionMs > 0) exo.seekTo(positionMs)
        exo.setPlaybackSpeed(currentSpeed)
        exo.playWhenReady = true
        startProgressLoop()
        updatePlayPause()
        setLoadingIndicator(visible = true, immediate = true)
    }

    /**
     * 有声无画：READY 后仍无首帧 → 分段抬升解码策略。
     * 另：视频解码器名为空（仅音频起来）时尽快拒绝并回退。
     * 硬解「成功」但不吐帧时 Media3 不会走 decoder fallback。
     */
    private fun scheduleFirstFrameWatch(token: Int, stream: StreamInfo) {
        if (videoFrameRendered || blackScreenRecoveryStage >= 2) return
        if (firstFrameWatchJob?.isActive == true) return
        val timeout = WatchDecodePolicy.firstFrameTimeoutMs(this)
        firstFrameWatchJob = lifecycleScope.launch {
            // 短窗：READY 后视频解码器仍为空 → 立即恢复（CD12 Max / bytevc1 静默无解码器）
            delay(1_200L)
            if (token != playGeneration || !playerForeground) return@launch
            if (videoFrameRendered) return@launch
            val early = player
            if (early != null &&
                early.playbackState == Player.STATE_READY &&
                PlayerDiagLog.lastVideoDecoder.isBlank()
            ) {
                PlayerDiagLog.w(
                    "video decoder null after prepare; codec=${stream.codec} " +
                        "audio=${PlayerDiagLog.lastAudioDecoder.ifBlank { "-" }} " +
                        "kernel=$activeKernel mode=$videoCodecMode stage=$blackScreenRecoveryStage",
                )
                if (tryDecodeRecovery(stream, early.currentPosition, reason = "null_video_decoder")) {
                    return@launch
                }
            }
            val remaining = (timeout - 1_200L).coerceAtLeast(0L)
            if (remaining > 0L) delay(remaining)
            if (token != playGeneration || !playerForeground) return@launch
            if (videoFrameRendered || blackScreenRecoveryStage >= 2) return@launch
            if (!hasActiveEngine()) return@launch
            val exo = player
            if (exo != null && !exo.playWhenReady && exo.playbackState != Player.STATE_READY) {
                return@launch
            }
            val decoderHint = if (PlayerDiagLog.lastVideoDecoder.isBlank()) {
                " videoDecoder=null"
            } else {
                " videoDecoder=${PlayerDiagLog.lastVideoDecoder}"
            }
            PlayerDiagLog.w(
                "no first frame after ${timeout}ms; codec=${stream.codec} " +
                    "kernel=$activeKernel mode=$videoCodecMode " +
                    "stage=$blackScreenRecoveryStage$decoderHint",
            )
            val reason = if (PlayerDiagLog.lastVideoDecoder.isBlank()) {
                "null_video_decoder"
            } else {
                "first_frame_timeout"
            }
            if (!tryDecodeRecovery(stream, activePosition(), reason = reason)) {
                uploadDiagnosticsAsync(reason)
                binding.retryButton.visibility = View.VISIBLE
                Toast.makeText(
                    this@PlayerActivity,
                    R.string.player_black_diag_hint,
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    /**
     * @return true 表示已发起兼容重试。
     */
    private fun tryDecodeRecovery(
        stream: StreamInfo,
        positionMs: Long,
        reason: String,
    ): Boolean {
        val nextStage = blackScreenRecoveryStage + 1
        if (nextStage > 2) return false
        blackScreenRecoveryStage = nextStage
        when (nextStage) {
            1 -> {
                // 架构差：优先切 IJK 软解，而不是只靠 MediaCodec 重排
                if (IjkPlayerEngine.isAbiSupported()) {
                    activeKernel = PlayerKernel.IJK
                    softDecodePreferred = true
                    forceAvcOnly = false
                } else {
                    videoCodecMode = WatchDecodePolicy.VideoCodecMode.SoftwareFirst
                    forceAvcOnly = CodecLabels.isHevc(stream.codec)
                }
            }
            else -> {
                if (activeKernel == PlayerKernel.IJK) {
                    // IJK 已失败：回 Media3 仅软解 + 偏 AVC（仍非永久禁 HEVC）
                    activeKernel = PlayerKernel.MEDIA3
                    videoCodecMode = WatchDecodePolicy.VideoCodecMode.SoftwareOnly
                    softDecodePreferred = true
                    forceAvcOnly = true
                } else {
                    activeKernel = PlayerKernel.IJK
                    softDecodePreferred = true
                    forceAvcOnly = false
                    if (!IjkPlayerEngine.isAbiSupported()) {
                        videoCodecMode = WatchDecodePolicy.VideoCodecMode.SoftwareOnly
                        forceAvcOnly = true
                        activeKernel = PlayerKernel.MEDIA3
                    }
                }
            }
        }
        PlayerDiagLog.w(
            "recover[$reason] → stage=$blackScreenRecoveryStage kernel=$activeKernel " +
                "soft=$softDecodePreferred media3=$videoCodecMode avcOnly=$forceAvcOnly " +
                "prevCodec=${stream.codec.ifBlank { "?" }}",
        )
        startPositionMs = positionMs.coerceAtLeast(0L)
        Toast.makeText(this, R.string.player_retry_compat, Toast.LENGTH_SHORT).show()
        loadCurrent()
        return true
    }

    private fun uploadDiagnosticsAsync(reason: String) {
        if (diagUploadJob?.isActive == true) return
        diagUploadJob = lifecycleScope.launch {
            val outcome = runCatching {
                val report = PlayerDiagLog.buildReport(this@PlayerActivity, reason)
                withContext(Dispatchers.IO) { DiagUploader.upload(report) }
            }
            outcome.onSuccess { result ->
                PlayerDiagLog.i("diag uploaded via ${result.channel}: ${result.url}")
                if (!isDestroyed) {
                    watchSafeDiagToast(result.url)
                }
            }.onFailure { err ->
                PlayerDiagLog.e("diag upload failed", err)
            }
        }
    }

    private fun watchSafeDiagToast(url: String) {
        Toast.makeText(
            this,
            getString(R.string.diag_uploaded_toast, url.takeLast(48)),
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun updatePlayPause() {
        val playing = isActivelyPlaying()
        binding.playPauseButton.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        binding.playPauseButton.contentDescription =
            getString(if (playing) R.string.cd_pause else R.string.cd_play)
        binding.playPauseButton.isSelected = true
        binding.prevButton.isEnabled = episodeIndex > 0
        binding.nextButton.isEnabled = episodeIndex < episodeIds.lastIndex || nextSeasonWork != null
        binding.prevButton.alpha = if (binding.prevButton.isEnabled) 1f else 0.35f
        binding.nextButton.alpha = if (binding.nextButton.isEnabled) 1f else 0.35f
    }

    private fun hasActiveEngine(): Boolean = player != null || ijkEngine != null

    /**
     * 播放键图标应对齐「用户期望的播/停」，而不是瞬时 [Player.isPlaying]。
     * Media3 缓冲时 isPlaying=false 但 playWhenReady=true → 仍应显示暂停；
     * IJK 在 prepared 后同步 start，用引擎 isPlaying。
     */
    private fun isActivelyPlaying(): Boolean {
        val ijk = ijkEngine
        if (ijk != null) return ijk.isPlaying()
        val exo = player ?: return false
        return exo.playWhenReady && exo.playbackState != Player.STATE_ENDED
    }

    private fun activePosition(): Long =
        ijkEngine?.currentPosition() ?: player?.currentPosition?.coerceAtLeast(0L) ?: 0L

    private fun activeDuration(): Long =
        ijkEngine?.duration() ?: player?.duration?.coerceAtLeast(0L) ?: 0L

    private fun playActive() {
        ijkEngine?.play()
        player?.play()
    }

    private fun pauseActive() {
        ijkEngine?.pause()
        player?.pause()
    }

    private fun seekActive(positionMs: Long) {
        ijkEngine?.seekTo(positionMs)
        player?.seekTo(positionMs)
    }

    private fun setActiveSpeed(speed: Float) {
        ijkEngine?.setSpeed(speed)
        player?.setPlaybackSpeed(speed)
    }

    private fun startProgressLoop() {
        progressJob?.cancel()
        progressJob = lifecycleScope.launch {
            var ticks = 0
            while (isActive) {
                updateSeekUi()
                maybePrepareSeason()
                delay(500)
                ticks++
                // ~8s 写一次历史（500ms×16）；暂停/退出另有 force 落盘
                if (ticks % 16 == 0 && isActive) saveProgress(force = false)
            }
        }
    }

    /** 末集过半程：只预取下一季元数据（对照 hongguoTV offerNextSeason）。 */
    private fun maybePrepareSeason() {
        if (!hasActiveEngine()) return
        if (episodeIndex != episodeIds.lastIndex) return
        if (seasonPrepared) return
        val duration = activeDuration().takeIf { it > 0 } ?: return
        if (activePosition() < duration / 2) return
        if (!isActivelyPlaying()) return
        seasonPrepared = true
        val token = ++seasonGeneration
        seasonJob?.cancel()
        seasonJob = lifecycleScope.launch {
            val next = runCatching { repo.resolveNextSeason(seriesId, seriesTitle) }.getOrNull()
            if (token != seasonGeneration) return@launch
            nextSeasonWork = next
            if (next != null) {
                binding.seasonBanner.text =
                    getString(R.string.next_season_banner, next.series.title)
                binding.seasonBanner.visibility = View.VISIBLE
            } else {
                binding.seasonBanner.visibility = View.GONE
            }
            updatePlayPause()
        }
    }

    private fun finishSeasonOrStop() {
        val next = nextSeasonWork
        if (next == null) {
            if (!locked) showControls(autoHide = false)
            return
        }
        val token = seasonGeneration
        binding.loadingText.text = getString(R.string.next_season_loading)
        setLoadingIndicator(visible = true, immediate = true)
        showGestureHint(getString(R.string.next_season_loading))
        seasonJob?.cancel()
        seasonJob = lifecycleScope.launch {
            try {
                val detail = repo.detail(next.series.id)
                if (token != seasonGeneration) return@launch
                seriesId = detail.series.id
                seriesTitle = detail.series.title
                cover = detail.series.cover
                source = detail.series.source
                episodeIds = detail.episodes.map { it.id }
                episodeNumbers = detail.episodes.map { it.number }
                episodeIndex = 0
                startPositionMs = 0L
                nextSeasonWork = null
                seasonPrepared = false
                binding.seasonBanner.visibility = View.GONE
                refreshEpisodePicker()
                loadCurrent()
            } catch (e: Exception) {
                setLoadingIndicator(visible = false, immediate = true)
                binding.loadingText.text = getString(R.string.loading)
                Toast.makeText(
                    this@PlayerActivity,
                    e.message ?: getString(R.string.next_season_failed),
                    Toast.LENGTH_LONG,
                ).show()
                if (!locked) showControls(autoHide = false)
            } finally {
                binding.loadingText.text = getString(R.string.loading)
            }
        }
    }

    private fun updateSeekUi() {
        if (!hasActiveEngine()) return
        if (userSeeking || scrubbing) return
        val duration = activeDuration().takeIf { it > 0 } ?: 0L
        val position = activePosition()
        if (duration > 0) {
            val progress = ((position * 1000) / duration).toInt().coerceIn(0, 1000)
            binding.seekBar.progress = progress
            binding.miniProgress.progress = progress
            val buffered = player?.bufferedPosition?.coerceAtLeast(0L) ?: position
            val secondary = ((buffered * 1000) / duration).toInt().coerceIn(0, 1000)
            binding.seekBar.secondaryProgress = secondary
            binding.miniProgress.secondaryProgress = secondary
            binding.timeTotal.text = formatTime(duration)
        }
        binding.timeCurrent.text = formatTime(position)
        // IJK 无 isPlaying 回调：进度循环顺带校正播放键，避免偶发不同步
        if (ijkEngine != null && controlsVisible) updatePlayPause()
    }

    private fun formatTime(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val m = totalSec / 60
        val s = totalSec % 60
        return "%d:%02d".format(m, s)
    }

    private fun showGestureHint(text: String) {
        binding.gestureHint.text = text
        binding.gestureHint.visibility = View.VISIBLE
        hintJob?.cancel()
        hintJob = lifecycleScope.launch {
            delay(700)
            binding.gestureHint.visibility = View.GONE
        }
    }

    private var lastHistoryWriteMs = 0L

    private fun saveProgress(force: Boolean = false) {
        if (!hasActiveEngine()) return
        val episodeId = episodeIds.getOrNull(episodeIndex) ?: return
        val episodeNumber = episodeNumbers.getOrNull(episodeIndex) ?: (episodeIndex + 1)
        val now = System.currentTimeMillis()
        if (!force && now - lastHistoryWriteMs < 5_000L) return
        lastHistoryWriteMs = now
        val position = activePosition()
        val duration = activeDuration()
        lifecycleScope.launch {
            repo.saveProgress(
                seriesId = seriesId,
                seriesTitle = seriesTitle,
                cover = cover,
                episodeId = episodeId,
                episodeNumber = episodeNumber,
                positionMs = position,
                durationMs = duration,
                source = source,
            )
        }
    }

    private fun isWifi(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun releasePlayer(save: Boolean = true) {
        progressJob?.cancel()
        firstFrameWatchJob?.cancel()
        if (save) saveProgress(force = true)
        binding.playerView.player = null
        player?.release()
        player = null
        val ijk = ijkEngine
        ijkEngine = null
        if (ijk != null) {
            // IJK 引擎拥有并关闭 RemoteVideo
            ijk.release()
            remoteVideo = null
        } else {
            remoteVideo?.close()
            remoteVideo = null
        }
        // 清掉 IJK 矩阵；Media3 缩放由 applyVideoTransform 维持
        binding.ijkTexture.setTransform(android.graphics.Matrix())
        applyVideoTransform()
    }

    override fun onStart() {
        super.onStart()
        playerForeground = true
    }

    override fun onStop() {
        playerForeground = false
        // 作废进行中的取流，避免退后台后仍 prepare/play
        playGeneration++
        playJob?.cancel()
        playJob = null
        pauseActive()
        saveProgress(force = true)
        // 退后台取消未完成跨季
        seasonGeneration++
        seasonJob?.cancel()
        super.onStop()
    }

    override fun onDestroy() {
        playerForeground = false
        playGeneration++
        playJob?.cancel()
        hideControlsJob?.cancel()
        hideUnlockJob?.cancel()
        hintJob?.cancel()
        seasonJob?.cancel()
        loadingShowJob?.cancel()
        releasePlayer()
        super.onDestroy()
    }

    companion object {
        private const val TAG = PlayerDiagLog.TAG
        /** 锁定后小解锁钮自动隐藏（与普通控件栏相近）。 */
        private const val UNLOCK_CHIP_AUTO_HIDE_MS = 3_200L
        /** 播放中缓冲提示防抖：短于此时长的卡顿不闪「加载中」。 */
        private const val BUFFERING_INDICATOR_DELAY_MS = 450L
        private const val STATE_LANDSCAPE = "landscape_preferred"

        const val EXTRA_SERIES_ID = "series_id"
        const val EXTRA_SERIES_TITLE = "series_title"
        const val EXTRA_COVER = "cover"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_EPISODE_ID = "episode_id"
        const val EXTRA_EPISODE_NUMBER = "episode_number"
        const val EXTRA_EPISODE_IDS = "episode_ids"
        const val EXTRA_EPISODE_NUMBERS = "episode_numbers"
        const val EXTRA_START_POSITION_MS = "start_position_ms"

        private val mediaHttp: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}
