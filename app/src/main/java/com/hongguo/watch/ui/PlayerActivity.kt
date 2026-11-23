package com.hongguo.watch.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.recyclerview.widget.GridLayoutManager
import com.hongguo.watch.BuildConfig
import com.hongguo.watch.HongguoWatchApp
import com.hongguo.watch.R
import com.hongguo.watch.data.NoPlayableRenditionException
import com.hongguo.watch.data.PublicPlaybackFallback
import com.hongguo.watch.data.SettingsStore
import com.hongguo.watch.data.model.Episode
import com.hongguo.watch.data.model.RelatedWork
import com.hongguo.watch.data.model.SeriesSource
import com.hongguo.watch.data.model.StreamInfo
import com.hongguo.watch.databinding.ActivityPlayerBinding
import com.hongguo.watch.player.DecodePath
import com.hongguo.watch.player.IjkPlayerEngine
import com.hongguo.watch.player.PlaybackPowerController
import com.hongguo.watch.player.PlayerChromeInsets
import com.hongguo.watch.player.PlayerDiagLog
import com.hongguo.watch.player.PlayerGestureArbiter
import com.hongguo.watch.player.PlayerGestureZones
import com.hongguo.watch.player.PlayerKernel
import com.hongguo.watch.player.PlayerStatusHud
import com.hongguo.watch.player.ScreenOffPlaybackPolicy
import com.hongguo.watch.player.VideoAspectHelper
import com.hongguo.watch.player.VideoDataSource
import com.hongguo.watch.player.VideoResizeMode
import com.hongguo.watch.player.WatchDecodePolicy
import com.hongguo.watch.upstream.CodecLabels
import com.hongguo.watch.upstream.EmptyPlaybackResponseException
import com.hongguo.watch.upstream.MediaRangeFormatException
import com.hongguo.watch.upstream.MediaRangeHttpException
import com.hongguo.watch.upstream.PlaybackSourceException
import com.hongguo.watch.upstream.RemoteVideo
import com.hongguo.watch.upstream.Rendition
import com.hongguo.watch.upstream.StreamFailover
import com.hongguo.watch.upstream.StreamInfo as UpstreamStreamInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import tv.danmaku.ijk.media.player.IMediaPlayer
import java.io.EOFException
import java.io.File
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 手表全屏播放：锁定、左亮度/右音量、横滑进度、图标控件；
 * 末集结束按 hongguoTV 模式无缝跨季连播（预取元数据，不预取流）。
 */
class PlayerActivity : WatchDisplayActivity() {
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
    /** 播放中短暂缓冲：延迟显示指示器，避免闪烁。 */
    private var loadingShowJob: Job? = null
    /** 久卡后切换「即将切线」文案（非 Toast）。 */
    private var loadingProlongedJob: Job? = null
    private var loadingWanted = false
    /** 仅跨季等强制相位；其余由首帧 / 久卡推导。 */
    private var loadingPhaseOverride: PlayerLoadingPolicy.Phase? = null
    private var loadingProlonged = false
    private var remoteVideo: RemoteVideo? = null
    private var userSeeking = false
    private var controlsVisible = true
    private var locked = false
    /** 最近一次 PlayerChromeInsets 算出的安全边（只喂命中目标，不垫整栏）。 */
    private var chromeSafePad: PlayerChromeInsets.Padding? = null
    /** 锁定且解锁钮可见（可被点按；隐藏后点空白再唤出）。 */
    private var unlockChipVisible = false
    /** 递增以作废过期的取流结果（快速切集 / 退后台）。 */
    private var playGeneration = 0
    private var playerForeground = false
    private var episodeWheelEnabled = false
    /** 设置「息屏续听」；默认关。 */
    private var screenOffContinue = ScreenOffPlaybackPolicy.DEFAULT_CONTINUE_ENABLED
    private lateinit var powerController: PlaybackPowerController
    /** 播放器时间 + 电量；锁定时仍显示。 */
    private lateinit var statusHud: PlayerStatusHud

    private val repo get() = (application as HongguoWatchApp).container.repository
    private val settings get() = (application as HongguoWatchApp).container.settings
    private val videoCache get() = (application as HongguoWatchApp).container.videoCache
    /** 当前是否走本地缓存文件（与网络流二选一）。 */
    private var playingFromCache = false

    private var seriesId = ""
    private var seriesTitle = ""
    private var cover = ""
    private var source = SeriesSource.APP
    private var offlineOnly = false
    private var episodeIds: List<String> = emptyList()
    private var episodeNumbers: List<Int> = emptyList()
    private var episodeIndex = 0
    private var startPositionMs = 0L
    private var currentQuality = 480
    private var currentSpeed = 1.0f
    /** 画面比例：适应 / 铺满 / 拉伸。 */
    private var resizeMode: VideoResizeMode = VideoResizeMode.FIT
    private var videoTransform = VideoAspectHelper.Transform()
    private lateinit var media3Content: View

    /** Media3 视频解码模式：默认 → 软解优先 → 仅软解。 */
    private var videoCodecMode = WatchDecodePolicy.VideoCodecMode.Default
    /** 黑屏回退：仅取 AVC 档（Media3 路径）；IJK 路径默认不永久禁 HEVC。 */
    private var forceAvcOnly = false
    private var videoFrameRendered = false
    private var firstFrameWatchJob: Job? = null
    /** 0=未回退；1=切 IJK 软解；2=Media3 仅软解/AVC。 */
    private var blackScreenRecoveryStage = 0
    /** 当前集阶梯缓存（失败切流复用，避免每次重拉）。 */
    private var ladderRenditions: List<Rendition> = emptyList()
    private var ladderEpisodeId: String? = null
    private val failedStreamKeys = linkedSetOf<String>()
    /** 切流 + 解码策略切换合计次数。 */
    private var playbackFailoverAttempts = 0
    private var cdnBackupAttempts = 0
    private var activeStreamKey: String? = null
    private var activeStreamInfo: StreamInfo? = null
    private var stallWatchJob: Job? = null
    private var stallAnchorPositionMs = 0L

    private lateinit var episodeAdapter: EpisodeAdapter
    private lateinit var audioManager: AudioManager
    /** 单击显隐 / 双击播停（内置长按已关；长按加速自管）。 */
    private lateinit var tapDetector: GestureDetector
    private val gestureHandler = Handler(Looper.getMainLooper())
    private var touchSlop = 0
    private var downX = 0f
    private var downY = 0f
    private var touchDownTime: Long? = null
    private var lastEpisodeWheelStepAtMs = 0L
    private var episodeWheelScrollBridge: EpisodeScrollBridge? = null
    private var tapCancelled = false
    private var downContentX = 0f
    private var scrubStartPos = 0L
    private var scrubTarget: Long? = null
    private var brightnessStart = 0f
    private var volumeStart = 0
    private var previousTouchX = 0f
    private var previousTouchY = 0f
    private var pinchSpan = 0f
    /** 待决长按加速（手指基本不动）。 */
    private var longPressArmed = false
    private var nextSeasonWork: RelatedWork? = null
    private var seasonGeneration = 0
    private var seasonPrepared = false
    /** 手表默认竖屏；可切横屏（传感器横屏，允许倒装）。 */
    private var landscapePreferred = false

    private enum class GestureMode { NONE, BOOST, PINCH, PAN, SEEK, BRIGHTNESS, VOLUME }

    private var gestureMode = GestureMode.NONE

    private val longPressRunnable = Runnable {
        if (!longPressArmed || locked || gestureMode != GestureMode.NONE) {
            longPressArmed = false
            return@Runnable
        }
        longPressArmed = false
        startSpeedBoost()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        playerForeground = true
        landscapePreferred = savedInstanceState?.getBoolean(STATE_LANDSCAPE, false) == true
        applyRequestedOrientation(announce = false)
        WatchUi.applyImmersive(this)
        installPlayerChromeInsets()

        powerController = PlaybackPowerController(
            activity = this,
            onAudioFocusLost = {
                // 其他应用抢焦点：暂停并落盘，避免无声耗电
                if (isActivelyPlaying()) {
                    pauseActive()
                    updatePlayPause()
                    saveProgress(force = true)
                }
            },
        )
        statusHud = PlayerStatusHud(
            activity = this,
            strip = binding.playerStatusStrip,
            timeView = binding.statusClock,
            batteryIcon = binding.statusBatteryIcon,
            batteryText = binding.statusBatteryText,
        )
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        seriesId = intent.getStringExtra(EXTRA_SERIES_ID).orEmpty()
        seriesTitle = intent.getStringExtra(EXTRA_SERIES_TITLE).orEmpty()
        cover = intent.getStringExtra(EXTRA_COVER).orEmpty()
        source = runCatching {
            SeriesSource.valueOf(intent.getStringExtra(EXTRA_SOURCE) ?: SeriesSource.APP.name)
        }.getOrDefault(SeriesSource.APP)
        offlineOnly = intent.getBooleanExtra(EXTRA_OFFLINE_ONLY, false)
        episodeIds = intent.getStringArrayListExtra(EXTRA_EPISODE_IDS)?.toList().orEmpty()
        episodeNumbers = intent.getIntegerArrayListExtra(EXTRA_EPISODE_NUMBERS)?.toList().orEmpty()

        val initialEpisodeId = intent.getStringExtra(EXTRA_EPISODE_ID)
        val initialNumber = intent.getIntExtra(EXTRA_EPISODE_NUMBER, 0)
        val initialPositionMs = intent.getLongExtra(EXTRA_START_POSITION_MS, 0L)

        touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        media3Content = binding.playerView.findViewById(androidx.media3.ui.R.id.exo_content_frame)
        binding.playerRoot.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) applyVideoTransform()
        }
        media3Content.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) applyVideoTransform()
        }
        binding.playerView.useController = false
        episodeWheelScrollBridge = EpisodeScrollBridge(binding.episodeWheelScroll, ::onEpisodeWheelStep)
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
            screenOffContinue = settings.screenOffContinueNow()
            powerController.setContinueEnabled(screenOffContinue)
            resetDecodePolicyForDevice()
            PlayerDiagLog.clearSession()
            PlayerDiagLog.i(
                "kernel=$activeKernel soft=$softDecodePreferred mode=$videoCodecMode " +
                    "avcOnly=$forceAvcOnly fragileSoc=${WatchDecodePolicy.isFragileSoc()} " +
                    "ijkAbi=${IjkPlayerEngine.isAbiSupported()} screenOffContinue=$screenOffContinue",
            )
            refreshQualityUi()
            refreshSpeedUi()
            if (offlineOnly) {
                val cachedEpisodes = offlineCacheEpisodes(videoCache.listReady(), seriesId)
                if (cachedEpisodes.isEmpty() || cachedEpisodes.none { it.episodeId == initialEpisodeId }) {
                    Toast.makeText(this@PlayerActivity, R.string.offline_entry_missing, Toast.LENGTH_LONG).show()
                    finish()
                    return@launch
                }
                episodeIds = cachedEpisodes.map { it.episodeId }
                episodeNumbers = cachedEpisodes.map { it.episodeNumber }
                if (seriesTitle.isBlank()) seriesTitle = cachedEpisodes.first().seriesTitle
            } else if (episodeIds.isEmpty()) {
                runCatching { repo.detail(seriesId) }
                    .onSuccess { detail ->
                        episodeIds = detail.episodes.map { it.id }
                        episodeNumbers = detail.episodes.map { it.number }
                        seriesTitle = detail.series.title
                        cover = detail.series.cover
                    }
            }
            val initialEpisode = selectInitialEpisode(
                episodeIds, episodeNumbers, initialEpisodeId, initialNumber, initialPositionMs,
            )
            if (initialEpisode == null) {
                Toast.makeText(this@PlayerActivity, R.string.error_no_play_url, Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            episodeIndex = initialEpisode.index
            startPositionMs = initialEpisode.positionMs
            refreshEpisodePicker()
            loadCurrent()
            bindEpisodeWheel()
        }
    }

    private fun bindEpisodeWheel() {
        val wheel = EpisodeWheel.create(this, ::onEpisodeWheelStep) ?: return
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                try {
                    settings.episodeWheelEnabled.collect { enabled ->
                        episodeWheelEnabled = enabled
                        if (BuildConfig.DEBUG) {
                            Log.d(
                                WHEEL_LOG_TAG,
                                "enabled=$enabled index=$episodeIndex total=${episodeIds.size} focus=${hasWindowFocus()}",
                            )
                        }
                        wheel.setEnabled(enabled)
                        episodeWheelScrollBridge?.setEnabled(enabled)
                    }
                } finally {
                    wheel.setEnabled(false)
                    episodeWheelScrollBridge?.setEnabled(false)
                }
            }
        }
    }

    /**
     * The watch framework sends its proprietary wheel callback to the resumed Activity.
     * Its default implementation scrolls the decor view; consume it as an episode step.
     */
    @androidx.annotation.Keep
    fun onEncoderChage(timeSincePreviousMs: Int, scrollDelta: Int) {
        if (BuildConfig.DEBUG) {
            Log.d(
                WHEEL_LOG_TAG,
                "activity callback interval=$timeSincePreviousMs delta=$scrollDelta enabled=$episodeWheelEnabled",
            )
        }
        if (!episodeWheelEnabled) return
        EpisodeWheel.frameworkScrollStep(scrollDelta)?.let(::onEpisodeWheelStep)
    }

    private fun onEpisodeWheelStep(step: Int) {
        val now = SystemClock.uptimeMillis()
        val blockedBy = when {
            !playerForeground -> "background"
            isFinishing -> "finishing"
            !hasWindowFocus() -> "focus"
            userSeeking -> "seeking"
            touchDownTime != null -> "touch"
            binding.sheetScrim.visibility == View.VISIBLE -> "sheet"
            loadingPhaseOverride == PlayerLoadingPolicy.Phase.NEXT_SEASON -> "next-season"
            now - lastEpisodeWheelStepAtMs < EPISODE_WHEEL_DEBOUNCE_MS -> "debounce"
            else -> null
        }
        if (blockedBy != null) {
            if (BuildConfig.DEBUG) {
                Log.d(WHEEL_LOG_TAG, "step=$step ignored=$blockedBy locked=$locked")
            }
            return
        }
        lastEpisodeWheelStepAtMs = now
        if (BuildConfig.DEBUG) Log.d(WHEEL_LOG_TAG, "step=$step episode=$episodeIndex locked=$locked")
        switchEpisode(episodeIndex + step)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) WatchUi.applyImmersive(this)
        else if (::tapDetector.isInitialized) cancelGesture()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_LANDSCAPE, landscapePreferred)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        WatchUi.applyImmersive(this)
        installPlayerChromeInsets()
        applyRoundPlayerChrome()
        // 横竖切换后重算 IJK / Media3 比例与缩放
        binding.playerRoot.post {
            applyVideoTransform()
            refreshEpisodeGridSpan()
        }
    }

    /** 视频全屏；顶/底 chrome 背景贴物理边，安全边只写到可点控件。 */
    private fun installPlayerChromeInsets() {
        PlayerChromeInsets.install(
            videoRoot = binding.playerRoot,
            chromeOverlay = binding.controlsOverlay,
            topChrome = binding.topChrome,
            bottomBar = binding.bottomBar,
            miniProgress = binding.miniProgress,
            onApplied = { pad ->
                chromeSafePad = pad
                applyRoundPlayerChrome()
            },
        )
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
            endSpeedBoost()
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
            // 手动重试：清空失败档与回退计数，再走一遍
            resetPlaybackRecoveryState()
            loadCurrent()
        }
        binding.qualityButton.setOnClickListener { openQualitySheet() }
        // 离线下载仅在详情页发起，播放器不缓存、不预取
        binding.cacheButton.visibility = View.GONE
        binding.speedButton.setOnClickListener { openSpeedSheet() }
        binding.episodesButton.setOnClickListener { openEpisodesSheet() }
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
            videoTransform = VideoAspectHelper.Transform()
            applyVideoTransform()
            showGestureHint(getString(R.string.zoom_reset_done))
            closeSheets()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bindGestures() {
        tapDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (locked || gestureMode != GestureMode.NONE) return false
                    return binding.gestureLayer.performClick()
                }

                override fun onDoubleTap(e: MotionEvent): Boolean = true

                override fun onDoubleTapEvent(e: MotionEvent): Boolean {
                    if (e.actionMasked == MotionEvent.ACTION_UP && !locked && gestureMode == GestureMode.NONE) {
                        togglePlayPauseGesture()
                    }
                    return true
                }
            },
        ).also { it.setIsLongpressEnabled(false) }
        binding.gestureLayer.setOnClickListener {
            if (locked) {
                if (unlockChipVisible) hideUnlockChip() else showUnlockChip(autoHide = true)
            } else if (binding.sheetScrim.visibility == View.VISIBLE) {
                closeSheets()
            } else {
                toggleControls()
            }
        }
        binding.gestureLayer.setOnTouchListener { _, event -> handleGesture(event) }
    }

    private fun armLongPress() {
        cancelLongPressArm()
        if (locked) return
        longPressArmed = true
        gestureHandler.postDelayed(
            longPressRunnable,
            ViewConfiguration.getLongPressTimeout().toLong(),
        )
    }

    private fun cancelLongPressArm() {
        longPressArmed = false
        gestureHandler.removeCallbacks(longPressRunnable)
    }

    /**
     * 圆盘 / 大圆角：安全边只垫命中目标（状态条、顶栏钮、锁、底栏钮），
     * 不垫 topChrome / bottomBar 整栏——背景与 miniProgress 贴物理边。
     */
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
        val preferred = WatchUi.preferredInsets(this)
        val safe = chromeSafePad
        val sidePad = safe?.let { maxOf(it.left, it.right) } ?: preferred.horizontal
        val topSafe = safe?.top ?: preferred.top
        val bottomSafe = safe?.bottom ?: preferred.vertical
        // 状态条：顶/左右吃系统栏与圆角，背景仍由 topChrome 铺满
        binding.playerStatusStrip.setPadding(
            sidePad,
            topSafe,
            sidePad,
            binding.playerStatusStrip.paddingBottom,
        )
        binding.midControls.setPadding(sidePad, 0, sidePad, 0)
        // 底栏按钮行：左右+底安全边；整栏容器与进度贴真底
        binding.bottomButtons.setPadding(
            sidePad,
            binding.bottomButtons.paddingTop,
            sidePad,
            bottomSafe,
        )
        val available = resources.displayMetrics.widthPixels - sidePad * 2
        // Leave space for all five actions when large UI settings narrow the controls row.
        val compact = available < WatchUi.dp(this, 208f)
        listOf(
            binding.episodesButton to android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            binding.playPauseButton to resources.getDimensionPixelSize(R.dimen.watch_play_h),
            binding.speedButton to android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
        ).forEach { (button, normalWidth) ->
            val params = button.layoutParams as android.widget.LinearLayout.LayoutParams
            val width = if (compact) 0 else normalWidth
            val weight = if (compact) 1f else 0f
            if (params.width != width || params.weight != weight) {
                params.width = width
                params.weight = weight
                button.layoutParams = params
                if (button is android.widget.TextView) {
                    val h = WatchUi.dp(this, if (compact) 2f else 10f)
                    button.setPadding(h, button.paddingTop, h, button.paddingBottom)
                }
            }
        }
        val iconPad = WatchUi.dp(this, if (compact) 5f else 8f)
        val playPad = WatchUi.dp(this, if (compact) 6f else 14f)
        binding.prevButton.setPadding(iconPad, iconPad, iconPad, iconPad)
        binding.nextButton.setPadding(iconPad, iconPad, iconPad, iconPad)
        binding.playPauseButton.setPadding(playPad, playPad, playPad, playPad)
        val timeRow = binding.timeCurrent.parent as? android.view.View
        timeRow?.setPadding(sidePad, timeRow.paddingTop, sidePad, timeRow.paddingBottom)
        binding.seekBar.setPadding(sidePad, binding.seekBar.paddingTop, sidePad, binding.seekBar.paddingBottom)
        binding.playerNotice.setPadding(sidePad, binding.playerNotice.paddingTop, sidePad, binding.playerNotice.paddingBottom)
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
        (binding.gestureHint.layoutParams as FrameLayout.LayoutParams).let { lp ->
            val top = topSafe + WatchUi.dp(this, 4f)
            if (lp.topMargin != top || lp.marginStart != sidePad || lp.marginEnd != sidePad) {
                lp.topMargin = top
                lp.marginStart = sidePad
                lp.marginEnd = sidePad
                binding.gestureHint.layoutParams = lp
            }
        }
        // 顶栏行：左右安全边（顶已由 status strip 承担）
        val topExtra = when {
            round -> WatchUi.dp(this, 6f)
            cornerSafe -> WatchUi.dp(this, 4f)
            else -> WatchUi.dp(this, 2f)
        }
        binding.topBar.setPadding(
            maxOf(sidePad, topExtra),
            binding.topBar.paddingTop,
            maxOf(sidePad, topExtra),
            binding.topBar.paddingBottom,
        )
        // 解锁芯片靠左：与 midControls 左 padding 对齐
        (binding.unlockButton.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let { lp ->
            lp.gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            lp.marginStart = sidePad
            lp.marginEnd = 0
            binding.unlockButton.layoutParams = lp
        }
        // 状态条随 controlsOverlay 显隐，不在此强制常驻
        if (::statusHud.isInitialized) {
            statusHud.setVisible(!locked && controlsVisible)
        }
    }

    private fun setLocked(value: Boolean) {
        cancelGesture()
        locked = value
        if (locked) {
            closeSheets()
            hideControls()
            // 防误触：藏控件、吞手势；画面保持可见；小解锁钮可自动隐藏
            binding.controlsOverlay.visibility = View.GONE
            binding.miniProgress.visibility = View.GONE
            binding.brightnessMeter.visibility = View.GONE
            binding.volumeMeter.visibility = View.GONE
            hideGestureHint()
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
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            touchDownTime = event.downTime
            downX = event.x
            downY = event.y
            downContentX = downX + binding.gestureLayer.left - binding.playerRoot.paddingLeft
            tapCancelled = false
            if (!locked) {
                hideControlsJob?.cancel()
                scrubStartPos = activePosition()
                brightnessStart = currentBrightness()
                volumeStart = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                tapDetector.onTouchEvent(event)
                armLongPress()
            }
            return true
        }

        if (touchDownTime != event.downTime) return true
        if (locked) {
            if (action == MotionEvent.ACTION_POINTER_DOWN ||
                action == MotionEvent.ACTION_MOVE &&
                PlayerGestureArbiter.shouldCancelLongPress(event.x - downX, event.y - downY, touchSlop.toFloat())
            ) {
                tapCancelled = true
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                touchDownTime = null
                if (action == MotionEvent.ACTION_UP && !tapCancelled) binding.gestureLayer.performClick()
            }
            return true
        }

        // A claimed hold owns this stream, including drift and extra fingers, until it ends.
        if (gestureMode == GestureMode.BOOST) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) finishGesture(false)
            return true
        }

        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            if (gestureMode != GestureMode.PINCH) {
                cancelLongPressArm()
                cancelTap()
                clearSwipeFeedback()
                gestureMode = GestureMode.PINCH
                hideControls()
                hideGestureHint()
            }
            updatePinch(event)
            return true
        }

        if (gestureMode == GestureMode.PINCH) {
            when (action) {
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_POINTER_UP -> updatePinch(event)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> finishGesture(false)
            }
            return true
        }

        when (action) {
            MotionEvent.ACTION_MOVE -> handleSwipe(event)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (gestureMode == GestureMode.NONE && !tapCancelled) tapDetector.onTouchEvent(event)
                finishGesture(action == MotionEvent.ACTION_UP)
            }
        }
        return true
    }

    private fun handleSwipe(event: MotionEvent) {
        val dx = event.x - downX
        val dy = event.y - downY
        val slop = touchSlop.toFloat()
        if (gestureMode == GestureMode.NONE) {
            if (PlayerGestureArbiter.shouldCancelLongPress(dx, dy, slop)) {
                cancelLongPressArm()
                cancelTap()
            }
            if (!PlayerGestureArbiter.shouldClaimSwipe(dx, dy, slop)) return
            gestureMode = classifyGesture(downContentX, dx, dy)
            if (gestureMode == GestureMode.PAN) {
                previousTouchX = downX
                previousTouchY = downY
            }
            if (gestureMode != GestureMode.SEEK) {
                hideControls()
                hideGestureHint()
            }
            hideControlsJob?.cancel()
        }

        when (gestureMode) {
            GestureMode.PAN -> {
                val viewport = videoViewport() ?: return
                videoTransform = viewport.moveFocus(
                    videoTransform, 1f, previousTouchX, previousTouchY, event.x, event.y,
                )
                previousTouchX = event.x
                previousTouchY = event.y
                applyVideoTransform()
            }
            GestureMode.SEEK -> {
                val duration = activeDuration().takeIf { it > 0 } ?: return
                val delta = ((dx / playerContentWidth()) * duration * 0.5f).toLong()
                val target = (scrubStartPos + delta).coerceIn(0L, duration)
                scrubTarget = target
                val progress = ((target * 1000) / duration).toInt().coerceIn(0, 1000)
                binding.seekBar.progress = progress
                binding.miniProgress.progress = progress
                binding.timeCurrent.text = formatTime(target)
                val sign = if (delta >= 0) "+" else "−"
                showGestureHint("$sign${formatTime(abs(delta))}  ${formatTime(target)}")
            }
            GestureMode.BRIGHTNESS -> {
                val next = (brightnessStart - dy / playerContentHeight()).coerceIn(0.05f, 1f)
                val percent = (next * 100).roundToInt()
                applyBrightness(next)
                binding.brightnessBar.progress = percent
                binding.brightnessPercent.text = getString(R.string.gesture_value_percent, percent)
                binding.brightnessMeter.visibility = View.VISIBLE
            }
            GestureMode.VOLUME -> {
                val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                val delta = ((-dy / playerContentHeight()) * maxVol).toInt()
                val next = (volumeStart + delta).coerceIn(0, maxVol)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
                val percent = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / maxVol
                binding.volumeBar.progress = percent
                binding.volumePercent.text = getString(R.string.gesture_value_percent, percent)
                binding.volumeMeter.visibility = View.VISIBLE
            }
            else -> Unit
        }
    }

    private fun updatePinch(event: MotionEvent) {
        val lifted = if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) event.actionIndex else -1
        if (event.pointerCount - (if (lifted >= 0) 1 else 0) < 2) {
            pinchSpan = 0f
            return
        }
        val first = if (lifted == 0) 1 else 0
        val second = if (lifted == 0 || lifted == 1) 2 else 1
        val x = (event.getX(first) + event.getX(second)) / 2f
        val y = (event.getY(first) + event.getY(second)) / 2f
        val span = PlayerGestureArbiter.movement(
            event.getX(second) - event.getX(first), event.getY(second) - event.getY(first),
        ).coerceAtLeast(touchSlop.toFloat().coerceAtLeast(1f))
        // Re-anchor when the pointer set changes. The platform detector's minimum span can
        // exceed the width of a watch, so scale from the actual spacing of the fingers.
        if (event.actionMasked == MotionEvent.ACTION_MOVE && pinchSpan > 0f) {
            val viewport = videoViewport()
            if (viewport != null) {
                val oldPercent = (videoTransform.zoom * 100f).roundToInt()
                videoTransform = viewport.moveFocus(videoTransform, span / pinchSpan, previousTouchX, previousTouchY, x, y)
                applyVideoTransform()
                val percent = (videoTransform.zoom * 100f).roundToInt()
                if (percent != oldPercent) showGestureHint(getString(R.string.zoom_hint, percent))
            }
        }
        previousTouchX = x
        previousTouchY = y
        pinchSpan = span
    }

    private fun finishGesture(commitSeek: Boolean) {
        cancelLongPressArm()
        val target = scrubTarget
        endSpeedBoost()
        gestureMode = GestureMode.NONE
        touchDownTime = null
        pinchSpan = 0f
        clearSwipeFeedback()
        if (commitSeek && target != null) {
            seekActive(target)
            saveProgress(force = true)
        }
        if (controlsVisible) scheduleHideControls()
    }

    private fun clearSwipeFeedback() {
        val hadSeek = scrubTarget != null
        scrubTarget = null
        binding.brightnessMeter.visibility = View.GONE
        binding.volumeMeter.visibility = View.GONE
        if (hadSeek) updateSeekUi()
    }

    private fun cancelGesture() {
        if (touchDownTime == null) return
        cancelTap()
        finishGesture(false)
        hideGestureHint()
    }

    private fun cancelTap() {
        if (tapCancelled) return
        tapCancelled = true
        val down = touchDownTime ?: return
        val cancel = MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, downX, downY, 0)
        try {
            tapDetector.onTouchEvent(cancel)
        } finally {
            cancel.recycle()
        }
    }

    /** 左右分区沿全屏手势层的宽度计算。 */
    private fun classifyGesture(startX: Float, dx: Float, dy: Float): GestureMode {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE ||
            landscapePreferred
        return when (
            PlayerGestureZones.classify(
                startXInContent = startX,
                dx = dx,
                dy = dy,
                contentWidth = playerContentWidth(),
                landscape = landscape,
                zoomed = videoTransform.zoom > VideoAspectHelper.MIN_USER_ZOOM,
            )
        ) {
            PlayerGestureZones.Mode.BRIGHTNESS -> GestureMode.BRIGHTNESS
            PlayerGestureZones.Mode.VOLUME -> GestureMode.VOLUME
            PlayerGestureZones.Mode.SEEK -> GestureMode.SEEK
            PlayerGestureZones.Mode.PAN -> GestureMode.PAN
        }
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
        // 亮屏策略交给 PlaybackPowerController（前台在播才 KEEP_SCREEN_ON）
        syncPlaybackPower()
    }

    private fun toggleControls() {
        if (controlsVisible) hideControls() else showControls(autoHide = true)
    }

    private fun showControls(autoHide: Boolean) {
        if (locked) return
        controlsVisible = true
        binding.controlsOverlay.visibility = View.VISIBLE
        binding.miniProgress.visibility = View.GONE
        binding.seasonBanner.visibility = if (nextSeasonWork != null) View.VISIBLE else View.GONE
        if (autoHide) scheduleHideControls() else hideControlsJob?.cancel()
    }

    private fun hideControls() {
        if (binding.sheetScrim.visibility == View.VISIBLE) return
        controlsVisible = false
        binding.controlsOverlay.visibility = View.GONE
        binding.seasonBanner.visibility = View.GONE
        binding.miniProgress.visibility = if (locked) View.GONE else View.VISIBLE
        hideControlsJob?.cancel()
        updateSeekUi()
    }

    private fun scheduleHideControls() {
        hideControlsJob?.cancel()
        hideControlsJob = lifecycleScope.launch {
            delay(3_200)
            if (!userSeeking && touchDownTime == null && isActivelyPlaying() && !locked) {
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
        endSpeedBoost()
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
        videoTransform = videoTransform.copy(x = 0f, y = 0f)
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
        val mode = media3ResizeMode(resizeMode)
        if (binding.playerView.resizeMode != mode) binding.playerView.resizeMode = mode
        videoViewport()?.let { videoTransform = it.constrain(videoTransform) }
        ijkEngine?.setAspect(resizeMode, videoTransform)
        // Keep PlayerView as the clipping viewport so FILL retains its offscreen picture for panning.
        media3Content.scaleX = videoTransform.zoom
        media3Content.scaleY = videoTransform.zoom
        media3Content.translationX = videoTransform.x
        media3Content.translationY = videoTransform.y
    }

    private fun videoViewport(): VideoAspectHelper.Viewport? {
        if (activeKernel == PlayerKernel.IJK) return ijkEngine?.videoViewport(resizeMode)
        val width = binding.playerView.width.toFloat()
        val height = binding.playerView.height.toFloat()
        if (!videoFrameRendered || width <= 0f || height <= 0f || media3Content.width <= 0 || media3Content.height <= 0) {
            return null
        }
        return VideoAspectHelper.Viewport(width, height, media3Content.width.toFloat(), media3Content.height.toFloat())
    }

    @OptIn(UnstableApi::class)
    private fun media3ResizeMode(mode: VideoResizeMode): Int = when (mode) {
        VideoResizeMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        VideoResizeMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        VideoResizeMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    }

    /**
     * 缓冲指示显示控制（单一 loadingBox，避免与 PlayerView 叠圈）：
     * - immediate=true：切集 / 起播 / 跨季等强制立刻显示
     * - immediate=false：播放中缓冲防抖，短暂卡顿不闪字
     * - 文案：起播「加载中…」/ 中途「网络较慢…」/ 久卡「即将切线…」
     */
    private fun setLoadingIndicator(
        visible: Boolean,
        immediate: Boolean,
        phaseOverride: PlayerLoadingPolicy.Phase? = null,
    ) {
        loadingShowJob?.cancel()
        loadingShowJob = null
        if (!visible) {
            loadingWanted = false
            loadingProlonged = false
            loadingPhaseOverride = null
            loadingProlongedJob?.cancel()
            loadingProlongedJob = null
            binding.loadingBox.visibility = View.GONE
            return
        }
        loadingWanted = true
        // null = 按首帧/久卡自动推导；显式相位（如跨季）写入覆盖
        loadingPhaseOverride = phaseOverride
        if (phaseOverride != null) {
            loadingProlonged = false
        }
        applyLoadingText()
        ensureProlongedHintScheduled()
        val alreadyVisible = binding.loadingBox.visibility == View.VISIBLE
        if (immediate || alreadyVisible) {
            binding.loadingBox.visibility = View.VISIBLE
            return
        }
        loadingShowJob = lifecycleScope.launch {
            delay(PlayerLoadingPolicy.INDICATOR_DELAY_MS)
            if (loadingWanted) {
                applyLoadingText()
                binding.loadingBox.visibility = View.VISIBLE
            }
        }
    }

    private fun resolveLoadingPhase(): PlayerLoadingPolicy.Phase {
        loadingPhaseOverride?.let { return it }
        return PlayerLoadingPolicy.phase(
            firstFrameRendered = videoFrameRendered,
            bufferingElapsedMs = if (loadingProlonged) {
                PlayerLoadingPolicy.PROLONGED_HINT_MS
            } else {
                0L
            },
        )
    }

    private fun applyLoadingText() {
        val res = when (resolveLoadingPhase()) {
            PlayerLoadingPolicy.Phase.INITIAL -> R.string.loading
            PlayerLoadingPolicy.Phase.REBUFFER -> R.string.player_buffering
            PlayerLoadingPolicy.Phase.REBUFFER_PROLONGED ->
                R.string.player_buffering_failover_hint
            PlayerLoadingPolicy.Phase.NEXT_SEASON -> R.string.next_season_loading
        }
        binding.loadingText.setText(res)
    }

    /** 首帧后持续缓冲达阈值 → 静默改文案，提示可能自动切线。 */
    private fun ensureProlongedHintScheduled() {
        if (loadingPhaseOverride == PlayerLoadingPolicy.Phase.NEXT_SEASON) return
        if (!videoFrameRendered) return
        if (loadingProlonged) return
        if (loadingProlongedJob?.isActive == true) return
        loadingProlongedJob = lifecycleScope.launch {
            delay(PlayerLoadingPolicy.PROLONGED_HINT_MS)
            if (!loadingWanted || !videoFrameRendered) return@launch
            if (loadingPhaseOverride != null) return@launch
            loadingProlonged = true
            applyLoadingText()
        }
    }

    private fun applyQuality(height: Int) {
        lifecycleScope.launch {
            settings.setMaxQuality(height)
            currentQuality = SettingsStore.normalizeQuality(height)
            // 用户改清晰度：尊重新上限，清空本集失败档（不永久写入更低档）
            resetStreamFailoverSession()
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
        val label = SettingsStore.formatSpeed(currentSpeed)
        binding.speedButton.text = label
        binding.speedLabel.text = label
        binding.speed075.isSelected = currentSpeed == 0.75f
        binding.speed100.isSelected = currentSpeed == 1.0f
        binding.speed125.isSelected = currentSpeed == 1.25f
        binding.speed150.isSelected = currentSpeed == 1.5f
        binding.speed200.isSelected = currentSpeed == 2.0f
    }

    private fun applySpeed(speed: Float) {
        lifecycleScope.launch {
            endSpeedBoost()
            settings.setPlaybackSpeed(speed)
            currentSpeed = SettingsStore.normalizeSpeed(speed)
            refreshSpeedUi()
            setActiveSpeed(currentSpeed)
            closeSheets()
            scheduleHideControls()
        }
    }

    /** 双击画面：播/停；不进入锁定态逻辑。 */
    private fun togglePlayPauseGesture() {
        if (!hasActiveEngine()) return
        endSpeedBoost()
        if (isActivelyPlaying()) {
            pauseActive()
            saveProgress(force = true)
            showGestureHint(getString(R.string.gesture_paused))
        } else {
            playActive()
            showGestureHint(getString(R.string.gesture_playing))
        }
        updatePlayPause()
        scheduleHideControls()
    }

    /** 长按临时 2x；不改用户倍速偏好，松手恢复 [currentSpeed]。 */
    private fun startSpeedBoost() {
        if (locked || !isActivelyPlaying()) return
        cancelTap()
        gestureMode = GestureMode.BOOST
        hideControls()
        setActiveSpeed(LONG_PRESS_BOOST_SPEED)
        binding.speedButton.text = SettingsStore.formatSpeed(LONG_PRESS_BOOST_SPEED)
        showGestureHint(getString(R.string.speed_boost_hint, binding.speedButton.text), autoHide = false)
    }

    private fun endSpeedBoost() {
        if (gestureMode != GestureMode.BOOST) return
        gestureMode = GestureMode.NONE
        setActiveSpeed(currentSpeed)
        refreshSpeedUi()
        hideGestureHint()
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
        // The previous engine can still finish while the next URL is being fetched.
        releasePlayer(save = false)
        syncPlaybackPower()
        seasonGeneration++
        seasonJob?.cancel()
        episodeIndex = index
        startPositionMs = 0L
        seasonPrepared = false
        nextSeasonWork = null
        binding.seasonBanner.visibility = View.GONE
        // 切集重置回退/切流状态，但保留设备默认软解偏好
        resetPlaybackRecoveryState()
        refreshEpisodePicker()
        updatePlayPause()
        loadCurrent()
    }

    private fun resetPlaybackRecoveryState() {
        resetDecodePolicyForDevice()
        resetStreamFailoverSession()
    }

    private fun resetStreamFailoverSession() {
        ladderRenditions = emptyList()
        ladderEpisodeId = null
        failedStreamKeys.clear()
        playbackFailoverAttempts = 0
        cdnBackupAttempts = 0
        activeStreamKey = null
        activeStreamInfo = null
        stallWatchJob?.cancel()
        stallWatchJob = null
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
        binding.playerTitle.text = getString(R.string.playing_now, seriesTitle, episodeNumber)
        binding.playerTitle.isSelected = true
        binding.retryButton.visibility = View.GONE
        setLoadingIndicator(visible = true, immediate = true)
        if (!locked) showControls(autoHide = false)

        // 取消上一次取流，避免快速切集时旧结果覆盖当前集
        playJob?.cancel()
        firstFrameWatchJob?.cancel()
        val token = ++playGeneration
        playJob = lifecycleScope.launch {
            // 优先本地缓存：弱网 / 无网可播；Wi‑Fi 限制不拦缓存命中
            val cachedFile = runCatching {
                videoCache.resolvePlayFile(requestedSeriesId, episodeId)
            }.getOrNull()
            if (cachedFile != null) {
                if (token != playGeneration) return@launch
                PlayerDiagLog.i("play from cache")
                playingFromCache = true
                playCachedFile(cachedFile, startPositionMs, token)
                return@launch
            }

            if (offlineOnly) {
                if (token != playGeneration) return@launch
                PlayerDiagLog.e("offline cache missing series=$requestedSeriesId episode=$episodeId")
                Toast.makeText(this@PlayerActivity, R.string.offline_entry_missing, Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }

            if (settings.wifiOnlyNow() && !isWifi()) {
                if (token != playGeneration) return@launch
                Toast.makeText(this@PlayerActivity, R.string.error_wifi_only, Toast.LENGTH_LONG).show()
                binding.retryButton.visibility = View.VISIBLE
                setLoadingIndicator(visible = false, immediate = true)
                return@launch
            }
            var selectedStreamForRecovery: StreamInfo? = null
            try {
                playingFromCache = false
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
                val selection = if (
                    ladderEpisodeId == episodeId &&
                    ladderRenditions.isNotEmpty()
                ) {
                    repo.selectFromLadder(
                        renditions = ladderRenditions,
                        maxHeight = effectiveMax,
                        preferAvc = true,
                        avcOnly = forceAvcOnly,
                        excludeKeys = failedStreamKeys,
                    )
                } else {
                    repo.streamSelection(
                        seriesId = requestedSeriesId,
                        episodeId = episodeId,
                        preferAvc = true,
                        avcOnly = forceAvcOnly,
                        maxHeight = effectiveMax,
                        excludeKeys = failedStreamKeys,
                    )
                }
                if (token != playGeneration) return@launch
                if (!playerForeground) return@launch
                if (seriesId != requestedSeriesId || episodeIndex != requestedIndex) return@launch
                if (episodeIds.getOrNull(episodeIndex) != episodeId) return@launch
                ladderRenditions = selection.renditions
                ladderEpisodeId = episodeId
                activeStreamKey = selection.selectedKey
                val stream = selection.stream
                selectedStreamForRecovery = stream
                // avcOnly 会话：拒绝把 HEVC/bytevc1 交给播放器（选流层已硬拦，此处再防）
                if (forceAvcOnly && CodecLabels.isHevc(stream.codec)) {
                    PlayerDiagLog.e(
                        "refuse start: avcOnly=true but codec=${stream.codec} q=${stream.quality}",
                    )
                    throw IllegalStateException(
                        "本机仅支持 AVC，当前源为 ${stream.codec.ifBlank { "HEVC" }}",
                    )
                }
                // 下一档为 HEVC 时优先 IJK 软解（手表更安全）
                if (
                    CodecLabels.isHevc(stream.codec) &&
                    IjkPlayerEngine.isAbiSupported() &&
                    playbackFailoverAttempts > 0
                ) {
                    activeKernel = PlayerKernel.IJK
                    softDecodePreferred = true
                }
                when {
                    stream.sourceLabel == PublicPlaybackFallback.SOURCE_LABEL -> {
                        binding.playerNotice.setText(R.string.playback_public_backup_notice)
                        binding.playerNotice.visibility = View.VISIBLE
                        binding.qualityButton.setText(R.string.playback_public_quality)
                    }
                    stream.isPreview -> {
                        binding.playerNotice.setText(R.string.play_preview_notice)
                        binding.playerNotice.visibility = View.VISIBLE
                        refreshQualityUi()
                    }
                    else -> {
                        binding.playerNotice.visibility = View.GONE
                        refreshQualityUi()
                    }
                }
                PlayerDiagLog.markPlayStart(
                    codec = stream.codec.ifBlank { "?" },
                    quality = stream.quality,
                    mode = "kernel=$activeKernel soft=$softDecodePreferred " +
                        "media3=$videoCodecMode avcOnly=$forceAvcOnly " +
                        "failover=$playbackFailoverAttempts",
                )
                PlayerDiagLog.i(
                    "selected codec_id=${stream.codec.ifBlank { "?" }} " +
                        "isAvc=${CodecLabels.isAvc(stream.codec)} isHevc=${CodecLabels.isHevc(stream.codec)}",
                )
                playStream(stream, startPositionMs)
            } catch (e: Exception) {
                if (token != playGeneration) return@launch
                if (e is kotlinx.coroutines.CancellationException) throw e
                val sourceFailure = e as? PlaybackSourceException
                val status = mediaHttpStatus(e)
                PlayerDiagLog.e(
                    "fetchStream/play failed type=${e.javaClass.simpleName} " +
                        "reason=${sourceFailure?.reason} status=${sourceFailure?.upstreamCode ?: status} " +
                        "msg=${e.message?.take(180)}",
                )
                if (e is NoPlayableRenditionException &&
                    e.renditions.isNotEmpty()
                ) {
                    ladderRenditions = e.renditions
                    ladderEpisodeId = episodeId
                }
                // Source lookup failures cannot be fixed by changing decoder; only retry a selected
                // stream or reselect from a verified ladder for this episode.
                val failedLadder =
                    e is NoPlayableRenditionException && e.renditions.isNotEmpty() &&
                        ladderEpisodeId == episodeId && ladderRenditions.isNotEmpty()
                val recoverableStream = selectedStreamForRecovery
                val recoveryTarget = recoverableStream
                    ?: if (failedLadder) StreamInfo(url = "", quality = "", codec = "") else null
                if (recoveryTarget != null &&
                    tryPlaybackFailover(
                        recoveryTarget,
                        startPositionMs,
                        reason = "fetch_or_select_failed",
                        trySameQualityBackup = recoverableStream != null && isMediaReadFailure(e),
                        httpStatus = status,
                    )
                ) {
                    return@launch
                }
                Toast.makeText(
                    this@PlayerActivity,
                    when (e) {
                        is EmptyPlaybackResponseException -> getString(R.string.error_playback_empty)
                        is InterruptedIOException, is SocketException, is UnknownHostException ->
                            getString(R.string.error_network)
                        else -> e.message ?: getString(R.string.error_no_play_url)
                    },
                    Toast.LENGTH_LONG,
                ).show()
                binding.retryButton.visibility = View.VISIBLE
                setLoadingIndicator(visible = false, immediate = true)
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
        stallWatchJob?.cancel()
        activeStreamInfo = stream
        if (activeStreamKey.isNullOrBlank()) {
            activeStreamKey = StreamFailover.key(stream.quality, stream.codec, stream.url)
        }
        if (activeKernel == PlayerKernel.IJK) {
            playWithIjk(stream, positionMs, token)
        } else {
            playWithMedia3(stream, positionMs, token)
        }
    }

    /** 本地解密文件：IJK / Media3 均走 file URI，不再经过 RemoteVideo。 */
    @OptIn(UnstableApi::class)
    private suspend fun playCachedFile(file: File, positionMs: Long, token: Int) {
        if (!playerForeground || token != playGeneration) return
        val local = StreamInfo(
            url = file.toURI().toString(),
            quality = getString(R.string.cache_ready),
            isPreview = false,
            decryptionKey = null,
            codec = "",
        )
        releasePlayer(save = false)
        videoFrameRendered = false
        firstFrameWatchJob?.cancel()
        binding.playerNotice.visibility = View.GONE
        PlayerDiagLog.markPlayStart(
            codec = "cache",
            quality = local.quality,
            mode = "kernel=$activeKernel soft=$softDecodePreferred local=1",
        )
        if (activeKernel == PlayerKernel.IJK) {
            playWithIjk(local, positionMs, token)
        } else {
            playWithMedia3(local, positionMs, token)
        }
        Toast.makeText(this, R.string.playing_from_cache, Toast.LENGTH_SHORT).show()
    }

    private suspend fun playWithIjk(stream: StreamInfo, positionMs: Long, token: Int) {
        binding.playerView.visibility = View.GONE
        binding.playerView.player = null
        binding.ijkTexture.visibility = View.VISIBLE
        val remote = if (stream.decryptionKey != null || stream.isCdnBackup) {
            prepareRemoteVideo(stream)
        } else {
            null
        }
        if (!playerForeground || token != playGeneration) {
            remote?.close()
            return
        }
        remoteVideo = remote
        lateinit var engine: IjkPlayerEngine
        engine = IjkPlayerEngine(
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
                    syncPlaybackPower()
                    if (!locked) scheduleHideControls()
                    scheduleFirstFrameWatch(token, stream)
                }

                override fun onFirstFrame() {
                    if (token != playGeneration) return
                    videoFrameRendered = true
                    firstFrameWatchJob?.cancel()
                    PlayerDiagLog.markFirstFrame()
                    if (loadingWanted) {
                        applyLoadingText()
                        ensureProlongedHintScheduled()
                    }
                }

                override fun onBuffering(buffering: Boolean) {
                    if (token != playGeneration) return
                    setLoadingIndicator(visible = buffering, immediate = false)
                    watchStall(buffering, token, stream)
                }

                override fun onCompletion() {
                    if (ijkEngine !== engine || token != playGeneration) return
                    saveProgress(force = true)
                    if (episodeIndex < episodeIds.lastIndex) {
                        switchEpisode(episodeIndex + 1)
                    } else {
                        finishSeasonOrStop()
                    }
                }

                override fun onError(what: Int, extra: Int, message: String?) {
                    if (ijkEngine !== engine || (token != playGeneration && playJob?.isActive == true)) return
                    PlayerDiagLog.e(message ?: "ijk error")
                    val ioFailure = what == IMediaPlayer.MEDIA_ERROR_IO ||
                        extra == IMediaPlayer.MEDIA_ERROR_IO ||
                        what == IMediaPlayer.MEDIA_ERROR_TIMED_OUT ||
                        extra == IMediaPlayer.MEDIA_ERROR_TIMED_OUT
                    if (tryPlaybackFailover(
                            stream, activePosition(), reason = "ijk_error",
                            trySameQualityBackup = ioFailure && remote != null,
                        )
                    ) return
                    binding.retryButton.visibility = View.VISIBLE
                    setLoadingIndicator(visible = false, immediate = true)
                    if (!locked) showControls(autoHide = false)
                    Toast.makeText(
                        this@PlayerActivity,
                        message ?: "播放失败",
                        Toast.LENGTH_LONG,
                    ).show()
                }

                override fun onVideoSize(width: Int, height: Int, sarNum: Int, sarDen: Int) {
                    PlayerDiagLog.i(
                        "video size ${width}x$height sar=$sarNum:$sarDen mode=$resizeMode zoom=${videoTransform.zoom}",
                    )
                    applyVideoTransform()
                }
            },
        )
        ijkEngine = engine
        engine.setAspect(resizeMode, videoTransform)
        engine.start(stream, remote, positionMs, currentSpeed)
        startProgressLoop()
        updatePlayPause()
        setLoadingIndicator(visible = true, immediate = true)
    }

    private suspend fun prepareRemoteVideo(stream: StreamInfo): RemoteVideo = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            val remote = RemoteVideo(
                mediaHttp,
                UpstreamStreamInfo(stream.url, stream.decryptionKey, stream.quality, stream.isCdnBackup),
            )
            continuation.invokeOnCancellation { remote.close() }
            continuation.resumeWith(runCatching { remote.prepare() }.onFailure { remote.close() })
        }
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

        val localFile = stream.url.startsWith("file:", ignoreCase = true)
        val exo = if (stream.decryptionKey != null || stream.isCdnBackup) {
            val remote = prepareRemoteVideo(stream)
            if (!playerForeground || token != playGeneration) {
                remote.close()
                return
            }
            remoteVideo = remote
            ExoPlayer.Builder(this, renderersFactory)
                .setMediaSourceFactory(ProgressiveMediaSource.Factory { VideoDataSource(remote) })
                .setLoadControl(loadControl)
                .build()
        } else if (localFile) {
            // 本地缓存：DefaultDataSource 可读 file://，勿走仅 HTTP 的 DataSource
            if (!playerForeground || token != playGeneration) return
            ExoPlayer.Builder(this, renderersFactory)
                .setMediaSourceFactory(DefaultMediaSourceFactory(this))
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
                if (player !== exo) return
                updatePlayPause()
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        setLoadingIndicator(visible = true, immediate = false)
                        watchStall(buffering = true, token = token, stream = stream)
                    }
                    Player.STATE_IDLE -> Unit
                    else -> {
                        setLoadingIndicator(visible = false, immediate = true)
                        watchStall(buffering = false, token = token, stream = stream)
                    }
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
                syncPlaybackPower()
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
                applyVideoTransform()
                firstFrameWatchJob?.cancel()
                PlayerDiagLog.markFirstFrame()
                if (loadingWanted) {
                    applyLoadingText()
                    ensureProlongedHintScheduled()
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    applyVideoTransform()
                    PlayerDiagLog.i(
                        "video size ${videoSize.width}x${videoSize.height} " +
                            "pw=${videoSize.pixelWidthHeightRatio} mode=$resizeMode zoom=${videoTransform.zoom}",
                    )
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (player !== exo || (token != playGeneration && playJob?.isActive == true)) return
                val status = mediaHttpStatus(error)
                PlayerDiagLog.e(
                    "player error code=${error.errorCode} status=$status cause=${error.cause?.javaClass?.simpleName}",
                )
                val reason = when {
                    error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
                        error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ||
                        error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
                        "decode_error"
                    error.errorCode >= PlaybackException.ERROR_CODE_IO_UNSPECIFIED &&
                        error.errorCode < PlaybackException.ERROR_CODE_DRM_UNSPECIFIED ->
                        "http_or_io_error"
                    else -> "player_error"
                }
                val tryBackup = reason == "http_or_io_error" && (
                    isMediaReadFailure(error) ||
                        status == null && (
                            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                            )
                    )
                if (tryPlaybackFailover(stream, exo.currentPosition, reason, tryBackup, status)) {
                    return
                }
                binding.retryButton.visibility = View.VISIBLE
                setLoadingIndicator(visible = false, immediate = true)
                if (!locked) showControls(autoHide = false)
                Toast.makeText(this@PlayerActivity, error.message ?: "播放失败", Toast.LENGTH_LONG).show()
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
     * 有声无画：READY 后仍无首帧 → 先切流再抬升解码策略。
     * 另：视频解码器名为空（仅音频起来）时尽快拒绝并回退。
     * 硬解「成功」但不吐帧时 Media3 不会走 decoder fallback。
     */
    private fun scheduleFirstFrameWatch(token: Int, stream: StreamInfo) {
        if (videoFrameRendered || playbackFailoverAttempts >= StreamFailover.MAX_ATTEMPTS) return
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
                        "kernel=$activeKernel mode=$videoCodecMode " +
                        "failover=$playbackFailoverAttempts",
                )
                if (tryPlaybackFailover(stream, early.currentPosition, reason = "null_video_decoder")) {
                    return@launch
                }
            }
            val remaining = (timeout - 1_200L).coerceAtLeast(0L)
            if (remaining > 0L) delay(remaining)
            if (token != playGeneration || !playerForeground) return@launch
            if (videoFrameRendered || playbackFailoverAttempts >= StreamFailover.MAX_ATTEMPTS) {
                return@launch
            }
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
                    "failover=$playbackFailoverAttempts$decoderHint",
            )
            val reason = if (PlayerDiagLog.lastVideoDecoder.isBlank()) {
                "null_video_decoder"
            } else {
                "first_frame_timeout"
            }
            if (!tryPlaybackFailover(stream, activePosition(), reason = reason)) {
                binding.retryButton.visibility = View.VISIBLE
                Toast.makeText(
                    this@PlayerActivity,
                    R.string.player_no_picture,
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    /** 首帧后长时间缓冲且进度不前进 → 视为卡顿超时，自动切流。 */
    private fun watchStall(buffering: Boolean, token: Int, stream: StreamInfo) {
        if (!buffering || !videoFrameRendered || playingFromCache) {
            stallWatchJob?.cancel()
            return
        }
        stallAnchorPositionMs = activePosition()
        stallWatchJob?.cancel()
        stallWatchJob = lifecycleScope.launch {
            delay(StreamFailover.STALL_TIMEOUT_MS)
            if (token != playGeneration || !playerForeground) return@launch
            if (!hasActiveEngine() || !videoFrameRendered) return@launch
            val pos = activePosition()
            if (pos - stallAnchorPositionMs >= 400L) return@launch
            PlayerDiagLog.w(
                "stall timeout ${StreamFailover.STALL_TIMEOUT_MS}ms " +
                    "pos=$pos anchor=$stallAnchorPositionMs codec=${stream.codec}",
            )
            if (!tryPlaybackFailover(
                    stream, pos, reason = "stall_timeout", trySameQualityBackup = remoteVideo != null,
                )
            ) {
                binding.retryButton.visibility = View.VISIBLE
                setLoadingIndicator(visible = false, immediate = true)
                if (!locked) showControls(autoHide = false)
                Toast.makeText(
                    this@PlayerActivity,
                    R.string.player_stall_failed,
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    /**
     * 播放失败自动恢复：先沿阶梯切下一档（偏 AVC / 降档），再切解码策略。
     * 切流时才把当前档记入失败集；解码回退可对同一 URL 换内核重试。
     * @return true 表示已发起重试（勿再 toast 终态错误）。
     */
    private fun tryPlaybackFailover(
        stream: StreamInfo,
        positionMs: Long,
        reason: String,
        trySameQualityBackup: Boolean = false,
        httpStatus: Int? = null,
    ): Boolean {
        val rejection = remoteVideo?.upstreamRejection()?.statusCode ?: httpStatus
        if (isExplicitMediaRejection(rejection)) return false
        if (trySameQualityBackup && tryCdnBackup(stream, positionMs, reason)) return true
        if (playbackFailoverAttempts >= StreamFailover.MAX_ATTEMPTS) {
            PlayerDiagLog.w("failover cap reached ($playbackFailoverAttempts) reason=$reason")
            return false
        }
        val currentKey = activeStreamKey
            ?: StreamFailover.key(stream.quality, stream.codec, stream.url)
        // currentQuality 已与用户设置同步；切流不改写 DataStore
        val userMax = currentQuality
        val effectiveMax = WatchDecodePolicy.effectiveMaxQuality(this, userMax)

        val currentEpisodeId = episodeIds.getOrNull(episodeIndex)
        val hasCurrentLadder = ladderRenditions.isNotEmpty() && ladderEpisodeId == currentEpisodeId
        if (!playingFromCache && hasCurrentLadder &&
            (stream.url.isNotBlank() || reason == "fetch_or_select_failed")
        ) {
            val exclude = if (stream.url.isNotBlank() && currentKey.isNotBlank()) {
                failedStreamKeys + currentKey
            } else {
                failedStreamKeys
            }
            val next = StreamFailover.pick(
                renditions = ladderRenditions,
                maxQuality = effectiveMax,
                excludeKeys = exclude,
                preferAvc = true,
                avcOnly = forceAvcOnly,
            )
            if (next != null) {
                if (stream.url.isNotBlank() && currentKey.isNotBlank()) failedStreamKeys.add(currentKey)
                playbackFailoverAttempts++
                if (StreamFailover.shouldPreferIjkSoft(next) && IjkPlayerEngine.isAbiSupported()) {
                    activeKernel = PlayerKernel.IJK
                    softDecodePreferred = true
                    forceAvcOnly = false
                }
                val label = StreamFailover.describe(next)
                PlayerDiagLog.w(
                    "failover[$reason] → stream $label attempt=$playbackFailoverAttempts " +
                        "kernel=$activeKernel soft=$softDecodePreferred avcOnly=$forceAvcOnly " +
                        "userMax=${userMax}p effective=${effectiveMax}p",
                )
                startPositionMs = positionMs.coerceAtLeast(0L)
                Toast.makeText(
                    this,
                    getString(R.string.player_failover_stream, label),
                    Toast.LENGTH_SHORT,
                ).show()
                loadCurrent()
                return true
            }
        }

        return tryDecodeRecovery(stream, positionMs, reason)
    }

    private fun tryCdnBackup(stream: StreamInfo, positionMs: Long, reason: String): Boolean {
        if (playingFromCache || cdnBackupAttempts >= 2 ||
            ladderEpisodeId != episodeIds.getOrNull(episodeIndex)
        ) return false
        val currentKey = activeStreamKey ?: return false
        val current = ladderRenditions.firstOrNull {
            it.url == stream.url && StreamFailover.key(it) == currentKey
        } ?: return false
        val backup = StreamFailover.pickBackup(current, failedStreamKeys) ?: return false
        // Replace this one rung so selection retains quality and the decoder policy on retry.
        ladderRenditions = ladderRenditions.map { if (it === current) backup else it }
        failedStreamKeys.add(currentKey)
        cdnBackupAttempts++
        PlayerDiagLog.w("cdn backup[$reason] attempt=$cdnBackupAttempts quality=${current.quality}")
        startPositionMs = positionMs.coerceAtLeast(0L)
        loadCurrent()
        return true
    }

    private fun mediaHttpStatus(error: Throwable): Int? =
        generateSequence(error) { it.cause }.take(8).firstNotNullOfOrNull {
            when (it) {
                is HttpDataSource.InvalidResponseCodeException -> it.responseCode
                is MediaRangeHttpException -> it.statusCode
                else -> null
            }
        }

    private fun isMediaReadFailure(error: Throwable): Boolean {
        mediaHttpStatus(error)?.let { code ->
            return code == 200 || code == 204 || code in 300..399 ||
                code == 404 || code == 408 || code == 410 || code == 416 || code in 500..599
        }
        return generateSequence(error) { it.cause }.take(8).any {
            it is MediaRangeFormatException || it is EOFException || it is InterruptedIOException ||
                it is SocketException || it is UnknownHostException
        }
    }

    private fun isExplicitMediaRejection(code: Int?): Boolean = code == 401 || code == 403 || code == 429

    /**
     * @return true 表示已发起兼容重试。
     */
    private fun tryDecodeRecovery(
        stream: StreamInfo,
        positionMs: Long,
        reason: String,
    ): Boolean {
        if (playbackFailoverAttempts >= StreamFailover.MAX_ATTEMPTS) return false
        val nextStage = blackScreenRecoveryStage + 1
        if (nextStage > 2) return false
        playbackFailoverAttempts++
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
                "attempt=$playbackFailoverAttempts prevCodec=${stream.codec.ifBlank { "?" }}",
        )
        startPositionMs = positionMs.coerceAtLeast(0L)
        Toast.makeText(this, R.string.player_retry_compat, Toast.LENGTH_SHORT).show()
        loadCurrent()
        return true
    }

    private fun updatePlayPause() {
        val playing = isActivelyPlaying()
        binding.playPauseButton.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        binding.playPauseButton.contentDescription =
            getString(if (playing) R.string.cd_pause else R.string.cd_play)
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
        syncPlaybackPower()
    }

    private fun pauseActive() {
        ijkEngine?.pause()
        player?.pause()
        syncPlaybackPower()
    }

    /** 同步 KEEP_SCREEN_ON / 息屏续听唤醒锁 / 音频焦点。 */
    private fun syncPlaybackPower() {
        if (!::powerController.isInitialized) return
        powerController.sync(
            foreground = playerForeground,
            playing = isActivelyPlaying(),
        )
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
                binding.seasonBanner.visibility = if (controlsVisible && !locked) View.VISIBLE else View.GONE
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
        setLoadingIndicator(
            visible = true,
            immediate = true,
            phaseOverride = PlayerLoadingPolicy.Phase.NEXT_SEASON,
        )
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
                // loadCurrent 会立刻显示起播「加载中…」
                loadCurrent()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (token != seasonGeneration) return@launch
                setLoadingIndicator(visible = false, immediate = true)
                Toast.makeText(
                    this@PlayerActivity,
                    e.message ?: getString(R.string.next_season_failed),
                    Toast.LENGTH_LONG,
                ).show()
                if (!locked) showControls(autoHide = false)
            }
        }
    }

    private fun updateSeekUi() {
        if (!hasActiveEngine()) return
        if (userSeeking || scrubTarget != null) return
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

    private fun showGestureHint(text: String, autoHide: Boolean = true) {
        if (gestureMode == GestureMode.BOOST && autoHide) return
        hintJob?.cancel()
        binding.gestureHint.text = text
        binding.gestureHint.visibility = View.VISIBLE
        if (!autoHide) return
        hintJob = lifecycleScope.launch {
            delay(700)
            binding.gestureHint.visibility = View.GONE
        }
    }

    private fun hideGestureHint() {
        hintJob?.cancel()
        binding.gestureHint.visibility = View.GONE
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
                episodeCount = episodeIds.size,
                forceCloudSync = force,
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
        cancelGesture()
        progressJob?.cancel()
        firstFrameWatchJob?.cancel()
        stallWatchJob?.cancel()
        if (save) saveProgress(force = true)
        binding.playerView.player = null
        val exo = player
        player = null
        exo?.release()
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
        lifecycleScope.launch {
            screenOffContinue = settings.screenOffContinueNow()
            powerController.setContinueEnabled(screenOffContinue)
            syncPlaybackPower()
        }
        syncPlaybackPower()
    }

    override fun onStop() {
        val continuePlay = ScreenOffPlaybackPolicy.shouldContinueWhenStopping(
            continueEnabled = screenOffContinue,
            isFinishing = isFinishing,
            isActivelyPlaying = isActivelyPlaying(),
        )
        playerForeground = false
        cancelGesture()
        if (continuePlay) {
            // 息屏续听：不停播、不取消取流；清亮屏锁、持有 PARTIAL_WAKE_LOCK
            saveProgress(force = true)
            syncPlaybackPower()
            PlayerDiagLog.i("screen-off continue playing")
        } else {
            // 默认省电：熄屏/退后台暂停；作废进行中的取流
            playGeneration++
            playJob?.cancel()
            playJob = null
            pauseActive()
            saveProgress(force = true)
            // 退后台取消未完成跨季
            seasonGeneration++
            seasonJob?.cancel()
            if (loadingPhaseOverride == PlayerLoadingPolicy.Phase.NEXT_SEASON) {
                setLoadingIndicator(visible = false, immediate = true)
            }
            syncPlaybackPower()
        }
        super.onStop()
    }

    override fun onDestroy() {
        playerForeground = false
        cancelLongPressArm()
        playGeneration++
        playJob?.cancel()
        hideControlsJob?.cancel()
        hideUnlockJob?.cancel()
        hintJob?.cancel()
        seasonJob?.cancel()
        loadingShowJob?.cancel()
        loadingProlongedJob?.cancel()
        if (::powerController.isInitialized) powerController.release()
        releasePlayer()
        super.onDestroy()
    }

    companion object {
        private const val WHEEL_LOG_TAG = "HgwWheelScroll"
        private const val EPISODE_WHEEL_DEBOUNCE_MS = 500L

        private const val TAG = PlayerDiagLog.TAG
        /** 锁定后小解锁钮自动隐藏（与普通控件栏相近）。 */
        private const val UNLOCK_CHIP_AUTO_HIDE_MS = 3_200L
        /** 长按临时加速倍率（松手恢复用户倍速）。 */
        private const val LONG_PRESS_BOOST_SPEED = 2.0f
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
        const val EXTRA_OFFLINE_ONLY = "offline_only"

        private val mediaHttp: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}
