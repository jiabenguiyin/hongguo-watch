package com.hongguo.watch.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.RoundedCornersTransformation
import com.hongguo.watch.R
import com.hongguo.watch.data.model.Series
import com.hongguo.watch.databinding.ItemSectionHeaderBinding
import com.hongguo.watch.databinding.ItemSeriesBinding

sealed class HomeRow {
    data class Header(val title: String, val subtitle: String = "") : HomeRow()
    data class Item(
        val series: Series,
        /** 榜单名次；非空时显示左侧序号并隐藏「进」。 */
        val rank: Int? = null,
    ) : HomeRow()
}

class SeriesListAdapter(
    private val onClick: (Series) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private val rows = mutableListOf<HomeRow>()
    var showResumeHint: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    fun submit(rows: List<HomeRow>) {
        this.rows.clear()
        this.rows.addAll(rows)
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is HomeRow.Header -> 0
        is HomeRow.Item -> 1
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == 0) {
            HeaderHolder(ItemSectionHeaderBinding.inflate(inflater, parent, false))
        } else {
            ItemHolder(ItemSeriesBinding.inflate(inflater, parent, false))
        }
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is HomeRow.Header -> (holder as HeaderHolder).bind(row)
            is HomeRow.Item -> (holder as ItemHolder).bind(row)
        }
    }

    private class HeaderHolder(
        private val binding: ItemSectionHeaderBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: HomeRow.Header) {
            binding.sectionTitle.text = if (row.subtitle.isBlank()) {
                row.title
            } else {
                "${row.title} · ${row.subtitle}"
            }
        }
    }

    private inner class ItemHolder(
        private val binding: ItemSeriesBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: HomeRow.Item) {
            val series = row.series
            val rank = row.rank
            binding.title.text = series.title

            val ranked = rank != null && rank > 0
            if (ranked) {
                binding.rankBadge.visibility = View.VISIBLE
                binding.rankBadge.text = rank.toString()
                val colorRes = when (rank) {
                    1 -> R.color.rank_gold
                    2 -> R.color.rank_silver
                    3 -> R.color.rank_bronze
                    else -> R.color.text_secondary
                }
                binding.rankBadge.setTextColor(
                    ContextCompat.getColor(binding.root.context, colorRes),
                )
                binding.rankBadge.textSize = if (rank <= 3) 15f else 12f
            } else {
                binding.rankBadge.visibility = View.GONE
            }

            fun isHeatTag(value: String): Boolean =
                value.contains("万") ||
                    value.contains("收藏") ||
                    value.contains("点赞") ||
                    value.contains("评分") ||
                    value.contains("播放")
            val heat = series.tags.firstOrNull(::isHeatTag)
            val genre = series.tags.firstOrNull { !isHeatTag(it) }
            val meta = when {
                showResumeHint && series.description.isNotBlank() -> series.description
                ranked && series.episodeCount > 0 && !heat.isNullOrBlank() ->
                    binding.root.context.getString(R.string.rank_meta, series.episodeCount, heat)
                ranked && series.episodeCount > 0 && !genre.isNullOrBlank() ->
                    binding.root.context.getString(R.string.rank_meta, series.episodeCount, genre)
                ranked && !heat.isNullOrBlank() -> heat
                series.episodeCount > 0 && series.tags.isNotEmpty() ->
                    "全${series.episodeCount}集 · ${series.tags.take(2).joinToString(" ")}"
                series.episodeCount > 0 -> "全${series.episodeCount}集"
                series.tags.isNotEmpty() -> series.tags.take(2).joinToString(" ")
                series.description.isNotBlank() -> series.description
                else -> ""
            }
            binding.meta.text = meta
            binding.meta.visibility = if (meta.isBlank()) View.GONE else View.VISIBLE

            val radius = binding.root.resources.displayMetrics.density * 6f
            binding.cover.load(series.cover) {
                size(120, 160)
                crossfade(false)
                transformations(RoundedCornersTransformation(radius))
                placeholder(R.drawable.bg_cover)
                error(R.drawable.bg_cover)
            }

            // 榜单行省横向空间：靠序号与整行点击，不显示「进」
            if (ranked) {
                binding.actionHint.visibility = View.GONE
            } else {
                binding.actionHint.visibility = View.VISIBLE
                binding.actionHint.text = binding.root.context.getString(
                    if (showResumeHint) R.string.resume_hint else R.string.open_hint,
                )
            }
            binding.root.setOnClickListener { onClick(series) }
        }
    }
}
