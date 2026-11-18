package com.hongguo.watch.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.hongguo.watch.data.model.Episode
import com.hongguo.watch.databinding.ItemEpisodeBinding

class EpisodeAdapter(
    private val onClick: (Episode) -> Unit,
) : RecyclerView.Adapter<EpisodeAdapter.Holder>() {
    private val items = mutableListOf<Episode>()
    private var selectedId: String? = null
    private var gridMode: Boolean = false

    fun submit(episodes: List<Episode>, selectedId: String? = null, gridMode: Boolean = this.gridMode) {
        items.clear()
        items.addAll(episodes)
        this.selectedId = selectedId
        this.gridMode = gridMode
        notifyDataSetChanged()
    }

    fun setGridMode(gridMode: Boolean) {
        if (this.gridMode == gridMode) return
        this.gridMode = gridMode
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemEpisodeBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    inner class Holder(
        private val binding: ItemEpisodeBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(episode: Episode) {
            val density = binding.root.resources.displayMetrics.density
            val cell = (40f * density).toInt()
            val gap = (6f * density).toInt()
            val lp = (binding.root.layoutParams as? RecyclerView.LayoutParams)
                ?: RecyclerView.LayoutParams(cell, cell)
            if (gridMode) {
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                lp.height = cell
                lp.marginEnd = gap / 2
                lp.bottomMargin = gap
            } else {
                lp.width = cell
                lp.height = cell
                lp.marginEnd = gap
                lp.bottomMargin = 0
            }
            binding.root.layoutParams = lp
            binding.episodeNumber.text = episode.number.toString()
            binding.episodeNumber.isSelected = episode.id == selectedId
            binding.root.setOnClickListener { onClick(episode) }
        }
    }
}
