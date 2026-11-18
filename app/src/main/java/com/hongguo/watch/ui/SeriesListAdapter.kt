package com.hongguo.watch.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.RoundedCornersTransformation
import com.hongguo.watch.R
import com.hongguo.watch.data.CoverUrls
import com.hongguo.watch.data.model.Series
import com.hongguo.watch.databinding.ItemBannerBinding
import com.hongguo.watch.databinding.ItemDiscoveryStripBinding
import com.hongguo.watch.databinding.ItemHotSearchBinding
import com.hongguo.watch.databinding.ItemSectionHeaderBinding
import com.hongguo.watch.databinding.ItemSeriesBinding

sealed class HomeRow {
    data class Banner(
        val series: Series,
        val pageIndex: Int = 0,
        val pageCount: Int = 1,
    ) : HomeRow()

    data class Header(
        val title: String,
        val subtitle: String = "",
        val showMore: Boolean = false,
        val actionLabel: String = "",
        val onAction: (() -> Unit)? = null,
    ) : HomeRow()

    data class Item(
        val series: Series,
        /** 榜单名次；非空时显示左侧序号并隐藏圆形播放。 */
        val rank: Int? = null,
        /** 非 null 时显示行内 ♡；true=已收藏。 */
        val favorited: Boolean? = null,
        /** 0–100；非 null 时显示行内细进度条。 */
        val progressPercent: Int? = null,
        val watchedEpisode: Int? = null,
        val totalEpisodes: Int? = null,
    ) : HomeRow()

    data class SearchHotWord(
        val rank: Int,
        val title: String,
        val series: Series,
    ) : HomeRow()

    data class SearchDiscoveryStrip(
        val items: List<Series>,
    ) : HomeRow()
}

class SeriesListAdapter(
    private val onFavoriteClick: ((Series, Boolean) -> Unit)? = null,
    private val onHotWordClick: ((Series) -> Unit)? = null,
    private val onDiscoveryClick: ((Series) -> Unit)? = null,
    private val onHeaderAction: (() -> Unit)? = null,
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

    fun appendItems(items: List<HomeRow.Item>) {
        if (items.isEmpty()) return
        val start = rows.size
        rows.addAll(items)
        notifyItemRangeInserted(start, items.size)
    }

    /** 更新某剧收藏态（历史行 ♡），找不到则忽略。 */
    fun setFavorited(seriesId: String, favorited: Boolean) {
        val index = rows.indexOfFirst { row ->
            row is HomeRow.Item && row.series.id == seriesId && row.favorited != null
        }
        if (index < 0) return
        val old = rows[index] as HomeRow.Item
        if (old.favorited == favorited) return
        rows[index] = old.copy(favorited = favorited)
        notifyItemChanged(index)
    }

    fun itemCountRows(): Int = rows.size

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is HomeRow.Banner -> 0
        is HomeRow.Header -> 1
        is HomeRow.Item -> 2
        is HomeRow.SearchHotWord -> 3
        is HomeRow.SearchDiscoveryStrip -> 4
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            0 -> BannerHolder(ItemBannerBinding.inflate(inflater, parent, false))
            1 -> HeaderHolder(ItemSectionHeaderBinding.inflate(inflater, parent, false))
            3 -> HotWordHolder(ItemHotSearchBinding.inflate(inflater, parent, false))
            4 -> DiscoveryStripHolder(ItemDiscoveryStripBinding.inflate(inflater, parent, false))
            else -> ItemHolder(ItemSeriesBinding.inflate(inflater, parent, false))
        }
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is HomeRow.Banner -> (holder as BannerHolder).bind(row)
            is HomeRow.Header -> (holder as HeaderHolder).bind(row)
            is HomeRow.Item -> (holder as ItemHolder).bind(row)
            is HomeRow.SearchHotWord -> (holder as HotWordHolder).bind(row)
            is HomeRow.SearchDiscoveryStrip -> (holder as DiscoveryStripHolder).bind(row)
        }
    }

    private inner class BannerHolder(
        private val binding: ItemBannerBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: HomeRow.Banner) {
            val series = row.series
            binding.bannerTitle.text = series.title
            binding.bannerMeta.text = seriesMetaLine(binding.root.context, series)
            val radius = binding.root.resources.displayMetrics.density * 10f
            val coverUrl = CoverUrls.normalize(series.cover).ifBlank { series.cover }
            binding.bannerCover.load(coverUrl.ifBlank { null }) {
                size(640, 360)
                crossfade(false)
                transformations(RoundedCornersTransformation(radius))
                placeholder(R.drawable.bg_banner)
                error(R.drawable.bg_banner)
            }
            renderDots(binding.bannerDots, row.pageIndex, row.pageCount)
            binding.root.setOnClickListener { onClick(series) }
        }

        private fun renderDots(row: LinearLayout, index: Int, count: Int) {
            row.removeAllViews()
            val pages = count.coerceAtLeast(1).coerceAtMost(5)
            val selected = index.coerceIn(0, pages - 1)
            val gap = (row.resources.displayMetrics.density * 4f).toInt()
            val size = (row.resources.displayMetrics.density * 5f).toInt()
            repeat(pages) { i ->
                val dot = ImageView(row.context).apply {
                    setImageResource(R.drawable.bg_banner_dot)
                    isSelected = i == selected
                    layoutParams = LinearLayout.LayoutParams(size, size).apply {
                        if (i > 0) marginStart = gap
                    }
                }
                row.addView(dot)
            }
        }
    }

    private inner class HeaderHolder(
        private val binding: ItemSectionHeaderBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: HomeRow.Header) {
            binding.sectionTitle.text = row.title
            if (row.subtitle.isBlank()) {
                binding.sectionSubtitle.visibility = View.GONE
                binding.sectionSubtitle.text = ""
            } else {
                binding.sectionSubtitle.visibility = View.VISIBLE
                binding.sectionSubtitle.text = row.subtitle
            }
            val action = row.actionLabel.ifBlank {
                if (row.showMore) binding.root.context.getString(R.string.section_more) else ""
            }
            if (action.isNotBlank()) {
                binding.sectionMore.visibility = View.VISIBLE
                binding.sectionMore.text = action
                binding.sectionMore.setOnClickListener {
                    row.onAction?.invoke() ?: onHeaderAction?.invoke()
                }
            } else {
                binding.sectionMore.visibility = View.GONE
                binding.sectionMore.setOnClickListener(null)
            }
        }
    }

    private inner class HotWordHolder(
        private val binding: ItemHotSearchBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: HomeRow.SearchHotWord) {
            binding.hotRank.text = row.rank.toString()
            binding.hotTitle.text = row.title
            val colorRes = when (row.rank) {
                1 -> R.color.rank_gold
                2 -> R.color.rank_silver
                3 -> R.color.rank_bronze
                else -> R.color.text_secondary
            }
            binding.hotRank.setTextColor(
                ContextCompat.getColor(binding.root.context, colorRes),
            )
            binding.root.setOnClickListener {
                (onHotWordClick ?: onClick).invoke(row.series)
            }
        }
    }

    private inner class DiscoveryStripHolder(
        private val binding: ItemDiscoveryStripBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: HomeRow.SearchDiscoveryStrip) {
            val container = binding.discoveryRow
            container.removeAllViews()
            val inflater = LayoutInflater.from(binding.root.context)
            val radius = binding.root.resources.displayMetrics.density * 6f
            row.items.forEach { series ->
                val poster = inflater.inflate(R.layout.item_discovery_poster, container, false)
                val image = poster.findViewById<ImageView>(R.id.poster)
                val coverUrl = CoverUrls.normalize(series.cover).ifBlank { series.cover }
                image.load(coverUrl.ifBlank { null }) {
                    size(216, 288)
                    crossfade(false)
                    transformations(RoundedCornersTransformation(radius))
                    placeholder(R.drawable.bg_cover)
                    error(R.drawable.bg_cover)
                }
                poster.setOnClickListener {
                    (onDiscoveryClick ?: onClick).invoke(series)
                }
                container.addView(poster)
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

            val rankValue = rank?.takeIf { it > 0 }
            if (rankValue != null) {
                binding.rankBadge.visibility = View.VISIBLE
                binding.rankBadge.text = rankValue.toString()
                val colorRes = when (rankValue) {
                    1 -> R.color.rank_gold
                    2 -> R.color.rank_silver
                    3 -> R.color.rank_bronze
                    else -> R.color.text_secondary
                }
                binding.rankBadge.setTextColor(
                    ContextCompat.getColor(binding.root.context, colorRes),
                )
                binding.rankBadge.textSize = if (rankValue <= 3) 15f else 12f
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
            val ranked = rankValue != null
            val progressMeta = progressMetaLine(binding.root.context, row)
            val meta = when {
                progressMeta != null -> progressMeta
                showResumeHint && series.description.isNotBlank() -> series.description
                ranked && series.episodeCount > 0 && !heat.isNullOrBlank() ->
                    binding.root.context.getString(R.string.rank_meta, series.episodeCount, heat)
                ranked && series.episodeCount > 0 && !genre.isNullOrBlank() ->
                    binding.root.context.getString(R.string.rank_meta, series.episodeCount, genre)
                ranked && !heat.isNullOrBlank() -> heat
                else -> seriesMetaLine(binding.root.context, series)
            }
            binding.meta.text = meta
            binding.meta.visibility = if (meta.isBlank()) View.GONE else View.VISIBLE

            val percent = row.progressPercent
            if (percent != null) {
                binding.watchProgress.visibility = View.VISIBLE
                binding.watchProgress.progress = percent.coerceIn(0, 100)
            } else {
                binding.watchProgress.visibility = View.GONE
            }

            val radius = binding.root.resources.displayMetrics.density * 6f
            val coverUrl = CoverUrls.normalize(series.cover).ifBlank { series.cover }
            binding.cover.load(coverUrl.ifBlank { null }) {
                size(120, 160)
                crossfade(false)
                transformations(RoundedCornersTransformation(radius))
                placeholder(R.drawable.bg_cover)
                error(R.drawable.bg_cover)
            }

            val favorited = row.favorited
            if (favorited != null && onFavoriteClick != null) {
                binding.favoriteButton.visibility = View.VISIBLE
                binding.favoriteButton.setImageResource(
                    if (favorited) R.drawable.ic_favorite else R.drawable.ic_favorite_border,
                )
                binding.favoriteButton.setOnClickListener {
                    onFavoriteClick.invoke(series, favorited)
                }
            } else {
                binding.favoriteButton.visibility = View.GONE
                binding.favoriteButton.setOnClickListener(null)
            }

            when {
                ranked -> {
                    binding.playButton.visibility = View.GONE
                    binding.actionHint.visibility = View.GONE
                }
                showResumeHint -> {
                    binding.playButton.visibility = View.GONE
                    binding.actionHint.visibility = View.VISIBLE
                    binding.actionHint.text = binding.root.context.getString(R.string.resume_hint)
                }
                else -> {
                    binding.actionHint.visibility = View.GONE
                    binding.playButton.visibility = View.VISIBLE
                    binding.playButton.setOnClickListener { onClick(series) }
                }
            }
            binding.root.setOnClickListener { onClick(series) }
        }
    }

    companion object {
        fun seriesMetaLine(context: android.content.Context, series: Series): String {
            fun isHeatTag(value: String): Boolean =
                value.contains("万") ||
                    value.contains("收藏") ||
                    value.contains("点赞") ||
                    value.contains("评分") ||
                    value.contains("播放")
            val genres = series.tags.filterNot(::isHeatTag).take(2)
            return when {
                genres.isNotEmpty() && series.episodeCount > 0 ->
                    context.getString(
                        R.string.series_meta_tags,
                        genres.joinToString(" · "),
                        series.episodeCount,
                    )
                series.episodeCount > 0 -> "全${series.episodeCount}集"
                genres.isNotEmpty() -> genres.joinToString(" · ")
                series.description.isNotBlank() -> series.description
                else -> ""
            }
        }

        fun progressMetaLine(context: android.content.Context, row: HomeRow.Item): String? {
            val watched = row.watchedEpisode
            val total = row.totalEpisodes
            val percent = row.progressPercent
            return when {
                watched != null && total != null && total > 0 && percent != null ->
                    context.getString(R.string.progress_watched, watched, total, percent)
                watched != null && percent != null ->
                    context.getString(R.string.progress_meta, watched, percent)
                watched != null ->
                    context.getString(R.string.progress_watched_ep, watched)
                else -> null
            }
        }
    }
}
