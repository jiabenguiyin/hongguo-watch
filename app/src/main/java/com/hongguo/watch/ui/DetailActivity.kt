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
    private var source: SeriesSource = SeriesSource.OFFICIAL
    private var episodes: List<Episode> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        seriesId = intent.getStringExtra(EXTRA_SERIES_ID).orEmpty()
        seriesTitle = intent.getStringExtra(EXTRA_SERIES_TITLE).orEmpty()
        cover = intent.getStringExtra(EXTRA_COVER).orEmpty()
        source = runCatching {
            SeriesSource.valueOf(intent.getStringExtra(EXTRA_SOURCE) ?: SeriesSource.OFFICIAL.name)
        }.getOrDefault(SeriesSource.OFFICIAL)

        binding.seriesTitle.text = seriesTitle
        binding.seriesIntro.text = intent.getStringExtra(EXTRA_INTRO).orEmpty()

        adapter = EpisodeAdapter { openPlayer(it) }
        binding.episodeGrid.layoutManager = GridLayoutManager(this, 4)
        binding.episodeGrid.adapter = adapter

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
                }
                episodes = detail.episodes
                val history = repo.historyFor(seriesId)
                adapter.submit(episodes, history?.episodeId)
            } catch (e: Exception) {
                binding.errorView.visibility = View.VISIBLE
                binding.errorView.text = e.message ?: getString(R.string.error_generic)
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
