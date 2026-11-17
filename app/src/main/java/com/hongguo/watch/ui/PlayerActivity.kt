package com.hongguo.watch.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.hongguo.watch.HongguoWatchApp
import com.hongguo.watch.R
import com.hongguo.watch.data.model.SeriesSource
import com.hongguo.watch.databinding.ActivityPlayerBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PlayerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private var progressJob: Job? = null

    private val repo get() = (application as HongguoWatchApp).container.repository
    private val settings get() = (application as HongguoWatchApp).container.settings

    private var seriesId = ""
    private var seriesTitle = ""
    private var cover = ""
    private var source = SeriesSource.OFFICIAL
    private var episodeIds: List<String> = emptyList()
    private var episodeNumbers: List<Int> = emptyList()
    private var episodeIndex = 0
    private var startPositionMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        seriesId = intent.getStringExtra(EXTRA_SERIES_ID).orEmpty()
        seriesTitle = intent.getStringExtra(EXTRA_SERIES_TITLE).orEmpty()
        cover = intent.getStringExtra(EXTRA_COVER).orEmpty()
        source = runCatching {
            SeriesSource.valueOf(intent.getStringExtra(EXTRA_SOURCE) ?: SeriesSource.OFFICIAL.name)
        }.getOrDefault(SeriesSource.OFFICIAL)
        startPositionMs = intent.getLongExtra(EXTRA_START_POSITION_MS, 0L)

        episodeIds = intent.getStringArrayListExtra(EXTRA_EPISODE_IDS)?.toList().orEmpty()
        episodeNumbers = intent.getIntegerArrayListExtra(EXTRA_EPISODE_NUMBERS)?.toList().orEmpty()

        val initialEpisodeId = intent.getStringExtra(EXTRA_EPISODE_ID)
        val initialNumber = intent.getIntExtra(EXTRA_EPISODE_NUMBER, 1)

        lifecycleScope.launch {
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
            bindControls()
            loadCurrent()
        }
    }

    private fun bindControls() {
        binding.prevButton.setOnClickListener { switchEpisode(episodeIndex - 1) }
        binding.nextButton.setOnClickListener { switchEpisode(episodeIndex + 1) }
        binding.playPauseButton.setOnClickListener {
            val p = player ?: return@setOnClickListener
            if (p.isPlaying) p.pause() else p.play()
            updatePlayPause()
        }
        binding.retryButton.setOnClickListener { loadCurrent() }
    }

    private fun switchEpisode(index: Int) {
        if (index !in episodeIds.indices) return
        saveProgress()
        episodeIndex = index
        startPositionMs = 0L
        loadCurrent()
    }

    private fun loadCurrent() {
        val episodeId = episodeIds.getOrNull(episodeIndex) ?: return
        val episodeNumber = episodeNumbers.getOrNull(episodeIndex) ?: (episodeIndex + 1)
        binding.playerTitle.text = "$seriesTitle · 第 ${episodeNumber} 集"
        binding.retryButton.visibility = View.GONE

        lifecycleScope.launch {
            if (settings.wifiOnlyNow() && !isWifi()) {
                Toast.makeText(this@PlayerActivity, R.string.error_wifi_only, Toast.LENGTH_LONG).show()
                binding.retryButton.visibility = View.VISIBLE
                return@launch
            }
            try {
                val stream = repo.stream(seriesId, episodeId)
                binding.playerNotice.visibility = if (stream.isPreview) View.VISIBLE else View.GONE
                playUrl(stream.url, stream.userAgent, stream.referer, startPositionMs)
            } catch (e: Exception) {
                Toast.makeText(this@PlayerActivity, e.message ?: getString(R.string.error_no_play_url), Toast.LENGTH_LONG).show()
                binding.retryButton.visibility = View.VISIBLE
            }
        }
    }

    private fun playUrl(url: String, userAgent: String?, referer: String?, positionMs: Long) {
        releasePlayer()
        val factory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent ?: "HongguoWatch/0.1")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(12_000)
            .setDefaultRequestProperties(
                buildMap {
                    if (!referer.isNullOrBlank()) put("Referer", referer)
                },
            )

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(1_500, 8_000, 1_000, 1_500)
            .build()

        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(factory))
            .setLoadControl(loadControl)
            .build()

        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                updatePlayPause()
            }

            override fun onPlayerError(error: PlaybackException) {
                binding.retryButton.visibility = View.VISIBLE
                Toast.makeText(this@PlayerActivity, error.message ?: "播放失败", Toast.LENGTH_LONG).show()
            }
        })

        binding.playerView.player = exo
        player = exo
        exo.setMediaItem(MediaItem.fromUri(url))
        exo.prepare()
        if (positionMs > 0) exo.seekTo(positionMs)
        exo.playWhenReady = true
        startProgressLoop()
        updatePlayPause()
    }

    private fun updatePlayPause() {
        val playing = player?.isPlaying == true
        binding.playPauseButton.text = getString(if (playing) R.string.pause else R.string.play)
        binding.prevButton.isEnabled = episodeIndex > 0
        binding.nextButton.isEnabled = episodeIndex < episodeIds.lastIndex
    }

    private fun startProgressLoop() {
        progressJob?.cancel()
        progressJob = lifecycleScope.launch {
            while (isActive) {
                delay(2_000)
                saveProgress()
            }
        }
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

    private fun releasePlayer() {
        progressJob?.cancel()
        saveProgress()
        binding.playerView.player = null
        player?.release()
        player = null
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
        saveProgress()
    }

    override fun onDestroy() {
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
    }
}
