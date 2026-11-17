package com.hongguo.watch.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.hongguo.watch.R
import com.hongguo.watch.data.model.Episode
import com.hongguo.watch.databinding.ItemEpisodeBinding

class EpisodeAdapter(
    private val onClick: (Episode) -> Unit,
) : RecyclerView.Adapter<EpisodeAdapter.Holder>() {
    private val items = mutableListOf<Episode>()
    private var selectedId: String? = null

    fun submit(episodes: List<Episode>, selectedId: String? = null) {
        items.clear()
        items.addAll(episodes)
        this.selectedId = selectedId
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
            binding.episodeNumber.text = episode.number.toString()
            val selected = episode.id == selectedId
            val color = ContextCompat.getColor(
                binding.root.context,
                if (selected) R.color.episode_selected else R.color.episode_normal,
            )
            binding.episodeNumber.setBackgroundColor(color)
            binding.root.setOnClickListener { onClick(episode) }
        }
    }
}
