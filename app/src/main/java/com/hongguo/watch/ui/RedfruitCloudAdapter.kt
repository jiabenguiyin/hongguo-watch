package com.hongguo.watch.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.hongguo.watch.R
import com.hongguo.watch.databinding.ItemSeriesBinding

internal data class RedfruitCloudRow(
    val seriesId: String,
    val title: String,
    val cover: String,
    val meta: String,
    val source: String,
    val episodeId: String = "",
    val episodeNumber: Int = 0,
    val positionMs: Long = 0,
    val progressPercent: Int? = null,
) {
    val canResume: Boolean get() = episodeId.isNotBlank() || episodeNumber > 0
}

internal class RedfruitCloudAdapter(
    private val onClick: (RedfruitCloudRow) -> Unit,
) : ListAdapter<RedfruitCloudRow, RedfruitCloudAdapter.Holder>(Diff) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemSeriesBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class Holder(
        private val binding: ItemSeriesBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: RedfruitCloudRow) {
            binding.cover.load(row.cover) {
                crossfade(false)
                placeholder(R.drawable.bg_cover)
                error(R.drawable.bg_cover)
            }
            binding.title.text = row.title
            binding.meta.text = row.meta
            binding.rankBadge.isVisible = false
            binding.favoriteButton.isVisible = false
            binding.playButton.isVisible = false
            binding.actionHint.isVisible = true
            binding.actionHint.setText(
                if (row.canResume) R.string.redfruit_cloud_action_resume else R.string.redfruit_cloud_action_open,
            )
            binding.watchProgress.isVisible = row.progressPercent != null
            if (row.progressPercent != null) {
                binding.watchProgress.progress = row.progressPercent.coerceIn(0, 100)
            }
            binding.root.setOnClickListener { onClick(row) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<RedfruitCloudRow>() {
        override fun areItemsTheSame(old: RedfruitCloudRow, new: RedfruitCloudRow): Boolean =
            old.seriesId == new.seriesId && old.source == new.source &&
                old.episodeId == new.episodeId && old.episodeNumber == new.episodeNumber

        override fun areContentsTheSame(old: RedfruitCloudRow, new: RedfruitCloudRow): Boolean = old == new
    }
}
