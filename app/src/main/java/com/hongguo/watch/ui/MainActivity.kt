package com.hongguo.watch.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
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
import kotlin.math.abs

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: SeriesListAdapter
    private var mode = Mode.HOME
    private var librarySegment = LibrarySegment.FAVORITES
    private var homeNav = HomeNav.RECOMMEND
    private var lastQuery: String = ""
    private var categories: List<HomeCategory> =
        listOf(HomeCategory.recommend()) + HomeCategory.ranks()
    private var categoriesReady: Boolean = false
    private var selectedCategoryId: String = HomeCategory.ID_RECOMMEND
    private var categoryChipViews: List<TextView> = emptyList()
    private var visibleChipCategories: List<HomeCategory> = emptyList()
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
     * 防止首页 feed 异步结果污染库（收藏/历史）adapter。
     */
    private var listGeneration = 0
    private var listJob: Job? = null
    /** 当前列表加载指示器；与 [ListLoadingPolicy] 对齐，避免双圈。 */
    private var listLoading = ListLoadingPolicy.Indicator.NONE
    /** 搜索发现品类芯片：全部 / 短剧 / 漫剧 / 爱情 / 古装。 */
    private var searchChipId: String = SEARCH_CHIP_ALL
    private var hotBatchOffset: Int = 0
    private lateinit var librarySegmentGesture: GestureDetector

    private val container get() = (application as HongguoWatchApp).container
    private val repo get() = container.repository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // 必须在 setContentView 之后：否则系统栏藏不住，底 Tab 会被挤扁
        if (WatchUi.isCompactWatch(this)) WatchUi.applyImmersive(this)
        applyWatchChrome()

        adapter = SeriesListAdapter(
            onFavoriteClick = { series, currentlyFavorited ->
                toggleHistoryFavorite(series, currentlyFavorited)
            },
            onHotWordClick = { openSeries(it) },
            onDiscoveryClick = { openSeries(it) },
            onHeaderAction = { refreshHotBatch() },
        ) { openSeries(it) }
        binding.seriesList.layoutManager = LinearLayoutManager(this)
        binding.seriesList.adapter = adapter
        binding.seriesList.itemAnimator = null
        binding.seriesList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                maybeLoadMoreFeed()
            }
        })
        setupLibrarySegmentSwipe()

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
            // 用户下拉：只用 SwipeRefresh 圈，立刻藏中心 ProgressBar
            applyListLoading(ListLoadingPolicy.Indicator.REFRESH)
            when (mode) {
                Mode.HOME -> when {
                    homeNav == HomeNav.SEARCH && lastQuery.isNotBlank() -> runSearch(fromSwipe = true)
                    homeNav == HomeNav.SEARCH -> showSearchDiscovery(fromSwipe = true)
                    else -> showHome(forceReload = true, fromSwipe = true)
                }
                Mode.LIBRARY -> showLibrary(fromSwipe = true)
            }
        }
        binding.navRecommend.setOnClickListener { selectHomeNav(HomeNav.RECOMMEND) }
        binding.navRank.setOnClickListener { selectHomeNav(HomeNav.RANK) }
        binding.navCategory.setOnClickListener { selectHomeNav(HomeNav.CATEGORY) }
        binding.navSearch.setOnClickListener { selectHomeNav(HomeNav.SEARCH) }
        binding.tabHome.setOnClickListener { showHome() }
        binding.tabLibrary.setOnClickListener { showLibrary() }
        binding.tabSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.segmentFavorites.setOnClickListener {
            showLibrary(segment = LibrarySegment.FAVORITES)
        }
        binding.segmentHistory.setOnClickListener {
            showLibrary(segment = LibrarySegment.HISTORY)
        }

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
            if (mode == Mode.HOME) renderSubChips()
        }
        when (mode) {
            Mode.LIBRARY -> showLibrary()
            Mode.HOME -> if (homeNav != HomeNav.SEARCH || lastQuery.isBlank()) {
                if (homeNav != HomeNav.SEARCH) showHome()
            }
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
        if (WatchUi.isCompactWatch(this)) {
            val h = WatchUi.dp(this, 40f)
            binding.searchInput.layoutParams = binding.searchInput.layoutParams.apply { height = h }
            binding.searchButton.layoutParams = binding.searchButton.layoutParams.apply { height = h }
            binding.searchInput.requestLayout()
            binding.searchButton.requestLayout()
        }
    }

    private fun selectHomeNav(nav: HomeNav) {
        if (mode != Mode.HOME) {
            mode = Mode.HOME
            highlightBottomTabs(home = true)
        }
        if (homeNav == nav && lastQuery.isBlank() && nav != HomeNav.SEARCH) return
        homeNav = nav
        lastQuery = ""
        binding.searchInput.setText("")
        applyHomeChrome()
        highlightHomeNav()
        when (nav) {
            HomeNav.SEARCH -> {
                adapter.showResumeHint = false
                binding.librarySegmentRow.visibility = View.GONE
                binding.searchInput.requestFocus()
                showSearchDiscovery()
            }
            HomeNav.RECOMMEND, HomeNav.RANK, HomeNav.CATEGORY -> {
                selectedCategoryId = HomeNavPolicy.resolveSelectedCategoryId(
                    nav,
                    selectedCategoryId,
                    categories,
                )
                renderSubChips()
                binding.emptyView.visibility = View.GONE
                applyListLoading(ListLoadingPolicy.Indicator.CENTER)
                val token = beginListLoad(clearAdapter = true)
                adapter.showResumeHint = false
                listJob = lifecycleScope.launch {
                    try {
                        ensureCategories(token)
                        if (token != listGeneration || mode != Mode.HOME) return@launch
                        selectedCategoryId = HomeNavPolicy.resolveSelectedCategoryId(
                            homeNav,
                            selectedCategoryId,
                            categories,
                        )
                        renderSubChips()
                        if (homeNav == HomeNav.CATEGORY &&
                            HomeNavPolicy.genreCategories(categories).isEmpty()
                        ) {
                            adapter.submit(emptyList())
                            binding.emptyView.visibility = View.VISIBLE
                            binding.emptyView.text = getString(R.string.empty_category)
                            return@launch
                        }
                        loadSelectedCategory(token)
                    } catch (e: Exception) {
                        if (token != listGeneration || mode != Mode.HOME) return@launch
                        showHomeError(e)
                    } finally {
                        if (token == listGeneration) {
                            applyListLoading(ListLoadingPolicy.Indicator.NONE)
                        }
                    }
                }
            }
        }
    }

    private fun showHome(forceReload: Boolean = false, fromSwipe: Boolean = false) {
        val switchingToHome = mode != Mode.HOME
        mode = Mode.HOME
        highlightBottomTabs(home = true)
        if (homeNav == HomeNav.SEARCH && !forceReload && !switchingToHome) {
            applyHomeChrome()
            highlightHomeNav()
            return
        }
        if (homeNav == HomeNav.SEARCH) {
            homeNav = HomeNav.RECOMMEND
        }
        lastQuery = ""
        binding.searchInput.setText("")
        applyHomeChrome()
        highlightHomeNav()
        binding.brandTitle.text = getString(R.string.app_name)
        binding.emptyView.visibility = View.GONE
        val clear = ListTabPolicy.shouldClearHomeAdapter(switchingToHome, forceReload)
        val hadContent = adapter.itemCount > 0
        applyListLoading(
            ListLoadingPolicy.choose(
                swipeRefreshing = fromSwipe || binding.swipeRefresh.isRefreshing,
                clearingList = clear,
                hasVisibleContent = hadContent && !clear,
            ),
        )
        val token = beginListLoad(clearAdapter = clear)
        adapter.showResumeHint = false
        if (clear) binding.seriesList.scrollToPosition(0)
        listJob = lifecycleScope.launch {
            try {
                ensureCategories(token, force = forceReload)
                if (token != listGeneration || mode != Mode.HOME) return@launch
                selectedCategoryId = HomeNavPolicy.resolveSelectedCategoryId(
                    homeNav,
                    selectedCategoryId,
                    categories,
                )
                renderSubChips()
                loadSelectedCategory(token)
            } catch (e: Exception) {
                if (token != listGeneration || mode != Mode.HOME) return@launch
                showHomeError(e)
            } finally {
                if (token == listGeneration) {
                    applyListLoading(ListLoadingPolicy.Indicator.NONE)
                }
            }
        }
    }

    private suspend fun ensureCategories(token: Int, force: Boolean = false) {
        if (!force && categoriesReady) return
        categories = repo.homeCategories()
        if (token != listGeneration || mode != Mode.HOME) return
        categoriesReady = true
        if (categories.none { it.id == selectedCategoryId }) {
            selectedCategoryId = HomeCategory.ID_RECOMMEND
        }
    }

    private fun showHomeError(e: Exception) {
        adapter.submit(emptyList())
        binding.emptyView.visibility = View.VISIBLE
        binding.emptyView.text = e.message ?: getString(R.string.error_network)
        Toast.makeText(
            this,
            e.message ?: getString(R.string.error_network),
            Toast.LENGTH_LONG,
        ).show()
    }

    /** 同一时刻只亮一种加载指示器（中心圈 / 下拉圈 / 无）。 */
    private fun applyListLoading(indicator: ListLoadingPolicy.Indicator) {
        listLoading = indicator
        when (indicator) {
            ListLoadingPolicy.Indicator.NONE -> {
                binding.loading.visibility = View.GONE
                if (binding.swipeRefresh.isRefreshing) {
                    binding.swipeRefresh.isRefreshing = false
                }
            }
            ListLoadingPolicy.Indicator.CENTER -> {
                binding.loading.visibility = View.VISIBLE
                if (binding.swipeRefresh.isRefreshing) {
                    binding.swipeRefresh.isRefreshing = false
                }
            }
            ListLoadingPolicy.Indicator.REFRESH -> {
                binding.loading.visibility = View.GONE
                if (!binding.swipeRefresh.isRefreshing) {
                    binding.swipeRefresh.isRefreshing = true
                }
            }
        }
    }

    /** 列表已有内容时藏中心遮罩，避免「有行还叠大圈」。 */
    private fun hideCenterLoadingIfContentVisible() {
        if (adapter.itemCount <= 0) return
        val next = ListLoadingPolicy.afterContentShown(listLoading)
        if (next != listLoading) applyListLoading(next)
    }

    private fun applyHomeChrome() {
        val home = mode == Mode.HOME
        binding.homeNavScroll.visibility =
            if (HomeNavPolicy.showHomeNavChrome(home)) View.VISIBLE else View.GONE
        binding.brandRow.visibility = View.VISIBLE
        binding.librarySegmentRow.visibility = View.GONE
        binding.searchRow.visibility =
            if (home && HomeNavPolicy.showSearchRow(homeNav)) View.VISIBLE else View.GONE
        binding.categoryScroll.visibility =
            if (home && (HomeNavPolicy.showSubChips(homeNav) || homeNav == HomeNav.SEARCH)) {
                View.VISIBLE
            } else {
                View.GONE
            }
        binding.pageSubtitle.visibility = View.GONE
        if (home && homeNav == HomeNav.SEARCH) {
            renderSearchChips()
        }
    }

    private fun highlightHomeNav() {
        val active = ContextCompat.getColor(this, R.color.text_primary)
        val muted = ContextCompat.getColor(this, R.color.text_secondary)
        fun style(view: TextView, selected: Boolean) {
            view.setTextColor(if (selected) active else muted)
            view.setBackgroundResource(
                if (selected) R.drawable.bg_top_tab_active else R.drawable.bg_top_tab_idle,
            )
            view.paint.isFakeBoldText = selected
            view.invalidate()
        }
        style(binding.navRecommend, homeNav == HomeNav.RECOMMEND)
        style(binding.navRank, homeNav == HomeNav.RANK)
        style(binding.navCategory, homeNav == HomeNav.CATEGORY)
        style(binding.navSearch, homeNav == HomeNav.SEARCH)
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
        if (homeNav == HomeNav.SEARCH) return
        val cat = categories.firstOrNull { it.id == selectedCategoryId }
            ?: when (homeNav) {
                HomeNav.RANK -> HomeNavPolicy.rankCategories(categories).firstOrNull()
                HomeNav.CATEGORY -> HomeNavPolicy.genreCategories(categories).firstOrNull()
                else -> HomeCategory.recommend()
            }
            ?: HomeCategory.recommend()
        selectedCategoryId = cat.id
        highlightCategoryChips()
        scrollSelectedChipIntoView()
        resetFeedState()
        val feedToken = feedGeneration
        val page = fetchFeedPage(cat, page = 1, offset = 0, sessionId = "")
        if (!canMutateHomeFeed(listToken, feedToken)) return
        applyFeedPage(cat, page, append = false)
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
            homeNav != HomeNav.SEARCH &&
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
        if (mode != Mode.HOME || homeNav == HomeNav.SEARCH) return
        val seen = feedItems.map { it.id }.toHashSet()
        val fresh = page.items.filter { it.id !in seen }
        if (append) {
            val advanced = page.nextOffset > feedOffset
            feedOffset = page.nextOffset.coerceAtLeast(feedOffset)
            feedSession = page.sessionId.ifBlank { feedSession }
            feedPage = page.page
            if (fresh.isEmpty()) {
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
                // 推荐 Banner 已占首条：续页全部进列表
                fresh.map { HomeRow.Item(it) }
            }
            adapter.appendItems(rows)
            hideCenterLoadingIfContentVisible()
        } else {
            feedItems.clear()
            feedItems += fresh
            feedOffset = page.nextOffset
            feedSession = page.sessionId
            feedPage = page.page
            feedHasMore = page.hasMore
            feedRankBase = 0
            val rows = mutableListOf<HomeRow>()
            if (cat.isRecommend) {
                val banners = HomeNavPolicy.bannerSeriesList(fresh)
                if (banners.isNotEmpty()) {
                    rows += HomeRow.Banner(banners, pageIndex = 0)
                }
                rows += HomeRow.Header(
                    title = getString(R.string.section_for_you),
                    showMore = true,
                )
                rows += HomeNavPolicy.listSeriesAfterBanner(fresh, banners.size)
                    .map { HomeRow.Item(it) }
            } else {
                rows += HomeRow.Header(
                    title = cat.label,
                    subtitle = if (cat.isRank) getString(R.string.rank_header_hint) else "",
                )
                rows += if (cat.isRank) {
                    fresh.mapIndexed { index, series -> HomeRow.Item(series, rank = index + 1) }
                } else {
                    fresh.map { HomeRow.Item(it) }
                }
            }
            adapter.submit(rows)
            binding.seriesList.scrollToPosition(0)
            val empty = fresh.isEmpty()
            binding.emptyView.visibility = if (empty) View.VISIBLE else View.GONE
            binding.emptyView.text = when {
                !empty -> ""
                cat.isRank -> getString(R.string.empty_rank)
                else -> getString(R.string.empty_category)
            }
            if (!empty) hideCenterLoadingIfContentVisible()
        }
    }

    private fun maybeLoadMoreFeed() {
        if (mode != Mode.HOME || homeNav == HomeNav.SEARCH || lastQuery.isNotBlank()) return
        if (!feedHasMore || feedLoadingMore) return
        val lm = binding.seriesList.layoutManager as? LinearLayoutManager ?: return
        val last = lm.findLastVisibleItemPosition()
        if (!FeedPagingPolicy.shouldPrefetch(last, adapter.itemCount)) return
        loadMoreFeed()
    }

    private fun loadMoreFeed() {
        if (mode != Mode.HOME || homeNav == HomeNav.SEARCH || !feedHasMore || feedLoadingMore) return
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
                    feedHasMore = false
                }
            } finally {
                if (feedToken == feedGeneration) feedLoadingMore = false
            }
        }
    }

    private fun selectCategory(category: HomeCategory) {
        if (mode != Mode.HOME || homeNav == HomeNav.SEARCH) return
        if (selectedCategoryId == category.id && lastQuery.isBlank()) return
        selectedCategoryId = category.id
        lastQuery = ""
        binding.searchInput.setText("")
        highlightCategoryChips()
        scrollSelectedChipIntoView()
        binding.emptyView.visibility = View.GONE
        applyListLoading(ListLoadingPolicy.Indicator.CENTER)
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
                    applyListLoading(ListLoadingPolicy.Indicator.NONE)
                }
            }
        }
    }

    private fun renderSubChips() {
        visibleChipCategories = HomeNavPolicy.chipsFor(homeNav, categories)
        binding.categoryRow.removeAllViews()
        if (visibleChipCategories.isEmpty()) {
            categoryChipViews = emptyList()
            binding.categoryScroll.visibility = View.GONE
            return
        }
        binding.categoryScroll.visibility = View.VISIBLE
        val compact = WatchUi.isCompactWatch(this)
        val round = WatchUi.useRoundLayout(this)
        val endPad = if (round) WatchUi.dp(this, 10f) else WatchUi.dp(this, 2f)
        binding.categoryScroll.setPadding(endPad, 0, endPad, 0)
        val padH = if (compact) WatchUi.dp(this, 8f) else resources.getDimensionPixelSize(R.dimen.watch_chip_pad_h)
        val padV = if (compact) WatchUi.dp(this, 5f) else resources.getDimensionPixelSize(R.dimen.watch_chip_pad_v)
        val gap = WatchUi.dp(this, if (compact) 3f else 4f)
        val textSp = if (compact) 10f else 11f
        val chipMinH = resources.getDimensionPixelSize(R.dimen.watch_chip_min_h)
        categoryChipViews = visibleChipCategories.map { cat ->
            TextView(this).apply {
                text = cat.label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp)
                setPadding(padH, padV, padH, padV)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_quality_chip)
                isSelected = cat.id == selectedCategoryId
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
            val cat = visibleChipCategories.getOrNull(index) ?: return@forEachIndexed
            view.isSelected = cat.id == selectedCategoryId
        }
    }

    private fun scrollSelectedChipIntoView() {
        val index = visibleChipCategories.indexOfFirst { it.id == selectedCategoryId }
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

    private fun runSearch(fromSwipe: Boolean = false) {
        val query = binding.searchInput.text?.toString().orEmpty().trim()
        if (query.isEmpty()) {
            showSearchDiscovery(fromSwipe = fromSwipe)
            return
        }
        hideKeyboard()
        mode = Mode.HOME
        homeNav = HomeNav.SEARCH
        highlightBottomTabs(home = true)
        highlightHomeNav()
        lastQuery = query
        applyHomeChrome()
        binding.searchRow.visibility = View.VISIBLE
        binding.emptyView.visibility = View.GONE
        applyListLoading(
            ListLoadingPolicy.choose(
                swipeRefreshing = fromSwipe || binding.swipeRefresh.isRefreshing,
                clearingList = true,
                hasVisibleContent = false,
            ),
        )
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
                    applyListLoading(ListLoadingPolicy.Indicator.NONE)
                }
            }
        }
    }

    private fun showSearchDiscovery(fromSwipe: Boolean = false) {
        mode = Mode.HOME
        homeNav = HomeNav.SEARCH
        lastQuery = ""
        highlightBottomTabs(home = true)
        highlightHomeNav()
        applyHomeChrome()
        binding.searchRow.visibility = View.VISIBLE
        binding.emptyView.visibility = View.GONE
        adapter.showResumeHint = false
        applyListLoading(
            ListLoadingPolicy.choose(
                swipeRefreshing = fromSwipe || binding.swipeRefresh.isRefreshing,
                clearingList = true,
                hasVisibleContent = false,
            ),
        )
        val token = beginListLoad(clearAdapter = true)
        listJob = lifecycleScope.launch {
            try {
                ensureCategories(token)
                if (token != listGeneration || mode != Mode.HOME || homeNav != HomeNav.SEARCH) {
                    return@launch
                }
                renderSearchChips()
                val rankKey = rankKeyForSearchChip(searchChipId)
                val hotPage = repo.rank(
                    rankKey = rankKey,
                    page = 1,
                    offset = hotBatchOffset,
                    sessionId = "",
                )
                val discoverPage = when (searchChipId) {
                    SEARCH_CHIP_COMIC -> repo.rank(
                        rankKey = "hot-comic-drama",
                        page = 1,
                        offset = 0,
                        sessionId = "",
                    )
                    SEARCH_CHIP_ROMANCE, SEARCH_CHIP_COSTUME -> {
                        val genre = genreCategoryForChip(searchChipId)
                        if (genre != null) {
                            repo.category(
                                filters = genre.filters,
                                withSelector = false,
                                offset = 0,
                                sessionId = "",
                            )
                        } else {
                            repo.recommend(page = 1, offset = 0, sessionId = "")
                        }
                    }
                    else -> repo.recommend(page = 1, offset = 0, sessionId = "")
                }
                if (token != listGeneration || mode != Mode.HOME || homeNav != HomeNav.SEARCH) {
                    return@launch
                }
                val hotItems = hotPage.items.take(8)
                val discoverItems = discoverPage.items.take(12)
                val rows = mutableListOf<HomeRow>()
                rows += HomeRow.Header(
                    title = getString(R.string.hot_search),
                    actionLabel = getString(R.string.hot_search_refresh),
                    onAction = { refreshHotBatch() },
                )
                rows += hotItems.mapIndexed { index, series ->
                    HomeRow.SearchHotWord(
                        rank = index + 1 + (hotBatchOffset % 50).coerceAtMost(20),
                        title = series.title,
                        series = series,
                    )
                }
                if (discoverItems.isNotEmpty()) {
                    rows += HomeRow.Header(title = getString(R.string.discovery_picks))
                    rows += HomeRow.SearchDiscoveryStrip(discoverItems)
                }
                adapter.submit(rows)
                val empty = rows.none { it is HomeRow.SearchHotWord || it is HomeRow.SearchDiscoveryStrip }
                binding.emptyView.visibility = if (empty) View.VISIBLE else View.GONE
                binding.emptyView.text = getString(R.string.empty_search)
            } catch (e: Exception) {
                if (token != listGeneration || mode != Mode.HOME) return@launch
                adapter.submit(emptyList())
                binding.emptyView.visibility = View.VISIBLE
                binding.emptyView.text = e.message ?: getString(R.string.error_network)
            } finally {
                if (token == listGeneration) {
                    applyListLoading(ListLoadingPolicy.Indicator.NONE)
                }
            }
        }
    }

    private fun refreshHotBatch() {
        if (mode != Mode.HOME || homeNav != HomeNav.SEARCH) return
        hotBatchOffset += 8
        showSearchDiscovery()
    }

    private fun renderSearchChips() {
        val chips = listOf(
            SEARCH_CHIP_ALL to getString(R.string.chip_all),
            SEARCH_CHIP_SHORT to getString(R.string.chip_short),
            SEARCH_CHIP_COMIC to getString(R.string.chip_comic),
            SEARCH_CHIP_ROMANCE to getString(R.string.chip_romance),
            SEARCH_CHIP_COSTUME to getString(R.string.chip_costume),
        )
        binding.categoryRow.removeAllViews()
        binding.categoryScroll.visibility = View.VISIBLE
        val compact = WatchUi.isCompactWatch(this)
        val padH = if (compact) WatchUi.dp(this, 8f) else resources.getDimensionPixelSize(R.dimen.watch_chip_pad_h)
        val padV = if (compact) WatchUi.dp(this, 5f) else resources.getDimensionPixelSize(R.dimen.watch_chip_pad_v)
        val gap = WatchUi.dp(this, if (compact) 3f else 4f)
        val textSp = if (compact) 10f else 11f
        val chipMinH = resources.getDimensionPixelSize(R.dimen.watch_chip_min_h)
        chips.forEach { (id, label) ->
            val view = TextView(this).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp)
                setPadding(padH, padV, padH, padV)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_quality_chip)
                isSelected = id == searchChipId
                minHeight = chipMinH
                gravity = android.view.Gravity.CENTER
                includeFontPadding = false
                setOnClickListener {
                    if (searchChipId == id) return@setOnClickListener
                    searchChipId = id
                    hotBatchOffset = 0
                    renderSearchChips()
                    showSearchDiscovery()
                }
                val lp = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                lp.marginEnd = gap
                binding.categoryRow.addView(this, lp)
            }
        }
        categoryChipViews = emptyList()
        visibleChipCategories = emptyList()
    }

    private fun rankKeyForSearchChip(chipId: String): String = when (chipId) {
        SEARCH_CHIP_COMIC -> "hot-comic-drama"
        SEARCH_CHIP_SHORT -> "hot-real-drama"
        else -> "hot-drama"
    }

    private fun genreCategoryForChip(chipId: String): HomeCategory? {
        val needle = when (chipId) {
            SEARCH_CHIP_ROMANCE -> "爱情"
            SEARCH_CHIP_COSTUME -> "古装"
            else -> return null
        }
        return HomeNavPolicy.genreCategories(categories).firstOrNull { it.label.contains(needle) }
    }

    private fun showLibrary(
        fromSwipe: Boolean = false,
        segment: LibrarySegment = librarySegment,
    ) {
        mode = Mode.LIBRARY
        librarySegment = segment
        highlightBottomTabs(library = true)
        hideKeyboard()
        binding.homeNavScroll.visibility = View.GONE
        binding.searchRow.visibility = View.GONE
        binding.categoryScroll.visibility = View.GONE
        binding.pageSubtitle.visibility = View.GONE
        binding.librarySegmentRow.visibility = View.VISIBLE
        highlightLibrarySegment(librarySegment)
        binding.brandTitle.text = getString(R.string.tab_library)
        when (librarySegment) {
            LibrarySegment.HISTORY -> loadHistoryList(fromSwipe)
            LibrarySegment.FAVORITES -> loadFavoritesList(fromSwipe)
        }
    }

    private fun loadHistoryList(fromSwipe: Boolean) {
        binding.emptyView.visibility = View.GONE
        applyListLoading(
            ListLoadingPolicy.choose(
                swipeRefreshing = fromSwipe || binding.swipeRefresh.isRefreshing,
                clearingList = true,
                hasVisibleContent = false,
            ),
        )
        val token = beginListLoad(clearAdapter = true)
        adapter.showResumeHint = true
        binding.seriesList.scrollToPosition(0)
        listJob = lifecycleScope.launch {
            try {
                val history = repo.recentHistory()
                if (token != listGeneration || mode != Mode.LIBRARY ||
                    librarySegment != LibrarySegment.HISTORY
                ) {
                    return@launch
                }
                val favoritedIds = repo.favorites().map { it.seriesId }.toHashSet()
                val rows = history.map {
                    val percent = if (it.durationMs > 0) {
                        ((it.positionMs * 100) / it.durationMs).toInt().coerceIn(0, 100)
                    } else {
                        0
                    }
                    HomeRow.Item(
                        series = Series(
                            id = it.seriesId,
                            title = it.seriesTitle,
                            cover = com.hongguo.watch.data.CoverUrls.normalize(it.cover),
                            description = "",
                            episodeCount = 0,
                            source = runCatching { SeriesSource.valueOf(it.source) }
                                .getOrDefault(SeriesSource.APP),
                        ),
                        favorited = it.seriesId in favoritedIds,
                        progressPercent = percent,
                        watchedEpisode = it.episodeNumber,
                        totalEpisodes = null,
                    )
                }
                if (token != listGeneration || mode != Mode.LIBRARY ||
                    librarySegment != LibrarySegment.HISTORY
                ) {
                    return@launch
                }
                adapter.submit(rows)
                binding.seriesList.scrollToPosition(0)
                binding.emptyView.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
                binding.emptyView.text = getString(R.string.empty_history)
            } finally {
                if (token == listGeneration) {
                    applyListLoading(ListLoadingPolicy.Indicator.NONE)
                }
            }
        }
    }

    /** 历史行 ♡：一键收藏/取消，不打断续播入口。总集数走详情接口，不用进度集号。 */
    private fun toggleHistoryFavorite(series: Series, @Suppress("UNUSED_PARAMETER") currentlyFavorited: Boolean) {
        if (mode != Mode.LIBRARY || librarySegment != LibrarySegment.HISTORY) return
        lifecycleScope.launch {
            val totalEpisodes = runCatching { repo.resolveEpisodeTotal(series.id) }.getOrDefault(0)
            val nowFavorited = repo.toggleFavorite(
                seriesId = series.id,
                seriesTitle = series.title,
                cover = series.cover,
                source = series.source,
                episodeCount = totalEpisodes,
            )
            if (mode != Mode.LIBRARY || librarySegment != LibrarySegment.HISTORY) return@launch
            adapter.setFavorited(series.id, nowFavorited)
            Toast.makeText(
                this@MainActivity,
                if (nowFavorited) R.string.favorite_added else R.string.favorite_removed,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun loadFavoritesList(fromSwipe: Boolean) {
        binding.emptyView.visibility = View.GONE
        applyListLoading(
            ListLoadingPolicy.choose(
                swipeRefreshing = fromSwipe || binding.swipeRefresh.isRefreshing,
                clearingList = true,
                hasVisibleContent = false,
            ),
        )
        val token = beginListLoad(clearAdapter = true)
        adapter.showResumeHint = false
        binding.seriesList.scrollToPosition(0)
        listJob = lifecycleScope.launch {
            try {
                val favorites = repo.favoritesWithResolvedEpisodeTotals()
                if (token != listGeneration || mode != Mode.LIBRARY ||
                    librarySegment != LibrarySegment.FAVORITES
                ) {
                    return@launch
                }
                val historyMap = repo.recentHistory().associateBy { it.seriesId }
                val rows = favorites.map { fav ->
                    val history = historyMap[fav.seriesId]
                    val percent = if (history != null && history.durationMs > 0) {
                        ((history.positionMs * 100) / history.durationMs).toInt().coerceIn(0, 100)
                    } else if (history != null && fav.episodeCount > 0) {
                        ((history.episodeNumber * 100) / fav.episodeCount).coerceIn(0, 100)
                    } else {
                        null
                    }
                    HomeRow.Item(
                        series = Series(
                            id = fav.seriesId,
                            title = fav.seriesTitle,
                            cover = com.hongguo.watch.data.CoverUrls.normalize(fav.cover),
                            description = "",
                            episodeCount = fav.episodeCount,
                            source = runCatching { SeriesSource.valueOf(fav.source) }
                                .getOrDefault(SeriesSource.APP),
                        ),
                        progressPercent = percent,
                        watchedEpisode = history?.episodeNumber,
                        totalEpisodes = fav.episodeCount.takeIf { it > 0 },
                    )
                }
                if (token != listGeneration || mode != Mode.LIBRARY ||
                    librarySegment != LibrarySegment.FAVORITES
                ) {
                    return@launch
                }
                adapter.submit(rows)
                binding.seriesList.scrollToPosition(0)
                binding.emptyView.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
                binding.emptyView.text = getString(R.string.empty_favorites)
            } finally {
                if (token == listGeneration) {
                    applyListLoading(ListLoadingPolicy.Indicator.NONE)
                }
            }
        }
    }

    private fun highlightLibrarySegment(segment: LibrarySegment) {
        val favorites = segment == LibrarySegment.FAVORITES
        val history = segment == LibrarySegment.HISTORY
        val active = ContextCompat.getColor(this, R.color.text_primary)
        val muted = ContextCompat.getColor(this, R.color.text_secondary)
        binding.segmentFavorites.setTextColor(if (favorites) active else muted)
        binding.segmentHistory.setTextColor(if (history) active else muted)
        binding.segmentFavorites.setBackgroundResource(
            if (favorites) R.drawable.bg_top_tab_active else R.drawable.bg_top_tab_idle,
        )
        binding.segmentHistory.setBackgroundResource(
            if (history) R.drawable.bg_top_tab_active else R.drawable.bg_top_tab_idle,
        )
        binding.segmentFavorites.paint.isFakeBoldText = favorites
        binding.segmentHistory.paint.isFakeBoldText = history
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupLibrarySegmentSwipe() {
        librarySegmentGesture = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = mode == Mode.LIBRARY

                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float,
                ): Boolean {
                    if (mode != Mode.LIBRARY) return false
                    if (abs(velocityX) < abs(velocityY) || abs(velocityX) < 800f) return false
                    // 左滑 → 观看历史；右滑 → 我的收藏（与分段左右顺序一致）
                    val target = if (velocityX < 0) {
                        LibrarySegment.HISTORY
                    } else {
                        LibrarySegment.FAVORITES
                    }
                    if (target == librarySegment) return false
                    showLibrary(segment = target)
                    return true
                }
            },
        )
        binding.seriesList.setOnTouchListener { _, event ->
            if (mode != Mode.LIBRARY) return@setOnTouchListener false
            librarySegmentGesture.onTouchEvent(event)
            false
        }
        binding.librarySegmentRow.setOnTouchListener { _, event ->
            if (mode != Mode.LIBRARY) return@setOnTouchListener false
            librarySegmentGesture.onTouchEvent(event)
            false
        }
    }

    private fun openSeries(series: Series) {
        when {
            mode == Mode.LIBRARY && librarySegment == LibrarySegment.HISTORY -> {
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
            else -> {
                startActivity(
                    Intent(this, DetailActivity::class.java).apply {
                        putExtra(DetailActivity.EXTRA_SERIES_ID, series.id)
                        putExtra(DetailActivity.EXTRA_SERIES_TITLE, series.title)
                        putExtra(DetailActivity.EXTRA_COVER, series.cover)
                        putExtra(DetailActivity.EXTRA_INTRO, series.description)
                        putExtra(DetailActivity.EXTRA_SOURCE, series.source.name)
                        putStringArrayListExtra(
                            DetailActivity.EXTRA_TAGS,
                            ArrayList(series.tags),
                        )
                    },
                )
            }
        }
    }

    private fun highlightBottomTabs(
        home: Boolean = false,
        library: Boolean = false,
    ) {
        val accent = ContextCompat.getColor(this, R.color.accent)
        val muted = ContextCompat.getColor(this, R.color.text_secondary)
        binding.tabHome.setTextColor(if (home) accent else muted)
        binding.tabLibrary.setTextColor(if (library) accent else muted)
        binding.tabSettings.setTextColor(muted)
        binding.tabHome.setBackgroundResource(
            if (home) R.drawable.bg_tab_active else android.R.color.transparent,
        )
        binding.tabLibrary.setBackgroundResource(
            if (library) R.drawable.bg_tab_active else android.R.color.transparent,
        )
        binding.tabSettings.setBackgroundResource(android.R.color.transparent)
        binding.tabHome.paint.isFakeBoldText = home
        binding.tabLibrary.paint.isFakeBoldText = library
        binding.tabHome.invalidate()
        binding.tabLibrary.invalidate()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
        binding.searchInput.clearFocus()
    }

    private enum class Mode { HOME, LIBRARY }

    private enum class LibrarySegment { FAVORITES, HISTORY }

    companion object {
        private const val SEARCH_CHIP_ALL = "all"
        private const val SEARCH_CHIP_SHORT = "short"
        private const val SEARCH_CHIP_COMIC = "comic"
        private const val SEARCH_CHIP_ROMANCE = "romance"
        private const val SEARCH_CHIP_COSTUME = "costume"
    }
}
