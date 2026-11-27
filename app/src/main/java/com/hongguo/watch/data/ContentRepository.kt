package com.hongguo.watch.data

import com.hongguo.watch.data.db.FavoriteDao
import com.hongguo.watch.data.db.FavoriteEntity
import com.hongguo.watch.data.db.WatchHistoryEntity
import com.hongguo.watch.data.model.CatalogPage
import com.hongguo.watch.data.model.HomeCategory
import com.hongguo.watch.data.model.RelatedWork
import com.hongguo.watch.data.model.SearchPage
import com.hongguo.watch.data.model.Series
import com.hongguo.watch.data.model.SeriesDetail
import com.hongguo.watch.data.model.SeriesSource
import com.hongguo.watch.data.model.StreamInfo
import com.hongguo.watch.upstream.Rendition
import com.hongguo.watch.upstream.RequestScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

class ContentRepository internal constructor(
    private val appSource: AppSourceClient,
    private val currentLibrary: suspend () -> LocalLibrary,
    private val settings: SettingsStore,
    private val onProgressSaved: (WatchHistoryEntity, Int, Boolean) -> Unit = { _, _, _ -> },
    private val onFavoriteChanged: (FavoriteEntity, Boolean) -> Unit = { _, _ -> },
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
    ): StreamInfo = streamSelection(seriesId, episodeId, preferAvc, avcOnly, maxHeight).stream

    /** 取流并带回完整阶梯，供播放失败自动切流。 */
    suspend fun streamSelection(
        seriesId: String,
        episodeId: String,
        preferAvc: Boolean = true,
        avcOnly: Boolean = false,
        maxHeight: Int? = null,
        excludeKeys: Set<String> = emptySet(),
    ): StreamSelection = withContext(Dispatchers.IO) {
        val quality = maxHeight ?: settings.maxQualityNow()
        val route = settings.playbackRouteNow()
        val proxyUrl = settings.playbackProxyUrlNow()
        val proxyToken = settings.playbackProxyTokenNow()
        suspendCancellableCoroutine { continuation ->
            val request = RequestScope()
            continuation.invokeOnCancellation { request.cancel() }
            continuation.resumeWith(runCatching {
                request.run {
                    appSource.fetchStreamSelection(
                        seriesId = seriesId,
                        episodeId = episodeId,
                        maxHeight = quality,
                        preferAvc = preferAvc,
                        avcOnly = avcOnly,
                        excludeKeys = excludeKeys,
                        routePreference = route,
                        playbackProxyBaseUrl = proxyUrl,
                        playbackProxyToken = proxyToken,
                    )
                }
            })
        }
    }

    /** 已有阶梯时本地再选（不联网）。 */
    fun selectFromLadder(
        renditions: List<Rendition>,
        maxHeight: Int,
        preferAvc: Boolean = true,
        avcOnly: Boolean = false,
        excludeKeys: Set<String> = emptySet(),
    ): StreamSelection = appSource.selectFromLadder(
        renditions = renditions,
        maxHeight = maxHeight,
        preferAvc = preferAvc,
        avcOnly = avcOnly,
        excludeKeys = excludeKeys,
    )

    /**
     * 相关季列表。优先 App plan 接口（按 book/series id）；
     * 失败时用标题 stem 仅作**展示**降级，不可用于自动连播。
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

    /**
     * 末集跨季：只走官方同 IP 季表（`single_col_same_ip_series`）的
     * `season_index + 1`。禁止用 `detail_page_more_related` / 标题搜索，
     * 避免 A 公司第3季误跳 B 公司同题材第4季。
     */
    @Suppress("UNUSED_PARAMETER") // seriesTitle 保留给调用方；自动续季不看标题
    suspend fun resolveNextSeason(seriesId: String, seriesTitle: String): RelatedWork? =
        withContext(Dispatchers.IO) {
            val sameIp = runCatching { appSource.fetchSameIpSeries(seriesId) }
                .getOrDefault(emptyList())
            RelatedWorks.nextSeasonFromSameIp(seriesId, sameIp)
        }

    suspend fun recentHistory(): List<WatchHistoryEntity> = currentLibrary().history.recent()

    suspend fun saveProgress(
        seriesId: String,
        seriesTitle: String,
        cover: String,
        episodeId: String,
        episodeNumber: Int,
        positionMs: Long,
        durationMs: Long,
        source: SeriesSource,
        episodeCount: Int = 0,
        forceCloudSync: Boolean = false,
    ) {
        // 接近片尾记为已看完本集，续播时由调用方决定切下一集；落盘仍保留真实进度。
        val record = WatchHistoryEntity(
            seriesId = seriesId,
            seriesTitle = seriesTitle,
            cover = CoverUrls.normalize(cover).ifBlank { cover },
            episodeId = episodeId,
            episodeNumber = episodeNumber,
            positionMs = positionMs.coerceAtLeast(0L),
            durationMs = durationMs.coerceAtLeast(0L),
            updatedAt = System.currentTimeMillis(),
            source = source.name,
        )
        currentLibrary().saveProgress(record)
        onProgressSaved(record, episodeCount.coerceAtLeast(0), forceCloudSync)
    }

    suspend fun historyFor(seriesId: String): WatchHistoryEntity? = currentLibrary().history.find(seriesId)

    /** 续播起点：近片尾（≥95%）则从 0 起（通常应切下一集）。 */
    fun resumePositionMs(history: WatchHistoryEntity?): Long {
        if (history == null) return 0L
        val duration = history.durationMs
        val position = history.positionMs.coerceAtLeast(0L)
        if (duration > 5_000L && position >= (duration * 0.95).toLong()) return 0L
        return position
    }

    suspend fun clearHistory() = currentLibrary().clearHistory()

    suspend fun deleteHistory(seriesId: String) = currentLibrary().deleteHistory(seriesId)

    suspend fun favorites(): List<FavoriteEntity> = currentLibrary().favorites.list()

    /**
     * 收藏列表用：用详情接口校正总集数，**不**用观看进度集号。
     * 网络失败时保留库内原值；校正后写回 favorites，不改 updatedAt 以免打乱排序。
     */
    suspend fun favoritesWithResolvedEpisodeTotals(): List<FavoriteEntity> {
        val favoriteDao = currentLibrary().favorites
        return withContext(Dispatchers.IO) {
            favoriteDao.list().mapNotNull { fav ->
                val total = runCatching { resolveEpisodeTotal(fav.seriesId) }.getOrNull()
                if (total != null && total > 0 && total != fav.episodeCount) {
                    val fixed = fav.copy(episodeCount = total)
                    if (updateFavoriteMetadata(favoriteDao, fixed) > 0) fixed else favoriteDao.find(fav.seriesId)
                } else {
                    fav
                }
            }
        }
    }

    /** 详情/分集列表 → 真实总集数；与观看进度无关。 */
    suspend fun resolveEpisodeTotal(seriesId: String): Int = withContext(Dispatchers.IO) {
        val detail = appSource.fetchDetail(seriesId)
        episodeTotal(detail.series.episodeCount, detail.episodes.size)
    }

    suspend fun isFavorite(seriesId: String): Boolean = currentLibrary().favorites.exists(seriesId)

    suspend fun toggleFavorite(
        seriesId: String,
        seriesTitle: String,
        cover: String,
        source: SeriesSource,
        episodeCount: Int = 0,
    ): Boolean {
        val record = FavoriteEntity(
            seriesId = seriesId,
            seriesTitle = seriesTitle,
            cover = CoverUrls.normalize(cover).ifBlank { cover },
            source = source.name,
            episodeCount = episodeCount.coerceAtLeast(0),
            updatedAt = System.currentTimeMillis(),
        )
        val added = currentLibrary().toggleFavorite(record)
        onFavoriteChanged(record, added)
        return added
    }

    /** 批量编辑：移除收藏（已不在收藏则忽略）。 */
    suspend fun removeFavorite(seriesId: String) {
        val id = seriesId.trim()
        if (id.isEmpty()) return
        val current = currentLibrary().favorites.find(id) ?: return
        // toggleFavorite：已收藏则删除并返回 false
        val nowFavorited = currentLibrary().toggleFavorite(current)
        if (!nowFavorited) onFavoriteChanged(current, false)
    }

    suspend fun refreshFavoriteMetadata(
        seriesId: String,
        seriesTitle: String,
        cover: String,
        source: SeriesSource,
        episodeCount: Int = 0,
    ) {
        val favoriteDao = currentLibrary().favorites
        val current = favoriteDao.find(seriesId) ?: return
        updateFavoriteMetadata(
            favoriteDao,
            current.copy(
                seriesTitle = seriesTitle,
                cover = CoverUrls.normalize(cover).ifBlank { cover },
                source = source.name,
                episodeCount = if (episodeCount > 0) episodeCount else current.episodeCount,
            ),
        )
    }

    private suspend fun updateFavoriteMetadata(favoriteDao: FavoriteDao, record: FavoriteEntity): Int =
        favoriteDao.updateMetadataIfCurrent(
            record.seriesId, record.updatedAt, record.seriesTitle, record.cover, record.source, record.episodeCount,
        )

    companion object {
        /** 取 series 声明集数与分集列表长度的较大值；禁止用历史进度集号冒充。 */
        fun episodeTotal(seriesEpisodeCount: Int, episodeListSize: Int): Int =
            seriesEpisodeCount.coerceAtLeast(episodeListSize).coerceAtLeast(0)
    }
}
