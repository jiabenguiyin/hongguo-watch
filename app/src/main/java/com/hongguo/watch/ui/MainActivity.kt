package com.hongguo.watch.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hongguo.watch.HongguoWatchApp
import com.hongguo.watch.R
import com.hongguo.watch.data.model.HomeCategory
import com.hongguo.watch.data.model.Series
import com.hongguo.watch.data.model.SeriesSource
import com.hongguo.watch.databinding.ActivityMainBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: SeriesListAdapter
    private var mode = Mode.HOME
    private var lastQuery: String = ""
    private var categories: List<HomeCategory> =
        listOf(HomeCategory.recommend()) + HomeCategory.ranks()
    private var categoriesReady: Boolean = false
    private var selectedCategoryId: String = HomeCategory.ID_RECOMMEND
    private var categoryChipViews: List<TextView> = emptyList()
    private var lastRoundLayout: Boolean = false

    /** 首页分类流分页状态（推荐 / 榜单 / 题材）。 */
    private val feedItems = mutableListOf<Series>()
    private var feedPage = 0
    private var feedOffset = 0
    private var feedSession = ""
    private var feedHasMore = false
    private var feedLoadingMore = false
    private var feedGeneration = 0
    private var feedJob: Job? = null
    private var feedRankBase = 0
    /**
     * 列表内容世代：切 Tab / 搜索 / 分类时递增。
     * 防止首页 feed 异步结果污染历史/收藏 adapter。
     */
    private var listGeneration = 0
    private var listJob: Job? = null

    private val container get() = (application as HongguoWatchApp).container
    private val repo get() = container.repository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // 必须在 setContentView 之后：否则系统栏藏不住，底 Tab 会被挤扁
        if (WatchUi.isCompactWatch(this)) WatchUi.applyImmersive(this)
        applyWatchChrome()

        adapter = SeriesListAdapter { openSeries(it) }
        binding.seriesList.layoutManager = LinearLayoutManager(this)
        binding.seriesList.adapter = adapter
        binding.seriesList.itemAnimator = null
        binding.seriesList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                maybeLoadMoreFeed()
            }
        })

        binding.swipeRefresh.setColorSchemeResources(R.color.accent)
        binding.swipeRefresh.setProgressBackgroundColorSchemeResource(R.color.bg_elevated)

        binding.searchButton.setOnClickListener { runSearch() }
        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                runSearch()
                true
            } else {
                false
            }
        }
        binding.swipeRefresh.setOnRefreshListener {
            when (mode) {
                Mode.HOME -> if (lastQuery.isNotBlank()) runSearch() else showHome(forceReload = true)
                Mode.HISTORY -> showHistory()
                Mode.FAVORITES -> showFavorites()
            }
        }
        binding.tabHome.setOnClickListener { showHome() }
        binding.tabHistory.setOnClickListener { showHistory() }
        binding.tabFavorites.setOnClickListener { showFavorites() }
        binding.tabSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        renderCategoryChips(categories)
        showHome()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && WatchUi.isCompactWatch(this)) WatchUi.applyImmersive(this)
    }

    override fun onResume() {
        super.onResume()
        if (WatchUi.isCompactWatch(this)) WatchUi.applyImmersive(this)
        val round = WatchUi.useRoundLayout(this)
        if (round != lastRoundLayout) {
            applyWatchChrome()
            renderCategoryChips(categories)
        }
        when (mode) {
            Mode.HISTORY -> showHistory()
            Mode.FAVORITES -> showFavorites()
            Mode.HOME -> if (lastQuery.isBlank()) showHome()
        }
    }

    /** 圆/方屏边距与空态留白；设置页改圆屏适配后回首页会重算。 */
    private fun applyWatchChrome() {
        lastRoundLayout = WatchUi.useRoundLayout(this)
        val cornerSafe = WatchUi.needsCornerSafeInset(this)
        WatchUi.applySafePadding(binding.mainRoot, extraBottom = 0)
        val listBottom = when {
            lastRoundLayout -> WatchUi.dp(this, 14f)
            cornerSafe -> WatchUi.dp(this, 10f)
            else -> WatchUi.dp(this, 6f)
        }
        binding.seriesList.setPadding(
            binding.seriesList.paddingLeft,
            binding.seriesList.paddingTop,
            binding.seriesList.paddingRight,
            listBottom,
        )
        val emptyPad = when {
            lastRoundLayout -> WatchUi.dp(this, 28f)
            cornerSafe -> WatchUi.dp(this, 22f)
            else -> WatchUi.dp(this, 20f)
        }
        binding.emptyView.setPadding(
            emptyPad,
            binding.emptyView.paddingTop,
            emptyPad,
            binding.emptyView.paddingBottom,
        )
        // 小屏压缩搜索行高度，给列表多挤出一行
        if (WatchUi.isCompactWatch(this)) {
            val h = WatchUi.dp(this, 40f)
            binding.searchInput.layoutParams = binding.searchInput.layoutParams.apply { height = h }
            binding.searchButton.layoutParams = binding.searchButton.layoutParams.apply { height = h }
            binding.searchInput.requestLayout()
            binding.searchButton.requestLayout()
        }
    }

    private fun showHome(forceReload: Boolean = false) {
        mode = Mode.HOME
        highlightTabs(home = true)
        lastQuery = ""
        binding.searchInput.setText("")
        binding.searchRow.visibility = View.VISIBLE
        binding.categoryScroll.visibility = View.VISIBLE
        binding.pageSubtitle.visibility = View.GONE
        binding.brandTitle.text = getString(R.string.app_name)
        adapter.showResumeHint = false
        binding.loading.visibility = View.VISIBLE
        binding.emptyView.visibility = View.GONE
        val token = beginListLoad(clearAdapter = forceReload)
        listJob = lifecycleScope.launch {
            try {
                if (forceReload || !categoriesReady) {
                    categories = repo.homeCategories()
                    if (token != listGeneration || mode != Mode.HOME) return@launch
                    categoriesReady = true
                    if (categories.none { it.id == selectedCategoryId }) {
                        selectedCategoryId = HomeCategory.ID_RECOMMEND
                    }
                    renderCategoryChips(categories)
                }
                if (token != listGeneration || mode != Mode.HOME) return@launch
                loadSelectedCategory(token)
            } catch (e: Exception) {
                if (token != listGeneration || mode != Mode.HOME) return@launch
                adapter.submit(emptyList())
                binding.emptyView.visibility = View.VISIBLE
                binding.emptyView.text = e.message ?: getString(R.string.error_network)
                Toast.makeText(
                    this@MainActivity,
                    e.message ?: getString(R.string.error_network),
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                if (token == listGeneration) {
                    binding.loading.visibility = View.GONE
                    binding.swipeRefresh.isRefreshing = false
                }
            }
        }
    }

    /** 切换列表内容源时调用：作废首页 feed 与进行中的列表协程。 */
    private fun beginListLoad(clearAdapter: Boolean): Int {
        listJob?.cancel()
        resetFeedState()
        val token = ++listGeneration
        if (clearAdapter) {
            adapter.submit(emptyList())
        }
        return token
    }

    private fun resetFeedState() {
        feedJob?.cancel()
        feedGeneration++
        feedItems.clear()
        feedPage = 0
        feedOffset = 0
        feedSession = ""
        feedHasMore = false
        feedLoadingMore = false
        feedRankBase = 0
    }

    private suspend fun loadSelectedCategory(listToken: Int = listGeneration) {
        if (mode != Mode.HOME || listToken != listGeneration) return
        val cat = categories.firstOrNull { it.id == selectedCategoryId }
            ?: HomeCategory.recommend()
        highlightCategoryChips()
        scrollSelectedChipIntoView()
        resetFeedState()
        val feedToken = feedGeneration
        val page = fetchFeedPage(cat, page = 1, offset = 0, sessionId = "")
        if (!canMutateHomeFeed(listToken, feedToken)) return
        applyFeedPage(cat, page, append = false)
        // 首屏若条数太少填不满可视区，用户无法上滑触发续页 → 自动连拉几页
        fillFeedUntilScrollable(cat, listToken, feedToken)
    }

    /**
     * 小屏手表首屏常见只解析出 1～3 条；列表不满时无法 scroll，
     * [maybeLoadMoreFeed] 永远不触发。自动续页直到够滑或没有更多。
     */
    private suspend fun fillFeedUntilScrollable(
        cat: HomeCategory,
        listToken: Int,
        feedToken: Int,
    ) {
        var pages = 0
        while (
            canMutateHomeFeed(listToken, feedToken) &&
            FeedPagingPolicy.shouldContinueInitialFill(feedItems.size, feedHasMore, pages)
        ) {
            pages++
            val page = fetchFeedPage(
                cat,
                page = feedPage + 1,
                offset = feedOffset,
                sessionId = feedSession,
            )
            if (!canMutateHomeFeed(listToken, feedToken)) return
            val before = feedItems.size
            applyFeedPage(cat, page, append = true)
            if (feedItems.size <= before) break
        }
    }

    private fun canMutateHomeFeed(listToken: Int, feedToken: Int): Boolean {
        return mode == Mode.HOME &&
            listToken == listGeneration &&
            feedToken == feedGeneration
    }

    private suspend fun fetchFeedPage(
        cat: HomeCategory,
        page: Int,
        offset: Int,
        sessionId: String,
    ) = when {
        cat.isRecommend -> repo.recommend(page = page, offset = offset, sessionId = sessionId)
        cat.isRank -> repo.rank(
            rankKey = cat.rankKey.orEmpty(),
            page = page,
            offset = offset,
            sessionId = sessionId,
        )
        else -> repo.category(
            filters = cat.filters,
            withSelector = false,
            offset = offset,
            sessionId = sessionId,
        )
    }

    private fun applyFeedPage(
        cat: HomeCategory,
        page: com.hongguo.watch.data.model.CatalogPage,
        append: Boolean,
    ) {
        // 硬闸：离开首页后绝不再写共用 adapter
        if (mode != Mode.HOME) return
        val seen = feedItems.map { it.id }.toHashSet()
        val fresh = page.items.filter { it.id !in seen }
        if (append) {
            val advanced = page.nextOffset > feedOffset
            feedOffset = page.nextOffset.coerceAtLeast(feedOffset)
            feedSession = page.sessionId.ifBlank { feedSession }
            feedPage = page.page
            if (fresh.isEmpty()) {
                // 整页重复：游标能前进则保留 hasMore 以便再试，否则停
                feedHasMore = page.hasMore && advanced
                return
            }
            val startRank = feedRankBase + feedItems.size
            feedItems += fresh
            feedHasMore = page.hasMore
            val rows = if (cat.isRank) {
                fresh.mapIndexed { index, series ->
                    HomeRow.Item(series, rank = startRank + index + 1)
                }
            } else {
                fresh.map { HomeRow.Item(it) }
            }
            adapter.appendItems(rows)
        } else {
            feedItems.clear()
            feedItems += fresh
            feedOffset = page.nextOffset
            feedSession = page.sessionId
            feedPage = page.page
            feedHasMore = page.hasMore
            feedRankBase = 0
            val rows = mutableListOf<HomeRow>()
            rows += HomeRow.Header(
                title = cat.label,
                subtitle = if (cat.isRank) getString(R.string.rank_header_hint) else "",
            )
            rows += if (cat.isRank) {
                fresh.mapIndexed { index, series -> HomeRow.Item(series, rank = index + 1) }
            } else {
                fresh.map { HomeRow.Item(it) }
            }
            adapter.submit(rows)
            val empty = fresh.isEmpty()
            binding.emptyView.visibility = if (empty) View.VISIBLE else View.GONE
            binding.emptyView.text = when {
                !empty -> ""
                cat.isRank -> getString(R.string.empty_rank)
                else -> getString(R.string.empty_category)
            }
        }
    }

    private fun maybeLoadMoreFeed() {
        if (mode != Mode.HOME || lastQuery.isNotBlank()) return
        if (!feedHasMore || feedLoadingMore) return
        val lm = binding.seriesList.layoutManager as? LinearLayoutManager ?: return
        val last = lm.findLastVisibleItemPosition()
        if (!FeedPagingPolicy.shouldPrefetch(last, adapter.itemCount)) return
        loadMoreFeed()
    }

    private fun loadMoreFeed() {
        if (mode != Mode.HOME || !feedHasMore || feedLoadingMore) return
        val cat = categories.firstOrNull { it.id == selectedCategoryId } ?: return
        feedLoadingMore = true
        val listToken = listGeneration
        val feedToken = feedGeneration
        val nextPage = feedPage + 1
        val offset = feedOffset
        val session = feedSession
        feedJob = lifecycleScope.launch {
            try {
                val page = fetchFeedPage(cat, page = nextPage, offset = offset, sessionId = session)
                if (!canMutateHomeFeed(listToken, feedToken)) return@launch
                applyFeedPage(cat, page, append = true)
            } catch (_: Exception) {
                if (canMutateHomeFeed(listToken, feedToken)) {
                    // 续页失败不清空已有列表；短暂停止自动续页，下拉可刷新
                    feedHasMore = false
                }
            } finally {
                if (feedToken == feedGeneration) feedLoadingMore = false
            }
        }
    }

    private fun selectCategory(category: HomeCategory) {
        if (mode != Mode.HOME) return
        if (selectedCategoryId == category.id && lastQuery.isBlank()) return
        selectedCategoryId = category.id
        lastQuery = ""
        binding.searchInput.setText("")
        highlightCategoryChips()
        scrollSelectedChipIntoView()
        binding.loading.visibility = View.VISIBLE
        binding.emptyView.visibility = View.GONE
        val token = beginListLoad(clearAdapter = true)
        listJob = lifecycleScope.launch {
            try {
                if (token != listGeneration || mode != Mode.HOME) return@launch
                loadSelectedCategory(token)
            } catch (e: Exception) {
                if (token != listGeneration || mode != Mode.HOME) return@launch
                adapter.submit(
                    listOf(
                        HomeRow.Header(
                            title = category.label,
                            subtitle = if (category.isRank) getString(R.string.rank_header_hint) else "",
                        ),
                    ),
                )
                binding.emptyView.visibility = View.VISIBLE
                binding.emptyView.text = when {
                    category.isRank -> e.message ?: getString(R.string.empty_rank)
                    else -> e.message ?: getString(R.string.error_network)
                }
            } finally {
                if (token == listGeneration) {
                    binding.loading.visibility = View.GONE
                    binding.swipeRefresh.isRefreshing = false
                }
            }
        }
    }

    private fun renderCategoryChips(items: List<HomeCategory>) {
        binding.categoryRow.removeAllViews()
        val compact = WatchUi.isCompactWatch(this)
        val round = WatchUi.useRoundLayout(this)
        // 圆屏分类条两端留白，避免首尾芯片贴圆弧被裁
        val endPad = if (round) WatchUi.dp(this, 10f) else WatchUi.dp(this, 2f)
        binding.categoryScroll.setPadding(endPad, 0, endPad, 0)
        val padH = if (compact) WatchUi.dp(this, 8f) else resources.getDimensionPixelSize(R.dimen.watch_chip_pad_h)
        val padV = if (compact) WatchUi.dp(this, 5f) else resources.getDimensionPixelSize(R.dimen.watch_chip_pad_v)
        val gap = WatchUi.dp(this, if (compact) 3f else 4f)
        val textSp = if (compact) 10f else 11f
        val chipMinH = resources.getDimensionPixelSize(R.dimen.watch_chip_min_h)
        categoryChipViews = items.map { cat ->
            TextView(this).apply {
                text = cat.label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp)
                setPadding(padH, padV, padH, padV)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_quality_chip)
                isSelected = cat.id == selectedCategoryId
                // 触控热区 ≥36dp，避免圆屏/粗指难点
                minHeight = chipMinH
                gravity = android.view.Gravity.CENTER
                includeFontPadding = false
                setOnClickListener { selectCategory(cat) }
                val lp = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                lp.marginEnd = gap
                binding.categoryRow.addView(this, lp)
            }
        }
        binding.categoryScroll.post { scrollSelectedChipIntoView() }
    }

    private fun highlightCategoryChips() {
        categoryChipViews.forEachIndexed { index, view ->
            val cat = categories.getOrNull(index) ?: return@forEachIndexed
            view.isSelected = cat.id == selectedCategoryId
        }
    }

    private fun scrollSelectedChipIntoView() {
        val index = categories.indexOfFirst { it.id == selectedCategoryId }
        if (index < 0) return
        val child = binding.categoryRow.getChildAt(index) ?: return
        binding.categoryScroll.post {
            val scroll = binding.categoryScroll
            val left = child.left - scroll.paddingLeft
            val right = child.right + scroll.paddingRight
            val visibleLeft = scroll.scrollX
            val visibleRight = visibleLeft + scroll.width
            when {
                left < visibleLeft -> scroll.smoothScrollTo(left, 0)
                right > visibleRight -> scroll.smoothScrollTo(right - scroll.width, 0)
            }
        }
    }

    private fun runSearch() {
        val query = binding.searchInput.text?.toString().orEmpty().trim()
        if (query.isEmpty()) {
            Toast.makeText(this, R.string.search_hint, Toast.LENGTH_SHORT).show()
            return
        }
        hideKeyboard()
        mode = Mode.HOME
        highlightTabs(home = true)
        lastQuery = query
        binding.searchRow.visibility = View.VISIBLE
        binding.categoryScroll.visibility = View.GONE
        binding.pageSubtitle.visibility = View.GONE
        binding.loading.visibility = View.VISIBLE
        binding.emptyView.visibility = View.GONE
        adapter.showResumeHint = false
        val token = beginListLoad(clearAdapter = true)
        listJob = lifecycleScope.launch {
            try {
                val page = repo.search(query, page = 1)
                if (token != listGeneration || mode != Mode.HOME) return@launch
                val rows = mutableListOf<HomeRow>()
                rows += HomeRow.Header(getString(R.string.app_section))
                rows += page.items.map { HomeRow.Item(it) }
                adapter.submit(rows)
                binding.emptyView.visibility = if (page.items.isEmpty()) View.VISIBLE else View.GONE
                binding.emptyView.text =
                    if (page.items.isEmpty()) getString(R.string.empty_search_result) else ""
            } catch (e: Exception) {
                if (token != listGeneration || mode != Mode.HOME) return@launch
                adapter.submit(emptyList())
                binding.emptyView.visibility = View.VISIBLE
                binding.emptyView.text = e.message ?: getString(R.string.error_network)
                Toast.makeText(
                    this@MainActivity,
                    e.message ?: getString(R.string.error_network),
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                if (token == listGeneration) {
                    binding.loading.visibility = View.GONE
                    binding.swipeRefresh.isRefreshing = false
                }
            }
        }
    }

    private fun showHistory() {
        mode = Mode.HISTORY
        highlightTabs(history = true)
        hideKeyboard()
        binding.searchRow.visibility = View.GONE
        binding.categoryScroll.visibility = View.GONE
        binding.pageSubtitle.visibility = View.VISIBLE
        binding.pageSubtitle.text = getString(R.string.history_subtitle)
        binding.brandTitle.text = getString(R.string.tab_history)
        binding.loading.visibility = View.VISIBLE
        binding.emptyView.visibility = View.GONE
        adapter.showResumeHint = true
        // 立刻清空 + 作废首页 feed，避免推荐残留/异步回写
        val token = beginListLoad(clearAdapter = true)
        listJob = lifecycleScope.launch {
            try {
                val history = repo.recentHistory()
                if (token != listGeneration || mode != Mode.HISTORY) return@launch
                val rows = history.map {
                    val percent = if (it.durationMs > 0) {
                        ((it.positionMs * 100) / it.durationMs).toInt().coerceIn(0, 100)
                    } else {
                        0
                    }
                    HomeRow.Item(
                        Series(
                            id = it.seriesId,
                            title = it.seriesTitle,
                            cover = it.cover,
                            description = getString(R.string.progress_meta, it.episodeNumber, percent),
                            episodeCount = it.episodeNumber,
                            source = runCatching { SeriesSource.valueOf(it.source) }
                                .getOrDefault(SeriesSource.APP),
                        ),
                    )
                }
                adapter.submit(rows)
                binding.emptyView.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
                binding.emptyView.text = getString(R.string.empty_history)
            } finally {
                if (token == listGeneration) {
                    binding.loading.visibility = View.GONE
                    binding.swipeRefresh.isRefreshing = false
                }
            }
        }
    }

    private fun showFavorites() {
        mode = Mode.FAVORITES
        highlightTabs(favorites = true)
        hideKeyboard()
        binding.searchRow.visibility = View.GONE
        binding.categoryScroll.visibility = View.GONE
        binding.pageSubtitle.visibility = View.VISIBLE
        binding.pageSubtitle.text = getString(R.string.favorites_subtitle)
        binding.brandTitle.text = getString(R.string.tab_favorites)
        binding.loading.visibility = View.VISIBLE
        binding.emptyView.visibility = View.GONE
        adapter.showResumeHint = false
        val token = beginListLoad(clearAdapter = true)
        listJob = lifecycleScope.launch {
            try {
                val favorites = repo.favorites()
                if (token != listGeneration || mode != Mode.FAVORITES) return@launch
                val rows = favorites.map {
                    HomeRow.Item(
                        Series(
                            id = it.seriesId,
                            title = it.seriesTitle,
                            cover = it.cover,
                            description = if (it.episodeCount > 0) "全${it.episodeCount}集" else "",
                            episodeCount = it.episodeCount,
                            source = runCatching { SeriesSource.valueOf(it.source) }
                                .getOrDefault(SeriesSource.APP),
                        ),
                    )
                }
                adapter.submit(rows)
                binding.emptyView.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
                binding.emptyView.text = getString(R.string.empty_favorites)
            } finally {
                if (token == listGeneration) {
                    binding.loading.visibility = View.GONE
                    binding.swipeRefresh.isRefreshing = false
                }
            }
        }
    }

    private fun openSeries(series: Series) {
        when (mode) {
            Mode.HISTORY -> {
                lifecycleScope.launch {
                    val history = repo.historyFor(series.id)
                    val intent = Intent(this@MainActivity, PlayerActivity::class.java).apply {
                        putExtra(PlayerActivity.EXTRA_SERIES_ID, series.id)
                        putExtra(PlayerActivity.EXTRA_SERIES_TITLE, series.title)
                        putExtra(PlayerActivity.EXTRA_COVER, series.cover)
                        putExtra(PlayerActivity.EXTRA_SOURCE, series.source.name)
                        if (history != null) {
                            putExtra(PlayerActivity.EXTRA_EPISODE_ID, history.episodeId)
                            putExtra(PlayerActivity.EXTRA_EPISODE_NUMBER, history.episodeNumber)
                            putExtra(
                                PlayerActivity.EXTRA_START_POSITION_MS,
                                repo.resumePositionMs(history),
                            )
                        }
                    }
                    startActivity(intent)
                }
            }
            Mode.HOME, Mode.FAVORITES -> {
                startActivity(
                    Intent(this, DetailActivity::class.java).apply {
                        putExtra(DetailActivity.EXTRA_SERIES_ID, series.id)
                        putExtra(DetailActivity.EXTRA_SERIES_TITLE, series.title)
                        putExtra(DetailActivity.EXTRA_COVER, series.cover)
                        putExtra(DetailActivity.EXTRA_INTRO, series.description)
                        putExtra(DetailActivity.EXTRA_SOURCE, series.source.name)
                    },
                )
            }
        }
    }

    private fun highlightTabs(
        home: Boolean = false,
        history: Boolean = false,
        favorites: Boolean = false,
    ) {
        val accent = ContextCompat.getColor(this, R.color.accent)
        val muted = ContextCompat.getColor(this, R.color.text_secondary)
        binding.tabHome.setTextColor(if (home) accent else muted)
        binding.tabHistory.setTextColor(if (history) accent else muted)
        binding.tabFavorites.setTextColor(if (favorites) accent else muted)
        binding.tabSettings.setTextColor(muted)
        binding.tabHome.setBackgroundResource(if (home) R.drawable.bg_tab_active else android.R.color.transparent)
        binding.tabHistory.setBackgroundResource(if (history) R.drawable.bg_tab_active else android.R.color.transparent)
        binding.tabFavorites.setBackgroundResource(if (favorites) R.drawable.bg_tab_active else android.R.color.transparent)
        binding.tabSettings.setBackgroundResource(android.R.color.transparent)
        binding.tabHome.paint.isFakeBoldText = home
        binding.tabHistory.paint.isFakeBoldText = history
        binding.tabFavorites.paint.isFakeBoldText = favorites
        binding.tabHome.invalidate()
        binding.tabHistory.invalidate()
        binding.tabFavorites.invalidate()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
        binding.searchInput.clearFocus()
    }

    private enum class Mode { HOME, HISTORY, FAVORITES }
}
