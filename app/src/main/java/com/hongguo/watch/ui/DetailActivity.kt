package com.hongguo.watch.ui

import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import coil.transform.RoundedCornersTransformation
import com.hongguo.watch.HongguoWatchApp
import com.hongguo.watch.R
import com.hongguo.watch.data.ContentRepository
import com.hongguo.watch.data.CoverUrls
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
    private var ascending = true
    private var incomingTags: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (WatchUi.isCompactWatch(this)) WatchUi.applyImmersive(this)
        WatchUi.applySafePadding(binding.detailRoot)

        seriesId = intent.getStringExtra(EXTRA_SERIES_ID).orEmpty()
        seriesTitle = intent.getStringExtra(EXTRA_SERIES_TITLE).orEmpty()
        cover = intent.getStringExtra(EXTRA_COVER).orEmpty()
        incomingTags = intent.getStringArrayListExtra(EXTRA_TAGS).orEmpty()
        source = runCatching {
            SeriesSource.valueOf(intent.getStringExtra(EXTRA_SOURCE) ?: SeriesSource.APP.name)
        }.getOrDefault(SeriesSource.APP)

        binding.seriesTitle.text = seriesTitle
        binding.pageTitle.text = getString(R.string.detail_title)
        binding.seriesIntro.text = intent.getStringExtra(EXTRA_INTRO).orEmpty()
        bindCover(cover)
        bindTags(incomingTags)
        bindHeatAndRating(incomingTags)

        binding.backButton.setOnClickListener { finish() }
        binding.favoriteAction.setOnClickListener { toggleFavorite() }
        binding.favoriteButton.setOnClickListener { toggleFavorite() }
        binding.playNowButton.setOnClickListener { playPrimary() }
        binding.continueButton.setOnClickListener { playPrimary(resumePreferred = true) }
        binding.orderToggle.setOnClickListener {
            ascending = !ascending
            binding.orderToggle.text = getString(
                if (ascending) R.string.episode_order_asc else R.string.episode_order_desc,
            )
            submitEpisodes()
        }

        adapter = EpisodeAdapter { openPlayer(it, resume = false) }
        binding.episodeGrid.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
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
                    putStringArrayListExtra(EXTRA_TAGS, ArrayList(series.tags))
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
        lifecycleScope.launch { refreshFavoriteUi() }
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
                bindCover(cover)
                val intro = detail.series.description.ifBlank {
                    binding.seriesIntro.text?.toString().orEmpty()
                }
                if (intro.isNotBlank()) {
                    binding.seriesIntro.text = getString(R.string.synopsis_label, intro)
                    binding.seriesIntro.visibility = View.VISIBLE
                } else {
                    binding.seriesIntro.visibility = View.GONE
                }
                val tags = (incomingTags + detail.series.tags).distinct().filter { it.isNotBlank() }
                bindTags(tags)
                bindHeatAndRating(tags)
                episodes = detail.episodes
                binding.episodesLabel.text = getString(R.string.episodes_with_total, episodes.size)
                binding.episodesMeta.text = getString(
                    if (episodes.size >= 20) R.string.detail_meta_done else R.string.detail_meta_ongoing,
                    episodes.size.coerceAtLeast(episodeCount),
                )
                val history = repo.historyFor(seriesId)
                resumeEpisodeId = history?.episodeId
                resumePositionMs = repo.resumePositionMs(history)
                binding.continueButton.visibility =
                    if (history != null && episodes.isNotEmpty()) View.VISIBLE else View.GONE
                if (history != null) {
                    binding.continueButton.text =
                        getString(R.string.continue_watch) + " · ${history.episodeNumber}"
                }
                submitEpisodes()
                val index = episodes.indexOfFirst { it.id == history?.episodeId }.coerceAtLeast(0)
                binding.episodeGrid.post { binding.episodeGrid.scrollToPosition(index) }
                refreshFavoriteUi()
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

    private fun submitEpisodes() {
        val ordered = if (ascending) episodes else episodes.asReversed()
        adapter.submit(ordered, resumeEpisodeId)
    }

    private fun bindCover(url: String) {
        val radius = resources.displayMetrics.density * 8f
        val coverUrl = CoverUrls.normalize(url).ifBlank { url }
        binding.cover.load(coverUrl.ifBlank { null }) {
            size(216, 288)
            crossfade(false)
            transformations(RoundedCornersTransformation(radius))
            placeholder(R.drawable.bg_cover)
            error(R.drawable.bg_cover)
        }
    }

    private fun bindTags(tags: List<String>) {
        binding.tagRow.removeAllViews()
        fun isMeta(value: String): Boolean =
            value.contains("万") || value.contains("收藏") || value.contains("点赞") ||
                value.contains("评分") || value.contains("播放") || value.contains("分")
        val display = tags.filterNot(::isMeta).take(3).ifEmpty {
            listOf(getString(R.string.chip_short))
        }
        val padH = WatchUi.dp(this, 6f)
        val padV = WatchUi.dp(this, 2f)
        val gap = WatchUi.dp(this, 4f)
        display.forEachIndexed { index, label ->
            val chip = TextView(this).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                setTextColor(ContextCompat.getColor(this@DetailActivity, R.color.accent))
                background = ContextCompat.getDrawable(this@DetailActivity, R.drawable.bg_detail_tag)
                setPadding(padH, padV, padH, padV)
                includeFontPadding = false
            }
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            if (index > 0) lp.marginStart = gap
            binding.tagRow.addView(chip, lp)
        }
    }

    private fun bindHeatAndRating(tags: List<String>) {
        val rating = tags.firstOrNull { it.contains("评分") || (it.contains("分") && it.any(Char::isDigit)) }
            ?.replace("评分", "")?.trim()
        val heat = tags.firstOrNull {
            it.contains("万") || it.contains("热度") || it.contains("收藏") || it.contains("点赞")
        }
        if (!rating.isNullOrBlank()) {
            val score = rating.removeSuffix("分")
            binding.ratingText.visibility = View.VISIBLE
            binding.ratingText.text = getString(R.string.detail_rating, score)
        } else {
            binding.ratingText.visibility = View.GONE
        }
        if (!heat.isNullOrBlank()) {
            binding.heatText.visibility = View.VISIBLE
            binding.heatText.text = getString(R.string.detail_heat, heat.removeSuffix("热度").trim())
        } else {
            binding.heatText.visibility = View.GONE
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

    private suspend fun refreshFavoriteUi() {
        favorited = repo.isFavorite(seriesId)
        binding.favoriteButton.setImageResource(
            if (favorited) R.drawable.ic_favorite else R.drawable.ic_favorite_border,
        )
        binding.favoriteAction.text = getString(R.string.favorite)
        binding.favoriteAction.setTextColor(
            ContextCompat.getColor(
                this,
                if (favorited) R.color.accent else R.color.text_primary,
            ),
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
            refreshFavoriteUi()
            Toast.makeText(
                this@DetailActivity,
                if (favorited) R.string.favorite_added else R.string.favorite_removed,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun playPrimary(resumePreferred: Boolean = true) {
        val target = if (resumePreferred) {
            episodes.firstOrNull { it.id == resumeEpisodeId } ?: episodes.firstOrNull()
        } else {
            episodes.firstOrNull()
        }
        if (target != null) {
            openPlayer(target, resume = resumePreferred && target.id == resumeEpisodeId)
        } else {
            Toast.makeText(this, R.string.error_generic, Toast.LENGTH_SHORT).show()
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
        const val EXTRA_TAGS = "tags"
    }
}
