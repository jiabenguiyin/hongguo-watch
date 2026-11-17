package com.hongguo.watch.data

import com.hongguo.watch.data.db.WatchHistoryDao
import com.hongguo.watch.data.db.WatchHistoryEntity
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
    private val settings: SettingsStore,
) {
    fun demoHome(): List<Series> = DemoCatalog.homeItems()

    suspend fun search(query: String, page: Int = 1): SearchPage = withContext(Dispatchers.IO) {
        appSource.search(query, page)
    }

    suspend fun detail(seriesId: String): SeriesDetail = withContext(Dispatchers.IO) {
        if (seriesId == DemoCatalog.SERIES_ID) {
            DemoCatalog.detail()
        } else {
            appSource.fetchDetail(seriesId)
        }
    }

    suspend fun stream(seriesId: String, episodeId: String): StreamInfo = withContext(Dispatchers.IO) {
        if (seriesId == DemoCatalog.SERIES_ID) {
            DemoCatalog.streamFor(episodeId)
                ?: throw IllegalStateException("演示分集不存在")
        } else {
            appSource.fetchStream(seriesId, episodeId, settings.maxQualityNow())
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
        historyDao.upsert(
            WatchHistoryEntity(
                seriesId = seriesId,
                seriesTitle = seriesTitle,
                cover = cover,
                episodeId = episodeId,
                episodeNumber = episodeNumber,
                positionMs = positionMs,
                durationMs = durationMs,
                updatedAt = System.currentTimeMillis(),
                source = source.name,
            ),
        )
    }

    suspend fun historyFor(seriesId: String): WatchHistoryEntity? = historyDao.find(seriesId)

    suspend fun clearHistory() = historyDao.clear()
}
