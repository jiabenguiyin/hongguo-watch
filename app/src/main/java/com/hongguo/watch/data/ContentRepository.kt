package com.hongguo.watch.data

import com.hongguo.watch.data.db.FavoriteDao
import com.hongguo.watch.data.db.FavoriteEntity
import com.hongguo.watch.data.db.WatchHistoryDao
import com.hongguo.watch.data.db.WatchHistoryEntity
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
    fun demoHome(): List<Series> = DemoCatalog.homeItems()

    suspend fun search(query: String, page: Int = 1): SearchPage = withContext(Dispatchers.IO) {
        appSource.search(query, page)
    }

    suspend fun detail(seriesId: String): SeriesDetail = withContext(Dispatchers.IO) {
        if (seriesId == DemoCatalog.SERIES_ID || seriesId == DemoCatalog.SERIES_ID_S2) {
            DemoCatalog.detail(seriesId)
        } else {
            appSource.fetchDetail(seriesId)
        }
    }

    suspend fun stream(seriesId: String, episodeId: String): StreamInfo = withContext(Dispatchers.IO) {
        if (seriesId == DemoCatalog.SERIES_ID || seriesId == DemoCatalog.SERIES_ID_S2) {
            DemoCatalog.streamFor(episodeId)
                ?: throw IllegalStateException("演示分集不存在")
        } else {
            appSource.fetchStream(seriesId, episodeId, settings.maxQualityNow())
        }
    }

    /**
     * 相关季列表。优先 App plan 接口；失败时用标题 stem 搜索作降级列表（不保证可自动连播）。
     */
    suspend fun related(seriesId: String, seriesTitle: String = "", force: Boolean = false): List<RelatedWork> =
        withContext(Dispatchers.IO) {
            if (seriesId == DemoCatalog.SERIES_ID) {
                return@withContext DemoCatalog.related()
            }
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
            if (seriesId == DemoCatalog.SERIES_ID) {
                return@withContext DemoCatalog.nextSeason()
            }
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
                cover = cover,
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
                    cover = cover,
                    source = source.name,
                    episodeCount = episodeCount,
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
                cover = cover,
                source = source.name,
                episodeCount = episodeCount,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }
}
