package com.hongguo.watch.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.hongguo.watch.R
import com.hongguo.watch.data.cache.VideoCacheEntry
import com.hongguo.watch.data.cache.VideoCacheManager
import com.hongguo.watch.data.cache.VideoCacheSpaceSummary
import com.hongguo.watch.databinding.ItemOfflineCacheBinding
import com.hongguo.watch.databinding.ItemOfflineHeaderBinding

/**
 * 库 · 离线段：空间摘要头 + 已缓存分集列表。
 */
class OfflineCacheAdapter(
    private val onPlay: (VideoCacheEntry) -> Unit,
    private val onDelete: (VideoCacheEntry) -> Unit,
    private val onClearAll: () -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private var summary: VideoCacheSpaceSummary = VideoCacheSpaceSummary(0, 0, 0, 0)
    private val entries = mutableListOf<VideoCacheEntry>()

    fun submit(summary: VideoCacheSpaceSummary, items: List<VideoCacheEntry>) {
        this.summary = summary
        entries.clear()
        entries.addAll(items)
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int =
        if (position == 0) TYPE_HEADER else TYPE_ENTRY

    override fun getItemCount(): Int = 1 + entries.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderHolder(ItemOfflineHeaderBinding.inflate(inflater, parent, false))
        } else {
            EntryHolder(ItemOfflineCacheBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is HeaderHolder -> holder.bind(summary, onClearAll)
            is EntryHolder -> holder.bind(entries[position - 1], onPlay, onDelete)
        }
    }

    class HeaderHolder(
        private val binding: ItemOfflineHeaderBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(summary: VideoCacheSpaceSummary, onClearAll: () -> Unit) {
            val ctx = binding.root.context
            binding.usageLabel.text = ctx.getString(
                R.string.video_cache_usage,
                summary.usedLabel,
                summary.budgetLabel,
            )
            binding.usageBar.progress = summary.usedPercentOfBudget
            binding.freeLabel.text = ctx.getString(R.string.offline_free_space, summary.freeLabel)
            binding.countLabel.text = ctx.getString(R.string.offline_entry_count, summary.entryCount)
            binding.clearAllButton.isEnabled = summary.entryCount > 0
            binding.clearAllButton.alpha = if (summary.entryCount > 0) 1f else 0.45f
            binding.clearAllButton.setOnClickListener { onClearAll() }
            binding.emptyHint.visibility =
                if (summary.entryCount == 0) android.view.View.VISIBLE else android.view.View.GONE
        }
    }

    class EntryHolder(
        private val binding: ItemOfflineCacheBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(
            entry: VideoCacheEntry,
            onPlay: (VideoCacheEntry) -> Unit,
            onDelete: (VideoCacheEntry) -> Unit,
        ) {
            val ctx = binding.root.context
            binding.title.text = entry.seriesTitle.ifBlank { entry.seriesId }
            val quality = entry.quality.ifBlank { "—" }
            val size = VideoCacheManager.formatBytes(entry.sizeBytes)
            binding.meta.text = ctx.getString(
                R.string.offline_entry_meta,
                entry.episodeNumber,
                size,
                quality,
            )
            binding.root.setOnClickListener { onPlay(entry) }
            binding.playHint.setOnClickListener { onPlay(entry) }
            binding.deleteButton.setOnClickListener { onDelete(entry) }
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ENTRY = 1
    }
}
