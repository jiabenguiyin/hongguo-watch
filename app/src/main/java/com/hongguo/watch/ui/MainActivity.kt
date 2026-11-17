package com.hongguo.watch.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.hongguo.watch.HongguoWatchApp
import com.hongguo.watch.R
import com.hongguo.watch.data.model.Series
import com.hongguo.watch.data.model.SeriesSource
import com.hongguo.watch.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: SeriesListAdapter
    private var mode = Mode.HOME
    private var lastQuery: String = ""

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
                Mode.HOME -> if (lastQuery.isNotBlank()) runSearch() else showHome()
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

    private fun showHome() {
        mode = Mode.HOME
        highlightTabs(home = true)
        lastQuery = ""
        binding.searchInput.setText("")
        binding.searchRow.visibility = View.VISIBLE
        binding.pageSubtitle.visibility = View.GONE
        binding.brandTitle.text = getString(R.string.app_name)
        adapter.showResumeHint = false
        lifecycleScope.launch {
            val rows = mutableListOf<HomeRow>()
            if (container.settings.showDemoNow()) {
                rows += HomeRow.Header(getString(R.string.demo_section))
                rows += repo.demoHome().map { HomeRow.Item(it) }
            }
            rows += HomeRow.Header(getString(R.string.app_section))
            adapter.submit(rows)
            binding.emptyView.visibility = View.VISIBLE
            binding.emptyView.text = getString(R.string.empty_search)
            binding.swipeRefresh.isRefreshing = false
            binding.loading.visibility = View.GONE
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
        binding.pageSubtitle.visibility = View.GONE
        binding.loading.visibility = View.VISIBLE
        binding.emptyView.visibility = View.GONE
        adapter.showResumeHint = false
        lifecycleScope.launch {
            try {
                val page = repo.search(query, page = 1)
                val rows = mutableListOf<HomeRow>()
                if (container.settings.showDemoNow()) {
                    rows += HomeRow.Header(getString(R.string.demo_section))
                    rows += repo.demoHome().map { HomeRow.Item(it) }
                }
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

    private enum class Mode { HOME, HISTORY, FAVORITES }
}
