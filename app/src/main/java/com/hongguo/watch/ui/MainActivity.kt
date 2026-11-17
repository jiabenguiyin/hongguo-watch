package com.hongguo.watch.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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

    private val repo get() = (application as HongguoWatchApp).container.repository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = SeriesListAdapter { openSeries(it) }
        binding.seriesList.layoutManager = LinearLayoutManager(this)
        binding.seriesList.adapter = adapter

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
            }
        }
        binding.tabHome.setOnClickListener { showHome() }
        binding.tabHistory.setOnClickListener { showHistory() }
        binding.tabSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        showHome()
    }

    override fun onResume() {
        super.onResume()
        if (mode == Mode.HISTORY) showHistory()
    }

    private fun showHome() {
        mode = Mode.HOME
        highlightTabs(home = true)
        lastQuery = ""
        binding.searchInput.setText("")
        val rows = mutableListOf<HomeRow>()
        rows += HomeRow.Header(getString(R.string.demo_section))
        rows += repo.demoHome().map { HomeRow.Item(it) }
        rows += HomeRow.Header(getString(R.string.official_section))
        adapter.submit(rows)
        binding.emptyView.visibility = View.VISIBLE
        binding.emptyView.text = getString(R.string.empty_search)
        binding.swipeRefresh.isRefreshing = false
        binding.loading.visibility = View.GONE
    }

    private fun runSearch() {
        val query = binding.searchInput.text?.toString().orEmpty().trim()
        if (query.isEmpty()) {
            Toast.makeText(this, R.string.search_hint, Toast.LENGTH_SHORT).show()
            return
        }
        mode = Mode.HOME
        highlightTabs(home = true)
        lastQuery = query
        binding.loading.visibility = View.VISIBLE
        binding.emptyView.visibility = View.GONE
        lifecycleScope.launch {
            try {
                val page = repo.searchOfficial(query, page = 1)
                val rows = mutableListOf<HomeRow>()
                rows += HomeRow.Header(getString(R.string.demo_section))
                rows += repo.demoHome().map { HomeRow.Item(it) }
                rows += HomeRow.Header(getString(R.string.official_section))
                rows += page.items.map { HomeRow.Item(it) }
                adapter.submit(rows)
                binding.emptyView.visibility = if (page.items.isEmpty()) View.VISIBLE else View.GONE
                binding.emptyView.text = if (page.items.isEmpty()) "无搜索结果" else ""
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, e.message ?: getString(R.string.error_network), Toast.LENGTH_LONG).show()
            } finally {
                binding.loading.visibility = View.GONE
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun showHistory() {
        mode = Mode.HISTORY
        highlightTabs(history = true)
        binding.loading.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val history = repo.recentHistory()
                val rows = history.map {
                    HomeRow.Item(
                        Series(
                            id = it.seriesId,
                            title = it.seriesTitle,
                            cover = it.cover,
                            description = "看到第 ${it.episodeNumber} 集",
                            episodeCount = it.episodeNumber,
                            source = runCatching { SeriesSource.valueOf(it.source) }.getOrDefault(SeriesSource.OFFICIAL),
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

    private fun openSeries(series: Series) {
        if (mode == Mode.HISTORY) {
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
                        putExtra(PlayerActivity.EXTRA_START_POSITION_MS, history.positionMs)
                    }
                }
                startActivity(intent)
            }
        } else {
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

    private fun highlightTabs(home: Boolean = false, history: Boolean = false) {
        binding.tabHome.setTextColor(getColor(if (home) R.color.accent else R.color.text_secondary))
        binding.tabHistory.setTextColor(getColor(if (history) R.color.accent else R.color.text_secondary))
        binding.tabSettings.setTextColor(getColor(R.color.text_secondary))
    }

    private enum class Mode { HOME, HISTORY }
}
