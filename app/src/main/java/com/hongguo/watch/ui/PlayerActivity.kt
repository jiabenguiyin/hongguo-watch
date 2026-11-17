package com.hongguo.watch.ui

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.recyclerview.widget.GridLayoutManager
import com.hongguo.watch.HongguoWatchApp
import com.hongguo.watch.R
import com.hongguo.watch.data.SettingsStore
import com.hongguo.watch.data.model.Episode
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
 * 手表小屏播放页：布局/控件密度对照哔哩终端（顶栏、进度条、清晰度/选集浮层、点按显隐），
 * 片源仍走本仓 Media3 + App 族逻辑，不引入 B 站协议。
 */
class PlayerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private var progressJob: Job? = null
    private var hideControlsJob: Job? = null
    private var remoteVideo: RemoteVideo? = null
    private var userSeeking = false
    private var controlsVisible = true

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

    private lateinit var episodeAdapter: EpisodeAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

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

        binding.playerView.useController = false
        bindControls()
        bindSheets()
        bindGestures()

        lifecycleScope.launch {
            currentQuality = settings.maxQualityNow()
            refreshQualityUi()
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

    private fun bindControls() {
        binding.backButton.setOnClickListener { finish() }
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
            if (p.isPlaying) p.pause() else p.play()
            updatePlayPause()
            scheduleHideControls()
        }
        binding.retryButton.setOnClickListener { loadCurrent() }
        binding.qualityButton.setOnClickListener { openQualitySheet() }
        binding.qualityBadge.setOnClickListener { openQualitySheet() }
        binding.episodesButton.setOnClickListener { openEpisodesSheet() }

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
                    if (duration > 0) {
                        p.seekTo(duration * bar.progress / 1000)
                    }
                }
                userSeeking = false
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
        binding.episodePicker.layoutManager = GridLayoutManager(this, 5)
        binding.episodePicker.adapter = episodeAdapter

        binding.sheetScrim.setOnClickListener { closeSheets() }
        binding.quality480.setOnClickListener { applyQuality(480) }
        binding.quality720.setOnClickListener { applyQuality(720) }
        binding.quality1080.setOnClickListener { applyQuality(1080) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bindGestures() {
        val detector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (binding.sheetScrim.visibility == View.VISIBLE) {
                        closeSheets()
                        return true
                    }
                    toggleControls()
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    val p = player ?: return false
                    if (p.isPlaying) p.pause() else p.play()
                    updatePlayPause()
                    showControls(autoHide = true)
                    return true
                }

                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float,
                ): Boolean {
                    if (e1 == null) return false
                    val dx = e2.x - e1.x
                    val dy = e2.y - e1.y
                    if (abs(dx) < abs(dy) || abs(dx) < 80f) return false
                    val p = player ?: return false
                    val delta = if (dx > 0) 10_000L else -10_000L
                    val target = (p.currentPosition + delta).coerceAtLeast(0L)
                    p.seekTo(target)
                    showGestureHint(if (dx > 0) "+10s" else "−10s")
                    showControls(autoHide = true)
                    return true
                }
            },
        )
        binding.gestureLayer.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            true
        }
        binding.controlsMidTap.setOnClickListener { toggleControls() }
    }

    private fun toggleControls() {
        if (controlsVisible) hideControls() else showControls(autoHide = true)
    }

    private fun showControls(autoHide: Boolean) {
        controlsVisible = true
        binding.controlsOverlay.visibility = View.VISIBLE
        if (autoHide) scheduleHideControls() else hideControlsJob?.cancel()
    }

    private fun hideControls() {
        if (binding.sheetScrim.visibility == View.VISIBLE) return
        controlsVisible = false
        binding.controlsOverlay.visibility = View.GONE
        hideControlsJob?.cancel()
    }

    private fun scheduleHideControls() {
        hideControlsJob?.cancel()
        hideControlsJob = lifecycleScope.launch {
            delay(3_500)
            if (!userSeeking && player?.isPlaying == true) {
                hideControls()
            }
        }
    }

    private fun openQualitySheet() {
        binding.sheetScrim.visibility = View.VISIBLE
        binding.qualitySheet.visibility = View.VISIBLE
        binding.episodesSheet.visibility = View.GONE
        refreshQualityUi()
        hideControlsJob?.cancel()
        showControls(autoHide = false)
    }

    private fun openEpisodesSheet() {
        refreshEpisodePicker()
        binding.sheetScrim.visibility = View.VISIBLE
        binding.episodesSheet.visibility = View.VISIBLE
        binding.qualitySheet.visibility = View.GONE
        hideControlsJob?.cancel()
        showControls(autoHide = false)
    }

    private fun closeSheets() {
        binding.sheetScrim.visibility = View.GONE
        binding.qualitySheet.visibility = View.GONE
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
        binding.qualityBadge.text = "${currentQuality}p"
        binding.qualityBadge.isSelected = true
        binding.quality480.isSelected = currentQuality == 480
        binding.quality720.isSelected = currentQuality == 720
        binding.quality1080.isSelected = currentQuality == 1080
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
        val selected = episodeIds.getOrNull(episodeIndex)
        episodeAdapter.submit(episodes, selected)
        binding.episodesSheetTitle.text =
            getString(R.string.episodes) + " · ${episodeIndex + 1}/${episodeIds.size}"
    }

    private fun switchEpisode(index: Int) {
        if (index !in episodeIds.indices) return
        saveProgress()
        episodeIndex = index
        startPositionMs = 0L
        refreshEpisodePicker()
        loadCurrent()
    }

    private fun loadCurrent() {
        val episodeId = episodeIds.getOrNull(episodeIndex) ?: return
        val episodeNumber = episodeNumbers.getOrNull(episodeIndex) ?: (episodeIndex + 1)
        binding.playerTitle.text = "$seriesTitle · 第 ${episodeNumber} 集"
        binding.playerTitle.isSelected = true
        binding.retryButton.visibility = View.GONE
        binding.loadingBox.visibility = View.VISIBLE
        showControls(autoHide = false)

        lifecycleScope.launch {
            if (settings.wifiOnlyNow() && !isWifi()) {
                Toast.makeText(this@PlayerActivity, R.string.error_wifi_only, Toast.LENGTH_LONG).show()
                binding.retryButton.visibility = View.VISIBLE
                binding.loadingBox.visibility = View.GONE
                return@launch
            }
            try {
                currentQuality = settings.maxQualityNow()
                refreshQualityUi()
                val stream = repo.stream(seriesId, episodeId)
                binding.playerNotice.visibility = if (stream.isPreview) View.VISIBLE else View.GONE
                if (stream.quality.isNotBlank()) {
                    binding.qualityBadge.text = stream.quality.replace("P", "p", ignoreCase = true)
                }
                playStream(stream, startPositionMs)
            } catch (e: Exception) {
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
        releasePlayer(save = false)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(1_500, 8_000, 1_000, 1_500)
            .build()

        val key = stream.decryptionKey
        val exo = if (key != null) {
            val remote = withContext(Dispatchers.IO) {
                RemoteVideo(
                    mediaHttp,
                    UpstreamStreamInfo(stream.url, key, stream.quality),
                ).prepare()
            }
            remoteVideo = remote
            ExoPlayer.Builder(this)
                .setMediaSourceFactory(ProgressiveMediaSource.Factory { VideoDataSource(remote) })
                .setLoadControl(loadControl)
                .build()
        } else {
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
            ExoPlayer.Builder(this)
                .setMediaSourceFactory(DefaultMediaSourceFactory(factory))
                .setLoadControl(loadControl)
                .build()
        }

        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                updatePlayPause()
                binding.loadingBox.visibility =
                    if (playbackState == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                if (playbackState == Player.STATE_READY) {
                    scheduleHideControls()
                }
                if (playbackState == Player.STATE_ENDED) {
                    showControls(autoHide = false)
                    if (episodeIndex < episodeIds.lastIndex) {
                        switchEpisode(episodeIndex + 1)
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlayPause()
                if (isPlaying) scheduleHideControls() else hideControlsJob?.cancel()
            }

            override fun onPlayerError(error: PlaybackException) {
                binding.retryButton.visibility = View.VISIBLE
                binding.loadingBox.visibility = View.GONE
                showControls(autoHide = false)
                Toast.makeText(this@PlayerActivity, error.message ?: "播放失败", Toast.LENGTH_LONG).show()
            }
        })

        binding.playerView.player = exo
        player = exo
        exo.setMediaItem(MediaItem.fromUri(stream.url))
        exo.prepare()
        if (positionMs > 0) exo.seekTo(positionMs)
        exo.playWhenReady = true
        startProgressLoop()
        updatePlayPause()
        binding.loadingBox.visibility = View.VISIBLE
    }

    private fun updatePlayPause() {
        val playing = player?.isPlaying == true
        binding.playPauseButton.text = getString(if (playing) R.string.pause else R.string.play)
        binding.prevButton.isEnabled = episodeIndex > 0
        binding.nextButton.isEnabled = episodeIndex < episodeIds.lastIndex
        binding.prevButton.alpha = if (binding.prevButton.isEnabled) 1f else 0.4f
        binding.nextButton.alpha = if (binding.nextButton.isEnabled) 1f else 0.4f
    }

    private fun startProgressLoop() {
        progressJob?.cancel()
        progressJob = lifecycleScope.launch {
            while (isActive) {
                updateSeekUi()
                delay(500)
                if (isActive) saveProgress()
                delay(1_500)
            }
        }
    }

    private fun updateSeekUi() {
        val p = player ?: return
        if (userSeeking) return
        val duration = p.duration.takeIf { it > 0 } ?: 0L
        val position = p.currentPosition.coerceAtLeast(0L)
        if (duration > 0) {
            binding.seekBar.progress = ((position * 1000) / duration).toInt().coerceIn(0, 1000)
            val buffered = p.bufferedPosition.coerceAtLeast(0L)
            binding.seekBar.secondaryProgress =
                ((buffered * 1000) / duration).toInt().coerceIn(0, 1000)
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
        binding.gestureHint.postDelayed({
            binding.gestureHint.visibility = View.GONE
        }, 700)
    }

    private fun saveProgress() {
        val p = player ?: return
        val episodeId = episodeIds.getOrNull(episodeIndex) ?: return
        val episodeNumber = episodeNumbers.getOrNull(episodeIndex) ?: (episodeIndex + 1)
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
        if (save) saveProgress()
        binding.playerView.player = null
        player?.release()
        player = null
        remoteVideo?.close()
        remoteVideo = null
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
        saveProgress()
    }

    override fun onDestroy() {
        hideControlsJob?.cancel()
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
