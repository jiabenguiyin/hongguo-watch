package com.hongguo.watch.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.hongguo.watch.data.model.Series
import com.hongguo.watch.databinding.ItemSectionHeaderBinding
import com.hongguo.watch.databinding.ItemSeriesBinding

sealed class HomeRow {
    data class Header(val title: String) : HomeRow()
    data class Item(val series: Series) : HomeRow()
}

class SeriesListAdapter(
    private val onClick: (Series) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private val rows = mutableListOf<HomeRow>()

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
            is HomeRow.Header -> (holder as HeaderHolder).bind(row.title)
            is HomeRow.Item -> (holder as ItemHolder).bind(row.series)
        }
    }

    private class HeaderHolder(
        private val binding: ItemSectionHeaderBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(title: String) {
            binding.sectionTitle.text = title
        }
    }

    private inner class ItemHolder(
        private val binding: ItemSeriesBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(series: Series) {
            binding.title.text = series.title
            val meta = buildString {
                if (series.episodeCount > 0) append("全${series.episodeCount}集")
                if (series.tags.isNotEmpty()) {
                    if (isNotEmpty()) append(" · ")
                    append(series.tags.take(2).joinToString(" "))
                }
            }
            binding.meta.text = meta
            binding.cover.load(series.cover) {
                size(96, 128)
                crossfade(false)
            }
            binding.root.setOnClickListener { onClick(series) }
        }
    }
}
