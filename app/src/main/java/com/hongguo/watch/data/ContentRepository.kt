package com.hongguo.watch.data

import com.hongguo.watch.data.db.FavoriteDao
import com.hongguo.watch.data.db.FavoriteEntity
import com.hongguo.watch.data.db.WatchHistoryDao
import com.hongguo.watch.data.db.WatchHistoryEntity
import com.hongguo.watch.data.model.CatalogPage
import com.hongguo.watch.data.model.HomeCategory
import com.hongguo.watch.data.model.RelatedWork
import com.hongguo.watch.data.model.SearchPage
import com.hongguo.watch.data.model.Series
import com.hongguo.watch.data.model.SeriesDetail
import com.hongguo.watch.data.model.SeriesSource
import com.hongguo.watch.data.model.StreamInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ContentRepository(
    private val appSource: AppSourceClient,
    private val historyDao: WatchHistoryDao,
    private val favoriteDao: FavoriteDao,
    private val settings: SettingsStore,
) {
    suspend fun search(query: String, page: Int = 1): SearchPage = withContext(Dispatchers.IO) {
        appSource.search(query, page)
    }

    /** 首页推荐流（书城 tab_type=16）；传 offset/sessionId 续页。 */
    suspend fun recommend(
        page: Int = 1,
        offset: Int = 0,
        sessionId: String = "",
    ): CatalogPage = withContext(Dispatchers.IO) {
        appSource.fetchRecommend(page = page, offset = offset, sessionId = sessionId)
    }

    /** App 热播榜（bookmall cell/change）；传 offset/sessionId 续页。 */
    suspend fun rank(
        rankKey: String,
        page: Int = 1,
        offset: Int = 0,
        sessionId: String = "",
    ): CatalogPage = withContext(Dispatchers.IO) {
        appSource.fetchRank(rankKey = rankKey, page = page, offset = offset, sessionId = sessionId)
    }

    /**
     * 分类落地页；`withSelector=true` 时附带题材/类型 Tab。
     * 对照 hongguoTV `filteredDiscovery`。
     */
    suspend fun category(
        filters: Map<String, String> = emptyMap(),
        withSelector: Boolean = true,
        offset: Int = 0,
        sessionId: String = "",
    ): CatalogPage = withContext(Dispatchers.IO) {
        appSource.fetchCategory(
            filters = filters,
            offset = offset,
            sessionId = sessionId,
            withSelector = withSelector && offset == 0,
        )
    }

    /** 组装默认分类条：推荐 + App 热播榜 + 落地页题材/类型（题材失败仍保留榜）。 */
    suspend fun homeCategories(): List<HomeCategory> = withContext(Dispatchers.IO) {
        val base = listOf(HomeCategory.recommend()) + HomeCategory.ranks()
        val panel = runCatching {
            appSource.fetchCategory(filters = emptyMap(), withSelector = true)
        }.getOrNull()
        val extra = panel?.categories.orEmpty()
        if (extra.isEmpty()) base else base + extra
    }

    suspend fun detail(seriesId: String): SeriesDetail = withContext(Dispatchers.IO) {
        appSource.fetchDetail(seriesId)
    }

    suspend fun stream(
        seriesId: String,
        episodeId: String,
        preferAvc: Boolean = true,
        avcOnly: Boolean = false,
        maxHeight: Int? = null,
    ): StreamInfo = withContext(Dispatchers.IO) {
        appSource.fetchStream(
            seriesId = seriesId,
            episodeId = episodeId,
            maxHeight = maxHeight ?: settings.maxQualityNow(),
            preferAvc = preferAvc,
            avcOnly = avcOnly,
        )
    }

    /**
     * 相关季列表。优先 App plan 接口；失败时用标题 stem 搜索作降级列表（不保证可自动连播）。
     */
    suspend fun related(seriesId: String, seriesTitle: String = "", force: Boolean = false): List<RelatedWork> =
        withContext(Dispatchers.IO) {
            runCatching { RelatedWorks.ordered(
                Series(id = seriesId, title = seriesTitle, source = SeriesSource.APP),
                appSource.fetchRelated(seriesId, force),
            ) }.getOrElse { err ->
                if (seriesTitle.isBlank()) throw err
                val stem = RelatedWorks.stem(seriesTitle)
                if (stem.length < 2) throw err
                val page = runCatching { appSource.search(stem.take(12), page = 1) }.getOrNull()
                    ?: throw err
                val current = Series(id = seriesId, title = seriesTitle, source = SeriesSource.APP)
                val rows = page.items
                    .filter { it.id != seriesId && RelatedWorks.stem(it.title) == stem }
                    .map {
                        RelatedWork(
                            series = it,
                            seasonLabel = it.tags.firstOrNull().orEmpty(),
                            upcoming = false,
                            seasonIndex = 0,
                        )
                    }
                RelatedWorks.ordered(current, rows)
            }
        }

    /** 末集跨季：官方相关优先，标题唯一命中下一季作降级。 */
    suspend fun resolveNextSeason(seriesId: String, seriesTitle: String): RelatedWork? =
        withContext(Dispatchers.IO) {
            val current = Series(id = seriesId, title = seriesTitle, source = SeriesSource.APP)
            val related = runCatching { related(seriesId, seriesTitle) }.getOrDefault(emptyList())
            RelatedWorks.nextSeason(current, related)
                ?: run {
                    val stem = RelatedWorks.stem(seriesTitle)
                    if (stem.length < 2) return@run null
                    val page = runCatching { appSource.search(stem.take(12), page = 1) }.getOrNull()
                        ?: return@run null
                    RelatedWorks.nextFromSearchFallback(current, page.items)
                }
        }

    suspend fun recentHistory(): List<WatchHistoryEntity> = historyDao.recent()

    suspend fun saveProgress(
        seriesId: String,
        seriesTitle: String,
        cover: String,
        episodeId: String,
        episodeNumber: Int,
        positionMs: Long,
        durationMs: Long,
        source: SeriesSource,
    ) {
        // 接近片尾记为已看完本集，续播时由调用方决定切下一集；落盘仍保留真实进度。
        historyDao.upsert(
            WatchHistoryEntity(
                seriesId = seriesId,
                seriesTitle = seriesTitle,
                cover = CoverUrls.normalize(cover).ifBlank { cover },
                episodeId = episodeId,
                episodeNumber = episodeNumber,
                positionMs = positionMs.coerceAtLeast(0L),
                durationMs = durationMs.coerceAtLeast(0L),
                updatedAt = System.currentTimeMillis(),
                source = source.name,
            ),
        )
    }

    suspend fun historyFor(seriesId: String): WatchHistoryEntity? = historyDao.find(seriesId)

    /** 续播起点：近片尾（≥95%）则从 0 起（通常应切下一集）。 */
    fun resumePositionMs(history: WatchHistoryEntity?): Long {
        if (history == null) return 0L
        val duration = history.durationMs
        val position = history.positionMs.coerceAtLeast(0L)
        if (duration > 5_000L && position >= (duration * 0.95).toLong()) return 0L
        return position
    }

    suspend fun clearHistory() = historyDao.clear()

    suspend fun favorites(): List<FavoriteEntity> = favoriteDao.list()

    /**
     * 收藏列表用：用详情接口校正总集数，**不**用观看进度集号。
     * 网络失败时保留库内原值；校正后写回 favorites，不改 updatedAt 以免打乱排序。
     */
    suspend fun favoritesWithResolvedEpisodeTotals(): List<FavoriteEntity> =
        withContext(Dispatchers.IO) {
            favoriteDao.list().map { fav ->
                val total = runCatching { resolveEpisodeTotal(fav.seriesId) }.getOrNull()
                if (total != null && total > 0 && total != fav.episodeCount) {
                    val fixed = fav.copy(episodeCount = total)
                    favoriteDao.upsert(fixed)
                    fixed
                } else {
                    fav
                }
            }
        }

    /** 详情/分集列表 → 真实总集数；与观看进度无关。 */
    suspend fun resolveEpisodeTotal(seriesId: String): Int = withContext(Dispatchers.IO) {
        val detail = appSource.fetchDetail(seriesId)
        episodeTotal(detail.series.episodeCount, detail.episodes.size)
    }

    suspend fun isFavorite(seriesId: String): Boolean = favoriteDao.exists(seriesId)

    suspend fun toggleFavorite(
        seriesId: String,
        seriesTitle: String,
        cover: String,
        source: SeriesSource,
        episodeCount: Int = 0,
    ): Boolean {
        return if (favoriteDao.exists(seriesId)) {
            favoriteDao.delete(seriesId)
            false
        } else {
            favoriteDao.upsert(
                FavoriteEntity(
                    seriesId = seriesId,
                    seriesTitle = seriesTitle,
                    cover = CoverUrls.normalize(cover).ifBlank { cover },
                    source = source.name,
                    episodeCount = episodeCount.coerceAtLeast(0),
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            true
        }
    }

    suspend fun addFavorite(
        seriesId: String,
        seriesTitle: String,
        cover: String,
        source: SeriesSource,
        episodeCount: Int = 0,
    ) {
        favoriteDao.upsert(
            FavoriteEntity(
                seriesId = seriesId,
                seriesTitle = seriesTitle,
                cover = CoverUrls.normalize(cover).ifBlank { cover },
                source = source.name,
                episodeCount = episodeCount.coerceAtLeast(0),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    companion object {
        /** 取 series 声明集数与分集列表长度的较大值；禁止用历史进度集号冒充。 */
        fun episodeTotal(seriesEpisodeCount: Int, episodeListSize: Int): Int =
            seriesEpisodeCount.coerceAtLeast(episodeListSize).coerceAtLeast(0)
    }
}
