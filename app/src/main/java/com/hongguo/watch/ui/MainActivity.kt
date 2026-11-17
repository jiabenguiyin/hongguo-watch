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
import com.hongguo.watch.HongguoWatchApp
import com.hongguo.watch.R
import com.hongguo.watch.data.model.HomeCategory
import com.hongguo.watch.data.model.Series
import com.hongguo.watch.data.model.SeriesSource
import com.hongguo.watch.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: SeriesListAdapter
    private var mode = Mode.HOME
    private var lastQuery: String = ""
    private var categories: List<HomeCategory> = listOf(HomeCategory.recommend())
    private var selectedCategoryId: String = HomeCategory.ID_RECOMMEND
    private var categoryChipViews: List<TextView> = emptyList()

    private val container get() = (application as HongguoWatchApp).container
    private val repo get() = container.repository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WatchUi.applySafePadding(binding.mainRoot, extraBottom = 0)

        adapter = SeriesListAdapter { openSeries(it) }
        binding.seriesList.layoutManager = LinearLayoutManager(this)
        binding.seriesList.adapter = adapter
        binding.seriesList.itemAnimator = null

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

    override fun onResume() {
        super.onResume()
        when (mode) {
            Mode.HISTORY -> showHistory()
            Mode.FAVORITES -> showFavorites()
            Mode.HOME -> if (lastQuery.isBlank()) showHome()
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
        lifecycleScope.launch {
            try {
                if (forceReload || categories.size <= 1) {
                    categories = repo.homeCategories()
                    if (categories.none { it.id == selectedCategoryId }) {
                        selectedCategoryId = HomeCategory.ID_RECOMMEND
                    }
                    renderCategoryChips(categories)
                }
                loadSelectedCategory()
            } catch (e: Exception) {
                adapter.submit(emptyList())
                binding.emptyView.visibility = View.VISIBLE
                binding.emptyView.text = e.message ?: getString(R.string.error_network)
                Toast.makeText(
                    this@MainActivity,
                    e.message ?: getString(R.string.error_network),
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                binding.loading.visibility = View.GONE
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    private suspend fun loadSelectedCategory() {
        val cat = categories.firstOrNull { it.id == selectedCategoryId }
            ?: HomeCategory.recommend()
        highlightCategoryChips()
        val page = if (cat.isRecommend) {
            repo.recommend(page = 1)
        } else {
            repo.category(filters = cat.filters, withSelector = false)
        }
        val rows = mutableListOf<HomeRow>()
        rows += HomeRow.Header(cat.label)
        rows += page.items.map { HomeRow.Item(it) }
        adapter.submit(rows)
        binding.emptyView.visibility = if (page.items.isEmpty()) View.VISIBLE else View.GONE
        binding.emptyView.text =
            if (page.items.isEmpty()) getString(R.string.empty_category) else ""
    }

    private fun selectCategory(category: HomeCategory) {
        if (selectedCategoryId == category.id && lastQuery.isBlank()) return
        selectedCategoryId = category.id
        lastQuery = ""
        binding.searchInput.setText("")
        highlightCategoryChips()
        binding.loading.visibility = View.VISIBLE
        binding.emptyView.visibility = View.GONE
        lifecycleScope.launch {
            try {
                loadSelectedCategory()
            } catch (e: Exception) {
                adapter.submit(listOf(HomeRow.Header(category.label)))
                binding.emptyView.visibility = View.VISIBLE
                binding.emptyView.text = e.message ?: getString(R.string.error_network)
            } finally {
                binding.loading.visibility = View.GONE
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun renderCategoryChips(items: List<HomeCategory>) {
        binding.categoryRow.removeAllViews()
        val padH = dp(10f)
        val padV = dp(6f)
        val gap = dp(4f)
        categoryChipViews = items.map { cat ->
            TextView(this).apply {
                text = cat.label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setPadding(padH, padV, padH, padV)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_quality_chip)
                isSelected = cat.id == selectedCategoryId
                setOnClickListener { selectCategory(cat) }
                val lp = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                lp.marginEnd = gap
                binding.categoryRow.addView(this, lp)
            }
        }
    }

    private fun highlightCategoryChips() {
        categoryChipViews.forEachIndexed { index, view ->
            val cat = categories.getOrNull(index) ?: return@forEachIndexed
            view.isSelected = cat.id == selectedCategoryId
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
        lifecycleScope.launch {
            try {
                val page = repo.search(query, page = 1)
                val rows = mutableListOf<HomeRow>()
                rows += HomeRow.Header(getString(R.string.app_section))
                rows += page.items.map { HomeRow.Item(it) }
                adapter.submit(rows)
                binding.emptyView.visibility = if (page.items.isEmpty()) View.VISIBLE else View.GONE
                binding.emptyView.text =
                    if (page.items.isEmpty()) getString(R.string.empty_search_result) else ""
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    e.message ?: getString(R.string.error_network),
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                binding.loading.visibility = View.GONE
                binding.swipeRefresh.isRefreshing = false
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
        adapter.showResumeHint = true
        lifecycleScope.launch {
            try {
                val history = repo.recentHistory()
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
                binding.loading.visibility = View.GONE
                binding.swipeRefresh.isRefreshing = false
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
        adapter.showResumeHint = false
        lifecycleScope.launch {
            try {
                val favorites = repo.favorites()
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
                binding.loading.visibility = View.GONE
                binding.swipeRefresh.isRefreshing = false
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

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()

    private enum class Mode { HOME, HISTORY, FAVORITES }
}
