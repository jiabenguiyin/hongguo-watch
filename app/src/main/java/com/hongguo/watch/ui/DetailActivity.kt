package com.hongguo.watch.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.hongguo.watch.HongguoWatchApp
import com.hongguo.watch.R
import com.hongguo.watch.data.ContentRepository
import com.hongguo.watch.data.model.Episode
import com.hongguo.watch.data.model.SeriesSource
import com.hongguo.watch.databinding.ActivityDetailBinding
import kotlinx.coroutines.launch

class DetailActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDetailBinding
    private lateinit var adapter: EpisodeAdapter
    private lateinit var relatedAdapter: SeriesListAdapter

    private val repo get() = (application as HongguoWatchApp).container.repository

    private var seriesId: String = ""
    private var seriesTitle: String = ""
    private var cover: String = ""
    private var source: SeriesSource = SeriesSource.APP
    private var episodeCount: Int = 0
    private var episodes: List<Episode> = emptyList()
    private var resumeEpisodeId: String? = null
    private var resumePositionMs: Long = 0L
    private var favorited = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (WatchUi.isCompactWatch(this)) WatchUi.applyImmersive(this)
        WatchUi.applySafePadding(binding.detailRoot)
        if (WatchUi.useRoundLayout(this)) {
            val bottom = WatchUi.dp(this, 10f)
            binding.relatedList.setPadding(
                binding.relatedList.paddingLeft,
                binding.relatedList.paddingTop,
                binding.relatedList.paddingRight,
                bottom,
            )
        }

        seriesId = intent.getStringExtra(EXTRA_SERIES_ID).orEmpty()
        seriesTitle = intent.getStringExtra(EXTRA_SERIES_TITLE).orEmpty()
        cover = intent.getStringExtra(EXTRA_COVER).orEmpty()
        source = runCatching {
            SeriesSource.valueOf(intent.getStringExtra(EXTRA_SOURCE) ?: SeriesSource.APP.name)
        }.getOrDefault(SeriesSource.APP)

        binding.seriesTitle.text = seriesTitle
        binding.seriesIntro.text = intent.getStringExtra(EXTRA_INTRO).orEmpty()
        binding.backButton.setOnClickListener { finish() }
        binding.favoriteButton.setOnClickListener { toggleFavorite() }
        binding.continueButton.setOnClickListener {
            val target = episodes.firstOrNull { it.id == resumeEpisodeId } ?: episodes.firstOrNull()
            if (target != null) openPlayer(target, resume = true)
        }

        adapter = EpisodeAdapter { openPlayer(it, resume = false) }
        binding.episodeGrid.layoutManager = GridLayoutManager(this, WatchUi.episodeColumns(this))
        binding.episodeGrid.adapter = adapter
        binding.episodeGrid.itemAnimator = null

        relatedAdapter = SeriesListAdapter { series ->
            startActivity(
                Intent(this, DetailActivity::class.java).apply {
                    putExtra(EXTRA_SERIES_ID, series.id)
                    putExtra(EXTRA_SERIES_TITLE, series.title)
                    putExtra(EXTRA_COVER, series.cover)
                    putExtra(EXTRA_INTRO, series.description)
                    putExtra(EXTRA_SOURCE, series.source.name)
                },
            )
        }
        binding.relatedList.layoutManager = LinearLayoutManager(this)
        binding.relatedList.adapter = relatedAdapter
        binding.relatedList.itemAnimator = null

        loadDetail()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && WatchUi.isCompactWatch(this)) WatchUi.applyImmersive(this)
    }

    override fun onResume() {
        super.onResume()
        if (WatchUi.isCompactWatch(this)) WatchUi.applyImmersive(this)
        lifecycleScope.launch { refreshFavoriteIcon() }
    }

    private fun loadDetail() {
        binding.loading.visibility = View.VISIBLE
        binding.errorView.visibility = View.GONE
        lifecycleScope.launch {
            try {
                val detail = repo.detail(seriesId)
                seriesTitle = detail.series.title
                cover = detail.series.cover
                episodeCount = detail.series.episodeCount
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
                resumePositionMs = repo.resumePositionMs(history)
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
                refreshFavoriteIcon()
                // 已收藏则用详情真实总集数回写，避免库内残留历史进度集号
                if (favorited) {
                    val total = ContentRepository.episodeTotal(episodeCount, episodes.size)
                    repo.addFavorite(
                        seriesId = seriesId,
                        seriesTitle = seriesTitle,
                        cover = cover,
                        source = source,
                        episodeCount = total,
                    )
                }
                loadRelated()
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

    private suspend fun loadRelated() {
        val rows = runCatching { repo.related(seriesId, seriesTitle) }.getOrDefault(emptyList())
        if (rows.isEmpty()) {
            binding.relatedLabel.visibility = View.GONE
            binding.relatedList.visibility = View.GONE
            return
        }
        binding.relatedLabel.visibility = View.VISIBLE
        binding.relatedList.visibility = View.VISIBLE
        relatedAdapter.showResumeHint = false
        relatedAdapter.submit(
            rows.map { work ->
                HomeRow.Item(
                    work.series.copy(
                        description = work.label,
                        tags = listOfNotNull(work.seasonLabel.takeIf { it.isNotBlank() }),
                    ),
                )
            },
        )
    }

    private suspend fun refreshFavoriteIcon() {
        favorited = repo.isFavorite(seriesId)
        binding.favoriteButton.setImageResource(
            if (favorited) R.drawable.ic_favorite else R.drawable.ic_favorite_border,
        )
    }

    private fun toggleFavorite() {
        lifecycleScope.launch {
            favorited = repo.toggleFavorite(
                seriesId = seriesId,
                seriesTitle = seriesTitle,
                cover = cover,
                source = source,
                episodeCount = ContentRepository.episodeTotal(episodeCount, episodes.size),
            )
            binding.favoriteButton.setImageResource(
                if (favorited) R.drawable.ic_favorite else R.drawable.ic_favorite_border,
            )
            Toast.makeText(
                this@DetailActivity,
                if (favorited) R.string.favorite_added else R.string.favorite_removed,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun openPlayer(episode: Episode, resume: Boolean) {
        val position = if (resume && episode.id == resumeEpisodeId) resumePositionMs else 0L
        startActivity(
            Intent(this, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_SERIES_ID, seriesId)
                putExtra(PlayerActivity.EXTRA_SERIES_TITLE, seriesTitle)
                putExtra(PlayerActivity.EXTRA_COVER, cover)
                putExtra(PlayerActivity.EXTRA_SOURCE, source.name)
                putExtra(PlayerActivity.EXTRA_EPISODE_ID, episode.id)
                putExtra(PlayerActivity.EXTRA_EPISODE_NUMBER, episode.number)
                putExtra(PlayerActivity.EXTRA_START_POSITION_MS, position)
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
