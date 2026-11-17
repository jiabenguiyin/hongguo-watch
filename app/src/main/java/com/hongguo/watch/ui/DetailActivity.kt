package com.hongguo.watch.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.hongguo.watch.HongguoWatchApp
import com.hongguo.watch.R
import com.hongguo.watch.data.model.Episode
import com.hongguo.watch.data.model.SeriesSource
import com.hongguo.watch.databinding.ActivityDetailBinding
import kotlinx.coroutines.launch

class DetailActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDetailBinding
    private lateinit var adapter: EpisodeAdapter

    private val repo get() = (application as HongguoWatchApp).container.repository

    private var seriesId: String = ""
    private var seriesTitle: String = ""
    private var cover: String = ""
    private var source: SeriesSource = SeriesSource.APP
    private var episodes: List<Episode> = emptyList()
    private var resumeEpisodeId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WatchUi.applySafePadding(binding.detailRoot)

        seriesId = intent.getStringExtra(EXTRA_SERIES_ID).orEmpty()
        seriesTitle = intent.getStringExtra(EXTRA_SERIES_TITLE).orEmpty()
        cover = intent.getStringExtra(EXTRA_COVER).orEmpty()
        source = runCatching {
            SeriesSource.valueOf(intent.getStringExtra(EXTRA_SOURCE) ?: SeriesSource.APP.name)
        }.getOrDefault(SeriesSource.APP)

        binding.seriesTitle.text = seriesTitle
        binding.seriesIntro.text = intent.getStringExtra(EXTRA_INTRO).orEmpty()
        binding.backButton.setOnClickListener { finish() }
        binding.continueButton.setOnClickListener {
            val target = episodes.firstOrNull { it.id == resumeEpisodeId } ?: episodes.firstOrNull()
            if (target != null) openPlayer(target)
        }

        adapter = EpisodeAdapter { openPlayer(it) }
        binding.episodeGrid.layoutManager = GridLayoutManager(this, WatchUi.episodeColumns(this))
        binding.episodeGrid.adapter = adapter
        binding.episodeGrid.itemAnimator = null

        loadDetail()
    }

    private fun loadDetail() {
        binding.loading.visibility = View.VISIBLE
        binding.errorView.visibility = View.GONE
        lifecycleScope.launch {
            try {
                val detail = repo.detail(seriesId)
                seriesTitle = detail.series.title
                cover = detail.series.cover
                binding.seriesTitle.text = detail.series.title
                if (detail.series.description.isNotBlank()) {
                    binding.seriesIntro.text = detail.series.description
                    binding.seriesIntro.visibility = View.VISIBLE
                } else if (binding.seriesIntro.text.isNullOrBlank()) {
                    binding.seriesIntro.visibility = View.GONE
                }
                episodes = detail.episodes
                binding.episodesLabel.text = getString(R.string.episodes_count, episodes.size)
                val history = repo.historyFor(seriesId)
                resumeEpisodeId = history?.episodeId
                binding.continueButton.visibility =
                    if (history != null && episodes.isNotEmpty()) View.VISIBLE else View.GONE
                if (history != null) {
                    binding.continueButton.text =
                        getString(R.string.continue_watch) + " · ${history.episodeNumber}"
                }
                adapter.submit(episodes, history?.episodeId)
                val index = episodes.indexOfFirst { it.id == history?.episodeId }.coerceAtLeast(0)
                binding.episodeGrid.post {
                    binding.episodeGrid.scrollToPosition(index)
                }
            } catch (e: Exception) {
                binding.errorView.visibility = View.VISIBLE
                binding.errorView.text =
                    (e.message ?: getString(R.string.error_generic)) + "\n" + getString(R.string.retry)
                binding.errorView.setOnClickListener { loadDetail() }
                Toast.makeText(this@DetailActivity, e.message, Toast.LENGTH_LONG).show()
            } finally {
                binding.loading.visibility = View.GONE
            }
        }
    }

    private fun openPlayer(episode: Episode) {
        startActivity(
            Intent(this, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_SERIES_ID, seriesId)
                putExtra(PlayerActivity.EXTRA_SERIES_TITLE, seriesTitle)
                putExtra(PlayerActivity.EXTRA_COVER, cover)
                putExtra(PlayerActivity.EXTRA_SOURCE, source.name)
                putExtra(PlayerActivity.EXTRA_EPISODE_ID, episode.id)
                putExtra(PlayerActivity.EXTRA_EPISODE_NUMBER, episode.number)
                putStringArrayListExtra(
                    PlayerActivity.EXTRA_EPISODE_IDS,
                    ArrayList(episodes.map { it.id }),
                )
                putIntegerArrayListExtra(
                    PlayerActivity.EXTRA_EPISODE_NUMBERS,
                    ArrayList(episodes.map { it.number }),
                )
            },
        )
    }

    companion object {
        const val EXTRA_SERIES_ID = "series_id"
        const val EXTRA_SERIES_TITLE = "series_title"
        const val EXTRA_COVER = "cover"
        const val EXTRA_INTRO = "intro"
        const val EXTRA_SOURCE = "source"
    }
}
