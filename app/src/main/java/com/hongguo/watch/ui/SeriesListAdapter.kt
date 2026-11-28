package com.hongguo.watch.ui

import android.annotation.SuppressLint
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
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
import com.hongguo.watch.data.ChaseUpdatePolicy
import com.hongguo.watch.databinding.ItemBannerBinding
import com.hongguo.watch.databinding.ItemDiscoveryStripBinding
import com.hongguo.watch.databinding.ItemHotSearchBinding
import com.hongguo.watch.databinding.ItemLibraryPosterBinding
import com.hongguo.watch.databinding.ItemSectionHeaderBinding
import com.hongguo.watch.databinding.ItemSeriesBinding
import kotlin.math.abs

sealed class HomeRow {
    data class Banner(
        val items: List<Series>,
        val pageIndex: Int = 0,
    ) : HomeRow() {
        init {
            require(items.isNotEmpty()) { "Banner needs at least one series" }
        }
    }

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

    /**
     * 库收藏等三列海报。
     * 默认 meta 为进度「已看集/总集」；[metaLabel] 非空时覆盖。
     * [progressPercent] 非空时在封面底边画细进度条（历史）。
     * [recentWatching] 为「最近在看」角标（历史最新一条）。
     */
    data class Poster(
        val series: Series,
        val watchedEpisode: Int? = null,
        val totalEpisodes: Int? = null,
        val hasUpdate: Boolean = false,
        val selected: Boolean = false,
        val metaLabel: String? = null,
        val progressPercent: Int? = null,
        val recentWatching: Boolean = false,
    ) : HomeRow()
}

class SeriesListAdapter(
    private val onFavoriteClick: ((Series, Boolean) -> Unit)? = null,
    private val onHotWordClick: ((Series) -> Unit)? = null,
    private val onDiscoveryClick: ((Series) -> Unit)? = null,
    private val onHeaderAction: (() -> Unit)? = null,
    /** 历史「续」：续播；缺省与 [onClick] 相同。 */
    private val onResumeClick: ((Series) -> Unit)? = null,
    /** 历史长按删除等。 */
    private val onItemLongClick: ((Series) -> Boolean)? = null,
    private val onSelectionToggle: ((Series) -> Unit)? = null,
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
    /** 收藏批量编辑：海报显示勾选框，点击改选中态。 */
    var selectionMode: Boolean = false
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
        appendRows(items)
    }

    /** 首页续页：列表行或三列海报均可追加。 */
    fun appendRows(more: List<HomeRow>) {
        if (more.isEmpty()) return
        val start = rows.size
        rows.addAll(more)
        notifyItemRangeInserted(start, more.size)
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
        is HomeRow.Poster -> 5
    }

    /** GridLayoutManager：海报占 1 列，其它行通栏。 */
    fun spanSizeFor(position: Int, spanCount: Int): Int {
        if (position !in rows.indices) return spanCount
        return if (rows[position] is HomeRow.Poster) 1 else spanCount
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            0 -> BannerHolder(ItemBannerBinding.inflate(inflater, parent, false))
            1 -> HeaderHolder(ItemSectionHeaderBinding.inflate(inflater, parent, false))
            3 -> HotWordHolder(ItemHotSearchBinding.inflate(inflater, parent, false))
            4 -> DiscoveryStripHolder(ItemDiscoveryStripBinding.inflate(inflater, parent, false))
            5 -> PosterHolder(ItemLibraryPosterBinding.inflate(inflater, parent, false))
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
            is HomeRow.Poster -> (holder as PosterHolder).bind(row)
        }
    }

    private inner class BannerHolder(
        private val binding: ItemBannerBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var items: List<Series> = emptyList()
        private var pageIndex: Int = 0
        private var downX = 0f
        private var downY = 0f
        private var trackingHorizontal = false
        private val gesture = GestureDetector(
            binding.root.context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    items.getOrNull(pageIndex)?.let(onClick)
                    return true
                }

                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float,
                ): Boolean {
                    if (items.size <= 1) return false
                    if (abs(velocityX) < abs(velocityY) || abs(velocityX) < 400f) return false
                    if (velocityX < 0) showPage(pageIndex + 1) else showPage(pageIndex - 1)
                    return true
                }
            },
        )

        @SuppressLint("ClickableViewAccessibility")
        fun bind(row: HomeRow.Banner) {
            items = row.items
            pageIndex = row.pageIndex.coerceIn(0, items.lastIndex)
            renderCurrent()
            binding.root.setOnClickListener(null)
            binding.root.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.x
                        downY = event.y
                        trackingHorizontal = false
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = abs(event.x - downX)
                        val dy = abs(event.y - downY)
                        if (!trackingHorizontal && (dx > 12f || dy > 12f)) {
                            trackingHorizontal = dx >= dy
                            v.parent?.requestDisallowInterceptTouchEvent(trackingHorizontal)
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (!trackingHorizontal && items.size > 1) {
                            val dx = event.x - downX
                            if (abs(dx) > 48f && abs(dx) > abs(event.y - downY)) {
                                if (dx < 0) showPage(pageIndex + 1) else showPage(pageIndex - 1)
                                v.parent?.requestDisallowInterceptTouchEvent(false)
                                return@setOnTouchListener true
                            }
                        }
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                }
                gesture.onTouchEvent(event)
            }
        }

        private fun showPage(target: Int) {
            if (items.isEmpty()) return
            val next = ((target % items.size) + items.size) % items.size
            if (next == pageIndex) return
            pageIndex = next
            renderCurrent()
        }

        private fun renderCurrent() {
            val series = items.getOrNull(pageIndex) ?: return
            binding.bannerTitle.text = series.title
            binding.bannerMeta.text = seriesMetaLine(binding.root.context, series)
            val radius = binding.root.resources.displayMetrics.density * 10f
            val coverUrl = CoverUrls.normalize(series.cover).ifBlank { series.cover }
            binding.bannerCover.load(coverUrl.ifBlank { null }) {
                size(640, 360)
                crossfade(true)
                transformations(RoundedCornersTransformation(radius))
                placeholder(R.drawable.bg_banner)
                error(R.drawable.bg_banner)
            }
            renderDots(binding.bannerDots, pageIndex, items.size)
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

    private inner class PosterHolder(
        private val binding: ItemLibraryPosterBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: HomeRow.Poster) {
            val series = row.series
            binding.posterTitle.text = series.title
            val total = row.totalEpisodes?.takeIf { it > 0 } ?: series.episodeCount
            val meta = row.metaLabel?.takeIf { it.isNotBlank() }
                ?: ChaseUpdatePolicy.progressLabel(row.watchedEpisode, total)
            if (meta.isBlank()) {
                binding.posterMeta.visibility = View.GONE
                binding.posterMeta.text = ""
            } else {
                binding.posterMeta.visibility = View.VISIBLE
                binding.posterMeta.text = meta
            }
            val percent = row.progressPercent
            if (percent != null) {
                binding.posterProgress.visibility = View.VISIBLE
                binding.posterProgress.progress = percent.coerceIn(0, 100)
            } else {
                binding.posterProgress.visibility = View.GONE
            }
            binding.recentWatchingBadge.visibility =
                if (!selectionMode && row.recentWatching) View.VISIBLE else View.GONE
            binding.updateBadge.visibility =
                if (!selectionMode && row.hasUpdate) View.VISIBLE else View.GONE
            if (selectionMode) {
                binding.posterCheck.visibility = View.VISIBLE
                binding.posterCheck.isSelected = row.selected
                binding.posterCheck.setImageResource(
                    if (row.selected) R.drawable.ic_check_white else 0,
                )
            } else {
                binding.posterCheck.visibility = View.GONE
                binding.posterCheck.isSelected = false
            }
            val radius = binding.root.resources.displayMetrics.density * 6f
            val coverUrl = CoverUrls.normalize(series.cover).ifBlank { series.cover }
            binding.posterCover.load(coverUrl.ifBlank { null }) {
                size(240, 320)
                crossfade(false)
                transformations(RoundedCornersTransformation(radius))
                placeholder(R.drawable.bg_cover)
                error(R.drawable.bg_cover)
            }
            binding.root.setOnClickListener {
                if (selectionMode) {
                    onSelectionToggle?.invoke(series)
                } else {
                    onClick(series)
                }
            }
            binding.root.setOnLongClickListener {
                if (selectionMode) {
                    onSelectionToggle?.invoke(series)
                    true
                } else {
                    onItemLongClick?.invoke(series) == true
                }
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
                    binding.actionHint.setOnClickListener(null)
                }
                showResumeHint -> {
                    binding.playButton.visibility = View.GONE
                    binding.actionHint.visibility = View.VISIBLE
                    binding.actionHint.text = binding.root.context.getString(R.string.resume_hint)
                    // 「续」单独续播；封面/标题走 onClick（详情）
                    binding.actionHint.setOnClickListener {
                        (onResumeClick ?: onClick).invoke(series)
                    }
                }
                else -> {
                    binding.actionHint.visibility = View.GONE
                    binding.actionHint.setOnClickListener(null)
                    binding.playButton.visibility = View.VISIBLE
                    binding.playButton.setOnClickListener { onClick(series) }
                }
            }
            binding.root.setOnClickListener { onClick(series) }
            binding.root.setOnLongClickListener {
                onItemLongClick?.invoke(series) == true
            }
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
