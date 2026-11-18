package com.hongguo.watch.ui

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.provider.Settings
import android.view.MotionEvent
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
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.recyclerview.widget.GridLayoutManager
import com.hongguo.watch.HongguoWatchApp
import com.hongguo.watch.R
import com.hongguo.watch.data.SettingsStore
import com.hongguo.watch.data.model.Episode
import com.hongguo.watch.data.model.RelatedWork
import com.hongguo.watch.data.model.SeriesSource
import com.hongguo.watch.data.model.StreamInfo
import com.hongguo.watch.databinding.ActivityPlayerBinding
import com.hongguo.watch.player.VideoDataSource
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

/**
 * 手表全屏播放：锁定、左亮度/右音量、横滑进度、图标控件；
 * 末集结束按 hongguoTV 模式无缝跨季连播（预取元数据，不预取流）。
 */
class PlayerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private var progressJob: Job? = null
    private var playJob: Job? = null
    private var hideControlsJob: Job? = null
    private var hintJob: Job? = null
    private var seasonJob: Job? = null
    private var remoteVideo: RemoteVideo? = null
    private var userSeeking = false
    private var controlsVisible = true
    private var locked = false
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

    private lateinit var episodeAdapter: EpisodeAdapter
    private lateinit var audioManager: AudioManager
    private var touchSlop = 0
    private var downX = 0f
    private var downY = 0f
    private var scrubbing = false
    private var adjustingBrightness = false
    private var adjustingVolume = false
    private var scrubStartPos = 0L
    private var brightnessStart = 0f
    private var volumeStart = 0
    private var gestureConsumedClick = false
    private var nextSeasonWork: RelatedWork? = null
    private var seasonGeneration = 0
    private var seasonPrepared = false

    private enum class GestureMode { NONE, SEEK, BRIGHTNESS, VOLUME }

    private var gestureMode = GestureMode.NONE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        playerForeground = true
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
        binding.playerView.useController = false
        // TextureView + 保留最后一帧，减轻 OEM SurfaceView 黑屏
        binding.playerView.setKeepContentOnPlayerReset(true)
        applyRoundPlayerChrome()
        bindControls()
        bindSheets()
        bindGestures()

        lifecycleScope.launch {
            currentQuality = settings.maxQualityNow()
            currentSpeed = settings.playbackSpeedNow()
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

    private fun bindControls() {
        binding.backButton.setOnClickListener { finish() }
        binding.lockButton.setOnClickListener { setLocked(true) }
        binding.unlockButton.setOnClickListener { setLocked(false) }
        // 整块中央提示区都可点解锁，圆屏更好点
        binding.lockOverlay.setOnClickListener { setLocked(false) }
        binding.lockBanner.setOnClickListener { setLocked(false) }
        binding.prevButton.setOnClickListener {
            switchEpisode(episodeIndex - 1)
            scheduleHideControls()
        }
        binding.nextButton.setOnClickListener {
            switchEpisode(episodeIndex + 1)
            scheduleHideControls()
        }
        binding.playPauseButton.setOnClickListener {
            val p = player ?: return@setOnClickListener
            if (p.isPlaying) {
                p.pause()
                saveProgress(force = true)
            } else {
                p.play()
            }
            updatePlayPause()
            scheduleHideControls()
        }
        binding.retryButton.setOnClickListener { loadCurrent() }
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
                val duration = player?.duration?.takeIf { it > 0 } ?: return
                binding.timeCurrent.text = formatTime(duration * progress / 1000)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                userSeeking = true
                hideControlsJob?.cancel()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val p = player
                val bar = seekBar
                if (p != null && bar != null) {
                    val duration = p.duration.takeIf { it > 0 } ?: 0L
                    if (duration > 0) p.seekTo(duration * bar.progress / 1000)
                }
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
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bindGestures() {
        val listener = View.OnTouchListener { _, event -> handleGesture(event) }
        binding.gestureLayer.setOnTouchListener(listener)
        binding.controlsMidTap.setOnTouchListener(listener)
    }

    /** 圆盘：加大侧栏内收；锁定解锁只用屏幕正中大钮，绝不放四角。 */
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
            binding.lockOverlay.visibility = View.VISIBLE
            binding.controlsOverlay.visibility = View.GONE
            binding.miniProgress.visibility = View.GONE
            showGestureHint(getString(R.string.lock_hint))
        } else {
            binding.lockOverlay.visibility = View.GONE
            showControls(autoHide = true)
        }
    }

    private fun handleGesture(event: MotionEvent): Boolean {
        if (locked) {
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                // 锁定时点空白无效；仅解锁钮可操作
            }
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                scrubbing = false
                adjustingBrightness = false
                adjustingVolume = false
                gestureMode = GestureMode.NONE
                gestureConsumedClick = false
                scrubStartPos = player?.currentPosition ?: 0L
                brightnessStart = currentBrightness()
                volumeStart = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (gestureMode == GestureMode.NONE) {
                    if (abs(dx) > touchSlop * 1.5f || abs(dy) > touchSlop * 1.5f) {
                        gestureMode = classifyGesture(downX, dx, dy)
                        gestureConsumedClick = true
                        hideControlsJob?.cancel()
                    }
                }
                when (gestureMode) {
                    GestureMode.SEEK -> {
                        scrubbing = true
                        showControls(autoHide = false)
                        val duration = player?.duration?.takeIf { it > 0 } ?: return true
                        val width = binding.playerRoot.width.coerceAtLeast(1)
                        val delta = ((dx / width) * duration * 0.5f).toLong()
                        val target = (scrubStartPos + delta).coerceIn(0L, duration)
                        binding.seekBar.progress = ((target * 1000) / duration).toInt().coerceIn(0, 1000)
                        binding.timeCurrent.text = formatTime(target)
                        val sign = if (delta >= 0) "+" else "−"
                        showGestureHint("$sign${formatTime(abs(delta))}  ${formatTime(target)}")
                    }
                    GestureMode.BRIGHTNESS -> {
                        adjustingBrightness = true
                        val height = binding.playerRoot.height.coerceAtLeast(1)
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
                        val height = binding.playerRoot.height.coerceAtLeast(1)
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
                        val duration = player?.duration?.takeIf { it > 0 }
                        if (duration != null) {
                            val target = duration * binding.seekBar.progress / 1000
                            player?.seekTo(target)
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

    /** 左 1/3 竖直→亮度，右 1/3 竖直→音量，中间或以横向为主→进度。 */
    private fun classifyGesture(startX: Float, dx: Float, dy: Float): GestureMode {
        val width = binding.playerRoot.width.coerceAtLeast(1)
        val vertical = abs(dy) > abs(dx) * 1.15f
        return when {
            vertical && startX < width / 3f -> GestureMode.BRIGHTNESS
            vertical && startX > width * 2f / 3f -> GestureMode.VOLUME
            abs(dx) > abs(dy) * 1.05f -> GestureMode.SEEK
            vertical && startX < width / 2f -> GestureMode.BRIGHTNESS
            vertical -> GestureMode.VOLUME
            else -> GestureMode.SEEK
        }
    }

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
                player?.isPlaying == true && !locked
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
        binding.episodesSheet.visibility = View.GONE
        refreshSpeedUi()
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
        binding.episodesSheet.visibility = View.GONE
        scheduleHideControls()
    }

    private fun applyQuality(height: Int) {
        lifecycleScope.launch {
            settings.setMaxQuality(height)
            currentQuality = SettingsStore.normalizeQuality(height)
            refreshQualityUi()
            closeSheets()
            startPositionMs = player?.currentPosition ?: 0L
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
            player?.setPlaybackSpeed(currentSpeed)
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
        refreshEpisodePicker()
        loadCurrent()
    }

    private fun loadCurrent() {
        val episodeId = episodeIds.getOrNull(episodeIndex) ?: return
        val episodeNumber = episodeNumbers.getOrNull(episodeIndex) ?: (episodeIndex + 1)
        val requestedSeriesId = seriesId
        val requestedIndex = episodeIndex
        binding.playerTitle.text = "$seriesTitle · 第${episodeNumber}集"
        binding.playerTitle.isSelected = true
        binding.retryButton.visibility = View.GONE
        binding.loadingBox.visibility = View.VISIBLE
        if (!locked) showControls(autoHide = false)

        // 取消上一次取流，避免快速切集时旧结果覆盖当前集
        playJob?.cancel()
        val token = ++playGeneration
        playJob = lifecycleScope.launch {
            if (settings.wifiOnlyNow() && !isWifi()) {
                if (token != playGeneration) return@launch
                Toast.makeText(this@PlayerActivity, R.string.error_wifi_only, Toast.LENGTH_LONG).show()
                binding.retryButton.visibility = View.VISIBLE
                binding.loadingBox.visibility = View.GONE
                return@launch
            }
            try {
                currentQuality = settings.maxQualityNow()
                if (token != playGeneration) return@launch
                refreshQualityUi()
                val stream = repo.stream(requestedSeriesId, episodeId)
                if (token != playGeneration) return@launch
                if (!playerForeground) return@launch
                if (seriesId != requestedSeriesId || episodeIndex != requestedIndex) return@launch
                if (episodeIds.getOrNull(episodeIndex) != episodeId) return@launch
                binding.playerNotice.visibility = if (stream.isPreview) View.VISIBLE else View.GONE
                playStream(stream, startPositionMs)
            } catch (e: Exception) {
                if (token != playGeneration) return@launch
                if (e is kotlinx.coroutines.CancellationException) throw e
                Toast.makeText(
                    this@PlayerActivity,
                    e.message ?: getString(R.string.error_no_play_url),
                    Toast.LENGTH_LONG,
                ).show()
                binding.retryButton.visibility = View.VISIBLE
                binding.loadingBox.visibility = View.GONE
            }
        }
    }

    @OptIn(UnstableApi::class)
    private suspend fun playStream(stream: StreamInfo, positionMs: Long) {
        val token = playGeneration
        if (!playerForeground || token != playGeneration) return
        releasePlayer(save = false)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(1_500, 8_000, 1_000, 1_500)
            .build()
        // 解码失败时回退（部分表端硬解花屏/黑屏仍有声）
        val renderersFactory = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)

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

        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                updatePlayPause()
                binding.loadingBox.visibility =
                    if (playbackState == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                if (playbackState == Player.STATE_READY && !locked) scheduleHideControls()
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
                } else {
                    hideControlsJob?.cancel()
                    saveProgress(force = true)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                binding.retryButton.visibility = View.VISIBLE
                binding.loadingBox.visibility = View.GONE
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
        binding.loadingBox.visibility = View.VISIBLE
    }

    private fun updatePlayPause() {
        val playing = player?.isPlaying == true
        binding.playPauseButton.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        binding.playPauseButton.contentDescription =
            getString(if (playing) R.string.cd_pause else R.string.cd_play)
        binding.playPauseButton.isSelected = true
        binding.prevButton.isEnabled = episodeIndex > 0
        binding.nextButton.isEnabled = episodeIndex < episodeIds.lastIndex || nextSeasonWork != null
        binding.prevButton.alpha = if (binding.prevButton.isEnabled) 1f else 0.35f
        binding.nextButton.alpha = if (binding.nextButton.isEnabled) 1f else 0.35f
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
        val p = player ?: return
        if (episodeIndex != episodeIds.lastIndex) return
        if (seasonPrepared) return
        val duration = p.duration.takeIf { it > 0 } ?: return
        if (p.currentPosition < duration / 2) return
        if (!p.isPlaying) return
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
        binding.loadingBox.visibility = View.VISIBLE
        binding.loadingText.text = getString(R.string.next_season_loading)
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
                binding.loadingBox.visibility = View.GONE
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
        val p = player ?: return
        if (userSeeking || scrubbing) return
        val duration = p.duration.takeIf { it > 0 } ?: 0L
        val position = p.currentPosition.coerceAtLeast(0L)
        if (duration > 0) {
            val progress = ((position * 1000) / duration).toInt().coerceIn(0, 1000)
            binding.seekBar.progress = progress
            binding.miniProgress.progress = progress
            val buffered = p.bufferedPosition.coerceAtLeast(0L)
            val secondary = ((buffered * 1000) / duration).toInt().coerceIn(0, 1000)
            binding.seekBar.secondaryProgress = secondary
            binding.miniProgress.secondaryProgress = secondary
            binding.timeTotal.text = formatTime(duration)
        }
        binding.timeCurrent.text = formatTime(position)
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
        val p = player ?: return
        val episodeId = episodeIds.getOrNull(episodeIndex) ?: return
        val episodeNumber = episodeNumbers.getOrNull(episodeIndex) ?: (episodeIndex + 1)
        val now = System.currentTimeMillis()
        if (!force && now - lastHistoryWriteMs < 5_000L) return
        lastHistoryWriteMs = now
        val position = p.currentPosition.coerceAtLeast(0L)
        val duration = p.duration.takeIf { it > 0 } ?: 0L
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
        if (save) saveProgress(force = true)
        binding.playerView.player = null
        player?.release()
        player = null
        remoteVideo?.close()
        remoteVideo = null
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
        player?.pause()
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
        hintJob?.cancel()
        seasonJob?.cancel()
        releasePlayer()
        super.onDestroy()
    }

    companion object {
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
