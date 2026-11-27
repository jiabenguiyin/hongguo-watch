package com.hongguo.watch.ui

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import coil.transform.RoundedCornersTransformation
import com.hongguo.watch.HongguoWatchApp
import com.hongguo.watch.R
import com.hongguo.watch.data.ContentRepository
import com.hongguo.watch.data.CoverUrls
import com.hongguo.watch.data.cache.VideoCacheManager
import com.hongguo.watch.data.cache.VideoCachePriority
import com.hongguo.watch.data.cache.VideoCacheRequest
import com.hongguo.watch.data.model.Episode
import com.hongguo.watch.data.model.SeriesSource
import com.hongguo.watch.databinding.ActivityDetailBinding
import com.hongguo.watch.player.WatchDecodePolicy
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class DetailActivity : WatchDisplayActivity() {
    private lateinit var binding: ActivityDetailBinding
    private lateinit var toolbarClock: WatchToolbarClock
    private lateinit var adapter: EpisodeAdapter
    private lateinit var relatedAdapter: SeriesListAdapter

    private val container get() = (application as HongguoWatchApp).container
    private val repo get() = container.repository
    private val videoCache get() = container.videoCache

    private var seriesId: String = ""
    private var seriesTitle: String = ""
    private var cover: String = ""
    private var synopsisText: String = ""
    private var synopsisExpanded = false
    private var synopsisExpandable = false
    private var source: SeriesSource = SeriesSource.APP
    private var episodeCount: Int = 0
    private var episodes: List<Episode> = emptyList()
    private var resumeEpisodeId: String? = null
    private var resumePositionMs: Long = 0L
    private var favorited = false
    private var ascending = true
    private var episodesExpanded = false
    private var selectedRangeIndex = 0
    private var episodeRanges: List<IntRange> = emptyList()
    private var incomingTags: List<String> = emptyList()

    /** 详情页手动下载：多选模式。 */
    private var downloadSelectMode = false
    private val downloadChecked = linkedSetOf<String>()
    private var downloadStatusJob: Job? = null
    private var downloadEnqueueJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (WatchUi.isCompactWatch(this)) WatchUi.applyImmersive(this)
        WatchUi.applySafePadding(binding.detailRoot, topChrome = binding.detailToolbar)
        toolbarClock = WatchToolbarClock(this, binding.toolbarClock)

        seriesId = intent.getStringExtra(EXTRA_SERIES_ID).orEmpty()
        seriesTitle = intent.getStringExtra(EXTRA_SERIES_TITLE).orEmpty()
        cover = intent.getStringExtra(EXTRA_COVER).orEmpty()
        incomingTags = intent.getStringArrayListExtra(EXTRA_TAGS).orEmpty()
        source = runCatching {
            SeriesSource.valueOf(intent.getStringExtra(EXTRA_SOURCE) ?: SeriesSource.APP.name)
        }.getOrDefault(SeriesSource.APP)

        binding.seriesTitle.text = seriesTitle
        binding.pageTitle.text = getString(R.string.detail_title)
        renderSynopsis(intent.getStringExtra(EXTRA_INTRO).orEmpty())
        bindCover(cover)
        bindTags(incomingTags)
        bindHeatAndRating(incomingTags)
        binding.cover.contentDescription = getString(R.string.cover_preview_action)
        binding.cover.setOnClickListener { showCoverPreview() }
        binding.synopsisExpandToggle.setOnClickListener { toggleSynopsisExpansion() }

        binding.backButton.setOnClickListener {
            if (downloadSelectMode) {
                exitDownloadSelectMode()
            } else {
                finish()
            }
        }
        binding.favoriteAction.setOnClickListener { toggleFavorite() }
        binding.favoriteButton.setOnClickListener { toggleFavorite() }
        binding.cacheAction.setOnClickListener { enterDownloadSelectMode() }
        binding.downloadSeasonButton.setOnClickListener { enqueueDownload(fullSeason = true) }
        binding.downloadSelectedButton.setOnClickListener { enqueueDownload(fullSeason = false) }
        binding.downloadCancelButton.setOnClickListener { exitDownloadSelectMode() }
        binding.playNowButton.setOnClickListener {
            if (downloadSelectMode) {
                Toast.makeText(this, R.string.download_hint, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            playPrimary()
        }
        binding.continueButton.setOnClickListener {
            if (downloadSelectMode) {
                Toast.makeText(this, R.string.download_hint, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            playPrimary(resumePreferred = true)
        }
        binding.orderToggle.setOnClickListener {
            ascending = !ascending
            binding.orderToggle.text = getString(
                if (ascending) R.string.episode_order_asc else R.string.episode_order_desc,
            )
            submitEpisodes()
        }
        binding.expandToggle.setOnClickListener { toggleEpisodeExpand() }

        adapter = EpisodeAdapter { episode ->
            if (downloadSelectMode) {
                toggleDownloadCheck(episode)
            } else {
                openPlayer(episode, resume = false)
            }
        }
        binding.episodeGrid.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.episodeGrid.adapter = adapter
        binding.episodeGrid.itemAnimator = null
        binding.episodeGrid.isNestedScrollingEnabled = false

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
        if (recreateForDisplaySizeChange()) return
        if (WatchUi.isCompactWatch(this)) WatchUi.applyImmersive(this)
        WatchUi.applySafePadding(binding.detailRoot, topChrome = binding.detailToolbar)
        TimeLockGate.enforceIfBlocked(this, container.timeLock)
        lifecycleScope.launch {
            refreshFavoriteUi()
            refreshDownloadChrome()
            submitEpisodes()
        }
        ensureDownloadStatusPolling()
    }

    override fun onPause() {
        downloadStatusJob?.cancel()
        downloadStatusJob = null
        super.onPause()
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
                val intro = detail.series.description.ifBlank { synopsisText }
                renderSynopsis(intro)
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
                episodeRanges = EpisodeExpandPolicy.ranges(episodes.size)
                selectedRangeIndex = EpisodeExpandPolicy.rangeIndexForEpisode(
                    history?.episodeNumber ?: 1,
                    episodes.size,
                )
                binding.expandToggle.visibility =
                    if (EpisodeExpandPolicy.shouldShowExpand(episodes.size)) View.VISIBLE else View.GONE
                if (!EpisodeExpandPolicy.shouldShowExpand(episodes.size)) {
                    episodesExpanded = false
                }
                applyEpisodeLayout()
                submitEpisodes()
                scrollEpisodesToResume()
                refreshFavoriteUi()
                refreshDownloadChrome()
                if (favorited) {
                    val total = ContentRepository.episodeTotal(episodeCount, episodes.size)
                    repo.refreshFavoriteMetadata(
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

    private fun toggleEpisodeExpand() {
        episodesExpanded = !episodesExpanded
        applyEpisodeLayout()
        submitEpisodes()
        if (!episodesExpanded) {
            scrollEpisodesToResume()
        }
    }

    private fun applyEpisodeLayout() {
        val canExpand = EpisodeExpandPolicy.shouldShowExpand(episodes.size)
        binding.expandToggle.visibility = if (canExpand) View.VISIBLE else View.GONE
        binding.expandToggle.text = getString(
            if (episodesExpanded) R.string.episode_collapse else R.string.episode_expand,
        )
        if (episodesExpanded && canExpand) {
            val widthDp = (resources.displayMetrics.widthPixels / resources.displayMetrics.density).toInt()
            val span = EpisodeExpandPolicy.spanCount(widthDp)
            binding.episodeGrid.layoutManager = GridLayoutManager(this, span)
            binding.episodeRangeScroll.visibility = View.VISIBLE
            renderEpisodeRanges()
        } else {
            binding.episodeGrid.layoutManager =
                LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
            binding.episodeRangeScroll.visibility = View.GONE
        }
    }

    private fun renderEpisodeRanges() {
        binding.episodeRangeRow.removeAllViews()
        if (episodeRanges.isEmpty()) return
        selectedRangeIndex = selectedRangeIndex.coerceIn(0, episodeRanges.lastIndex)
        val padH = WatchUi.dp(this, 8f)
        val padV = WatchUi.dp(this, 4f)
        val gap = WatchUi.dp(this, 4f)
        episodeRanges.forEachIndexed { index, range ->
            val chip = TextView(this).apply {
                text = getString(R.string.episode_range, range.first, range.last)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                includeFontPadding = false
                setPadding(padH, padV, padH, padV)
                setTextColor(
                    ContextCompat.getColor(
                        this@DetailActivity,
                        if (index == selectedRangeIndex) R.color.text_primary else R.color.text_secondary,
                    ),
                )
                background = ContextCompat.getDrawable(
                    this@DetailActivity,
                    if (index == selectedRangeIndex) R.drawable.bg_top_tab_active else R.drawable.bg_top_tab_idle,
                )
                setOnClickListener {
                    selectedRangeIndex = index
                    renderEpisodeRanges()
                    submitEpisodes()
                }
            }
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            if (index > 0) lp.marginStart = gap
            binding.episodeRangeRow.addView(chip, lp)
        }
    }

    private fun submitEpisodes() {
        val ordered = if (ascending) episodes else episodes.asReversed()
        val visible = if (episodesExpanded && episodeRanges.isNotEmpty()) {
            val range = episodeRanges[selectedRangeIndex.coerceIn(0, episodeRanges.lastIndex)]
            EpisodeExpandPolicy.sliceForRange(ordered, range)
        } else {
            ordered
        }
        adapter.submit(
            episodes = visible,
            selectedId = resumeEpisodeId,
            gridMode = episodesExpanded,
            selectionMode = downloadSelectMode,
            checkedIds = downloadChecked.toSet(),
            cacheStates = buildCacheStates(),
        )
        refreshDownloadSelectedLabel()
    }

    private fun buildCacheStates(): Map<String, EpisodeCacheUi> {
        if (episodes.isEmpty()) return emptyMap()
        val out = HashMap<String, EpisodeCacheUi>(episodes.size)
        for (ep in episodes) {
            out[ep.id] = when {
                videoCache.isReady(seriesId, ep.id) -> EpisodeCacheUi.READY
                videoCache.isCaching(seriesId, ep.id) -> EpisodeCacheUi.CACHING
                else -> EpisodeCacheUi.NONE
            }
        }
        return out
    }

    private fun scrollEpisodesToResume() {
        if (episodesExpanded || downloadSelectMode) return
        val ordered = if (ascending) episodes else episodes.asReversed()
        val index = ordered.indexOfFirst { it.id == resumeEpisodeId }.coerceAtLeast(0)
        binding.episodeGrid.post { binding.episodeGrid.scrollToPosition(index) }
    }

    private fun enterDownloadSelectMode() {
        if (episodes.isEmpty()) {
            Toast.makeText(this, R.string.error_generic, Toast.LENGTH_SHORT).show()
            return
        }
        if (!downloadSelectMode) {
            downloadSelectMode = true
            downloadChecked.clear()
            // 默认展开长剧，便于多选
            if (EpisodeExpandPolicy.shouldShowExpand(episodes.size) && !episodesExpanded) {
                episodesExpanded = true
                applyEpisodeLayout()
            }
            Toast.makeText(this, R.string.download_pick_mode, Toast.LENGTH_SHORT).show()
        }
        refreshDownloadChrome()
        submitEpisodes()
        ensureDownloadStatusPolling()
    }

    private fun exitDownloadSelectMode() {
        downloadSelectMode = false
        downloadChecked.clear()
        refreshDownloadChrome()
        submitEpisodes()
    }

    private fun toggleDownloadCheck(episode: Episode) {
        if (episode.id in downloadChecked) {
            downloadChecked.remove(episode.id)
        } else {
            downloadChecked.add(episode.id)
        }
        submitEpisodes()
    }

    private fun refreshDownloadChrome() {
        binding.downloadBar.visibility = if (downloadSelectMode) View.VISIBLE else View.GONE
        binding.downloadHint.visibility = if (downloadSelectMode) View.VISIBLE else View.GONE
        binding.cacheAction.setTextColor(
            ContextCompat.getColor(
                this,
                if (downloadSelectMode) R.color.accent else R.color.text_primary,
            ),
        )
        binding.cacheAction.text = getString(R.string.download_action)
        refreshDownloadSelectedLabel()
    }

    private fun refreshDownloadSelectedLabel() {
        if (!downloadSelectMode) return
        val n = downloadChecked.size
        binding.downloadSelectedButton.text = if (n > 0) {
            getString(R.string.download_selected_count, n)
        } else {
            getString(R.string.download_selected)
        }
    }

    private fun ensureDownloadStatusPolling() {
        if (downloadStatusJob?.isActive == true) return
        downloadStatusJob = lifecycleScope.launch {
            while (isActive) {
                if (episodes.isNotEmpty()) {
                    submitEpisodes()
                }
                val anyCaching = episodes.any { videoCache.isCaching(seriesId, it.id) }
                delay(if (anyCaching || downloadSelectMode) 900L else 2500L)
            }
        }
    }

    /**
     * 用户发起下载：多选分集或全季。不会自动边播边缓存。
     */
    private fun enqueueDownload(fullSeason: Boolean) {
        if (episodes.isEmpty()) {
            Toast.makeText(this, R.string.error_generic, Toast.LENGTH_SHORT).show()
            return
        }
        val targets = if (fullSeason) {
            episodes
        } else {
            episodes.filter { it.id in downloadChecked }
        }
        if (targets.isEmpty()) {
            Toast.makeText(this, R.string.download_none_selected, Toast.LENGTH_SHORT).show()
            return
        }
        val pending = targets.filterNot {
            videoCache.isReady(seriesId, it.id) || videoCache.isCaching(seriesId, it.id)
        }
        if (pending.isEmpty()) {
            Toast.makeText(this, R.string.download_all_ready, Toast.LENGTH_SHORT).show()
            submitEpisodes()
            return
        }
        if (downloadEnqueueJob?.isActive == true) {
            Toast.makeText(this, R.string.cache_caching, Toast.LENGTH_SHORT).show()
            return
        }
        downloadEnqueueJob = lifecycleScope.launch {
            val settings = container.settings
            if (settings.wifiOnlyNow() && !isWifi()) {
                Toast.makeText(this@DetailActivity, R.string.error_wifi_only, Toast.LENGTH_LONG).show()
                return@launch
            }
            val free = videoCache.freeSpaceBytes()
            if (free < VideoCacheManager.STORAGE_HEADROOM_BYTES) {
                Toast.makeText(this@DetailActivity, R.string.cache_low_storage, Toast.LENGTH_LONG).show()
                return@launch
            }
            val userMax = settings.maxQualityNow()
            val effectiveMax = WatchDecodePolicy.effectiveMaxQuality(this@DetailActivity, userMax)
            val queuedIds = ArrayList<String>(pending.size)
            var lastError: String? = null
            for (ep in pending) {
                if (!isActive) break
                try {
                    if (videoCache.freeSpaceBytes() < VideoCacheManager.STORAGE_HEADROOM_BYTES) {
                        lastError = getString(R.string.cache_low_storage)
                        break
                    }
                    val stream = repo.stream(
                        seriesId = seriesId,
                        episodeId = ep.id,
                        preferAvc = true,
                        maxHeight = effectiveMax,
                    )
                    videoCache.enqueue(
                        request = VideoCacheRequest(
                            seriesId = seriesId,
                            episodeId = ep.id,
                            seriesTitle = seriesTitle,
                            episodeNumber = ep.number,
                        ),
                        stream = stream,
                        priority = VideoCachePriority.CURRENT,
                    )
                    queuedIds += ep.id
                    submitEpisodes()
                } catch (err: Exception) {
                    if (err is kotlinx.coroutines.CancellationException) throw err
                    lastError = if (VideoCacheManager.isNoSpace(err)) {
                        getString(R.string.cache_low_storage)
                    } else {
                        err.message ?: getString(R.string.error_generic)
                    }
                }
            }
            val queued = queuedIds.size
            if (queued > 0) {
                val msg = if (fullSeason) {
                    getString(R.string.download_season_queued, queued)
                } else {
                    getString(R.string.download_queued, queued)
                }
                Toast.makeText(this@DetailActivity, msg, Toast.LENGTH_SHORT).show()
                if (!fullSeason) {
                    downloadChecked.removeAll(queuedIds.toSet())
                }
            }
            if (queued == 0 && lastError != null) {
                Toast.makeText(
                    this@DetailActivity,
                    getString(R.string.cache_failed, lastError),
                    Toast.LENGTH_LONG,
                ).show()
            }
            submitEpisodes()
            ensureDownloadStatusPolling()
        }
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

    private fun renderSynopsis(value: String) {
        synopsisText = value.trim()
        synopsisExpanded = false
        synopsisExpandable = false
        binding.seriesIntro.maxLines = SYNOPSIS_COLLAPSED_LINES
        binding.seriesIntro.ellipsize = android.text.TextUtils.TruncateAt.END
        binding.seriesIntro.text = if (synopsisText.isBlank()) {
            ""
        } else {
            getString(R.string.synopsis_label, synopsisText)
        }
        binding.seriesIntro.visibility = if (synopsisText.isBlank()) View.GONE else View.VISIBLE
        binding.synopsisExpandToggle.visibility = View.GONE
        binding.synopsisExpandToggle.setText(R.string.synopsis_expand)
        if (synopsisText.isNotBlank()) {
            binding.seriesIntro.post { updateSynopsisExpansionControl() }
        }
    }

    private fun toggleSynopsisExpansion() {
        if (!synopsisExpandable) return
        synopsisExpanded = !synopsisExpanded
        binding.seriesIntro.maxLines =
            if (synopsisExpanded) Integer.MAX_VALUE else SYNOPSIS_COLLAPSED_LINES
        binding.seriesIntro.ellipsize =
            if (synopsisExpanded) null else android.text.TextUtils.TruncateAt.END
        binding.synopsisExpandToggle.setText(
            if (synopsisExpanded) R.string.synopsis_collapse else R.string.synopsis_expand,
        )
        binding.synopsisExpandToggle.contentDescription = getString(
            if (synopsisExpanded) R.string.synopsis_collapse else R.string.synopsis_expand,
        )
        binding.synopsisExpandToggle.visibility = View.VISIBLE
        if (!synopsisExpanded) binding.seriesIntro.post { updateSynopsisExpansionControl() }
    }

    private fun updateSynopsisExpansionControl() {
        if (synopsisExpanded) {
            binding.synopsisExpandToggle.visibility = View.VISIBLE
            return
        }
        val layout = binding.seriesIntro.layout
        val lastLine = (layout?.lineCount ?: 0) - 1
        synopsisExpandable = lastLine >= SYNOPSIS_COLLAPSED_LINES - 1 &&
            layout?.getEllipsisCount(lastLine)?.let { it > 0 } == true
        binding.synopsisExpandToggle.visibility =
            if (synopsisExpandable) View.VISIBLE else View.GONE
    }

    private fun showCoverPreview() {
        val coverUrl = CoverUrls.normalize(cover).ifBlank { cover }
        if (coverUrl.isBlank()) return

        val metrics = resources.displayMetrics
        val padding = WatchUi.dp(this, 12f)
        val dialogWidth = minOf(
            WatchUi.dialogContentWidthPx(this),
            WatchUi.dp(this, COVER_PREVIEW_MAX_WIDTH_DP),
        )
        val imageWidth = (dialogWidth - padding * 2).coerceAtLeast(WatchUi.dp(this, 120f))
        val imageHeight = minOf(
            (imageWidth * COVER_PREVIEW_ASPECT_RATIO).toInt(),
            (metrics.heightPixels * COVER_PREVIEW_MAX_HEIGHT_FRACTION).toInt(),
        ).coerceAtLeast(WatchUi.dp(this, 160f))
        val image = ImageView(this).apply {
            contentDescription = getString(R.string.cover_preview_title, seriesTitle)
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = ContextCompat.getDrawable(this@DetailActivity, R.drawable.bg_cover)
            clipToOutline = true
            load(coverUrl) {
                size(imageWidth, imageHeight)
                crossfade(false)
                transformations(RoundedCornersTransformation(resources.displayMetrics.density * 8f))
                placeholder(R.drawable.bg_cover)
                error(R.drawable.bg_cover)
            }
        }
        val content = FrameLayout(this).apply {
            setPadding(padding, padding, padding, padding)
            setBackgroundResource(R.drawable.bg_alert_dialog)
            addView(
                image,
                FrameLayout.LayoutParams(imageWidth, imageHeight, Gravity.CENTER),
            )
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(
            this,
            R.style.Theme_HongguoWatch_AlertDialog,
        ).setView(content).create()
        image.setOnClickListener { dialog.dismiss() }
        dialog.setOnShowListener {
            dialog.window?.let { window ->
                window.setLayout(dialogWidth, WindowManager.LayoutParams.WRAP_CONTENT)
                window.setBackgroundDrawableResource(R.drawable.bg_alert_dialog)
            }
        }
        dialog.show()
        dialog.window?.let { window ->
            window.setLayout(dialogWidth, WindowManager.LayoutParams.WRAP_CONTENT)
            window.setBackgroundDrawableResource(R.drawable.bg_alert_dialog)
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
            val total = ContentRepository.episodeTotal(episodeCount, episodes.size)
            favorited = repo.toggleFavorite(
                seriesId = seriesId,
                seriesTitle = seriesTitle,
                cover = cover,
                source = source,
                episodeCount = total,
            )
            if (favorited) container.chaseUpdates.seedIfAbsent(seriesId, total)
            else container.chaseUpdates.clear(seriesId)
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

    private fun isWifi(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun openPlayer(episode: Episode, resume: Boolean) {
        lifecycleScope.launch {
            if (container.timeLock.isBlocked()) {
                TimeLockGate.showLockDialog(this@DetailActivity, container.timeLock)
                return@launch
            }
            val position = if (resume && episode.id == resumeEpisodeId) resumePositionMs else 0L
            startActivity(
                Intent(this@DetailActivity, PlayerActivity::class.java).apply {
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
    }

    companion object {
        private const val SYNOPSIS_COLLAPSED_LINES = 3
        private const val COVER_PREVIEW_MAX_WIDTH_DP = 280f
        private const val COVER_PREVIEW_ASPECT_RATIO = 4f / 3f
        private const val COVER_PREVIEW_MAX_HEIGHT_FRACTION = 0.7f

        const val EXTRA_SERIES_ID = "series_id"
        const val EXTRA_SERIES_TITLE = "series_title"
        const val EXTRA_COVER = "cover"
        const val EXTRA_INTRO = "intro"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_TAGS = "tags"
    }
}
