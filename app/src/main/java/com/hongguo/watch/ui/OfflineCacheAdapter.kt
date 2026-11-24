package com.hongguo.watch.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.hongguo.watch.R
import com.hongguo.watch.data.cache.VideoCacheEntry
import com.hongguo.watch.data.cache.VideoCacheManager
import com.hongguo.watch.data.cache.VideoCacheSeriesGroup
import com.hongguo.watch.data.cache.VideoCacheSpaceSummary
import com.hongguo.watch.databinding.ItemOfflineCacheBinding
import com.hongguo.watch.databinding.ItemOfflineHeaderBinding

/**
 * 库 · 离线段：空间摘要头 + 按剧分组；点进某部后按集数顺序列出分集。
 */
class OfflineCacheAdapter(
    private val onOpenSeries: (VideoCacheSeriesGroup) -> Unit,
    private val onBackToSeries: () -> Unit,
    private val onPlay: (VideoCacheEntry) -> Unit,
    private val onDeleteEpisode: (VideoCacheEntry) -> Unit,
    private val onDeleteSeries: (VideoCacheSeriesGroup) -> Unit,
    private val onClearAll: () -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private var summary: VideoCacheSpaceSummary = VideoCacheSpaceSummary(0, 0, 0, 0)
    private var groups: List<VideoCacheSeriesGroup> = emptyList()
    private var drillIn: VideoCacheSeriesGroup? = null

    fun submit(summary: VideoCacheSpaceSummary, groups: List<VideoCacheSeriesGroup>) {
        this.summary = summary
        this.groups = groups
        val openId = drillIn?.seriesId
        drillIn = openId?.let { id -> groups.firstOrNull { it.seriesId == id } }
        notifyDataSetChanged()
    }

    fun openSeries(group: VideoCacheSeriesGroup) {
        drillIn = group
        notifyDataSetChanged()
    }

    fun closeSeries() {
        if (drillIn == null) return
        drillIn = null
        notifyDataSetChanged()
    }

    fun isDrilledIn(): Boolean = drillIn != null

    override fun getItemViewType(position: Int): Int = when {
        position == 0 -> TYPE_HEADER
        drillIn != null -> TYPE_EPISODE
        else -> TYPE_SERIES
    }

    override fun getItemCount(): Int {
        val open = drillIn
        return if (open != null) 1 + open.episodes.size else 1 + groups.size
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderHolder(ItemOfflineHeaderBinding.inflate(inflater, parent, false))
            TYPE_SERIES -> SeriesHolder(ItemOfflineCacheBinding.inflate(inflater, parent, false))
            else -> EpisodeHolder(ItemOfflineCacheBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is HeaderHolder -> holder.bind(
                summary = summary,
                seriesCount = groups.size,
                drillIn = drillIn,
                onClearAll = onClearAll,
                onBackToSeries = onBackToSeries,
            )
            is SeriesHolder -> holder.bind(groups[position - 1], onOpenSeries, onDeleteSeries)
            is EpisodeHolder -> {
                val open = drillIn ?: return
                holder.bind(open.episodes[position - 1], onPlay, onDeleteEpisode)
            }
        }
    }

    class HeaderHolder(
        private val binding: ItemOfflineHeaderBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(
            summary: VideoCacheSpaceSummary,
            seriesCount: Int,
            drillIn: VideoCacheSeriesGroup?,
            onClearAll: () -> Unit,
            onBackToSeries: () -> Unit,
        ) {
            val ctx = binding.root.context
            binding.usageLabel.text = ctx.getString(
                R.string.video_cache_usage,
                summary.usedLabel,
                summary.budgetLabel,
            )
            binding.usageBar.progress = summary.usedPercentOfBudget
            binding.freeLabel.text = ctx.getString(R.string.offline_free_space, summary.freeLabel)
            if (drillIn != null) {
                binding.countLabel.text = ctx.getString(
                    R.string.offline_series_episode_count,
                    drillIn.seriesTitle,
                    drillIn.episodeCount,
                )
                binding.clearAllButton.text = ctx.getString(R.string.offline_back_series)
                binding.clearAllButton.isEnabled = true
                binding.clearAllButton.alpha = 1f
                binding.clearAllButton.setOnClickListener { onBackToSeries() }
                binding.emptyHint.visibility = android.view.View.GONE
            } else {
                binding.countLabel.text = ctx.getString(
                    R.string.offline_series_count,
                    seriesCount,
                    summary.entryCount,
                )
                binding.clearAllButton.text = ctx.getString(R.string.clear_video_cache)
                binding.clearAllButton.isEnabled = summary.entryCount > 0
                binding.clearAllButton.alpha = if (summary.entryCount > 0) 1f else 0.45f
                binding.clearAllButton.setOnClickListener { onClearAll() }
                binding.emptyHint.visibility =
                    if (summary.entryCount == 0) android.view.View.VISIBLE else android.view.View.GONE
            }
        }
    }

    class SeriesHolder(
        private val binding: ItemOfflineCacheBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(
            group: VideoCacheSeriesGroup,
            onOpen: (VideoCacheSeriesGroup) -> Unit,
            onDelete: (VideoCacheSeriesGroup) -> Unit,
        ) {
            val ctx = binding.root.context
            binding.title.text = group.seriesTitle
            binding.meta.text = ctx.getString(
                R.string.offline_series_meta,
                group.episodeCount,
                group.sizeLabel,
            )
            binding.playHint.text = ctx.getString(R.string.open_hint)
            binding.root.setOnClickListener { onOpen(group) }
            binding.playHint.setOnClickListener { onOpen(group) }
            binding.deleteButton.setOnClickListener { onDelete(group) }
        }
    }

    class EpisodeHolder(
        private val binding: ItemOfflineCacheBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(
            entry: VideoCacheEntry,
            onPlay: (VideoCacheEntry) -> Unit,
            onDelete: (VideoCacheEntry) -> Unit,
        ) {
            val ctx = binding.root.context
            binding.title.text = ctx.getString(R.string.offline_episode_title, entry.episodeNumber)
            val quality = entry.quality.ifBlank { "—" }
            val size = VideoCacheManager.formatBytes(entry.sizeBytes)
            binding.meta.text = ctx.getString(
                R.string.offline_episode_meta,
                size,
                quality,
            )
            binding.playHint.text = ctx.getString(R.string.resume_hint)
            binding.root.setOnClickListener { onPlay(entry) }
            binding.playHint.setOnClickListener { onPlay(entry) }
            binding.deleteButton.setOnClickListener { onDelete(entry) }
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_SERIES = 1
        private const val TYPE_EPISODE = 2
    }
}
