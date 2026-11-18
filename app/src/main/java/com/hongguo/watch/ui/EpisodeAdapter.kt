package com.hongguo.watch.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.hongguo.watch.R
import com.hongguo.watch.data.model.Episode
import com.hongguo.watch.databinding.ItemEpisodeBinding

enum class EpisodeCacheUi {
    NONE,
    CACHING,
    READY,
}

class EpisodeAdapter(
    private val onClick: (Episode) -> Unit,
) : RecyclerView.Adapter<EpisodeAdapter.Holder>() {
    private val items = mutableListOf<Episode>()
    private var selectedId: String? = null
    private var gridMode: Boolean = false
    private var selectionMode: Boolean = false
    private var checkedIds: Set<String> = emptySet()
    private var cacheStates: Map<String, EpisodeCacheUi> = emptyMap()

    fun submit(
        episodes: List<Episode>,
        selectedId: String? = null,
        gridMode: Boolean = this.gridMode,
        selectionMode: Boolean = this.selectionMode,
        checkedIds: Set<String> = this.checkedIds,
        cacheStates: Map<String, EpisodeCacheUi> = this.cacheStates,
    ) {
        items.clear()
        items.addAll(episodes)
        this.selectedId = selectedId
        this.gridMode = gridMode
        this.selectionMode = selectionMode
        this.checkedIds = checkedIds
        this.cacheStates = cacheStates
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

            val cache = cacheStates[episode.id] ?: EpisodeCacheUi.NONE
            val checked = selectionMode && episode.id in checkedIds
            val playing = !selectionMode && episode.id == selectedId

            binding.episodeNumber.text = episode.number.toString()
            binding.episodeNumber.isSelected = playing
            when {
                checked -> {
                    binding.episodeNumber.setBackgroundResource(R.drawable.bg_episode_download_selected)
                    binding.episodeNumber.setTextColor(
                        ContextCompat.getColor(binding.root.context, R.color.text_primary),
                    )
                }
                playing -> {
                    binding.episodeNumber.setBackgroundResource(R.drawable.bg_episode_cell)
                    binding.episodeNumber.setTextColor(
                        ContextCompat.getColor(binding.root.context, R.color.text_primary),
                    )
                }
                cache == EpisodeCacheUi.READY -> {
                    binding.episodeNumber.setBackgroundResource(R.drawable.bg_episode_cached)
                    binding.episodeNumber.setTextColor(
                        ContextCompat.getColor(binding.root.context, R.color.text_primary),
                    )
                }
                else -> {
                    binding.episodeNumber.setBackgroundResource(R.drawable.bg_episode_cell)
                    binding.episodeNumber.setTextColor(
                        ContextCompat.getColor(binding.root.context, R.color.text_primary),
                    )
                }
            }

            when (cache) {
                EpisodeCacheUi.READY -> {
                    binding.episodeStatus.visibility = View.VISIBLE
                    binding.episodeStatus.text = "✓"
                    binding.episodeStatus.setTextColor(
                        ContextCompat.getColor(binding.root.context, R.color.rating_good),
                    )
                }
                EpisodeCacheUi.CACHING -> {
                    binding.episodeStatus.visibility = View.VISIBLE
                    binding.episodeStatus.text = "…"
                    binding.episodeStatus.setTextColor(
                        ContextCompat.getColor(binding.root.context, R.color.accent),
                    )
                }
                EpisodeCacheUi.NONE -> {
                    binding.episodeStatus.visibility = if (checked) View.VISIBLE else View.GONE
                    if (checked) {
                        binding.episodeStatus.text = "↓"
                        binding.episodeStatus.setTextColor(
                            ContextCompat.getColor(binding.root.context, R.color.accent),
                        )
                    }
                }
            }

            binding.root.setOnClickListener { onClick(episode) }
        }
    }
}
