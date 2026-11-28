package com.hongguo.watch.ui

import com.hongguo.watch.data.db.WatchHistoryEntity
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * 历史筛选：对齐官方「已播放时长 / 已播放集数 / 时间 / 是否看完」。
 *
 * 数据约束：
 * - 时长用 [estimatedWatchedMs]：`(集号-1)*本集时长 + 本集进度`；无 duration 时仅用本集 positionMs。
 * - 看完：总集数取自历史行 [WatchHistoryEntity.episodeCount]（收藏仅回退），
 *   当前集 ≥ 总集，且本集进度 ≥95%（或无可靠时长时视为已到末集）。
 * - 时间用 [WatchHistoryEntity.updatedAt]。
 */
object HistoryFilterPolicy {
    enum class DurationMin(val minMs: Long) {
        ALL(0L),
        OVER_5_SEC(5_000L),
        OVER_1_MIN(60_000L),
        OVER_5_MIN(5 * 60_000L),
        OVER_15_MIN(15 * 60_000L),
        OVER_30_MIN(30 * 60_000L),
    }

    enum class EpisodesMin(val minEpisodes: Int) {
        ALL(0),
        OVER_1(1),
        OVER_3(3),
        OVER_10(10),
        OVER_20(20),
        OVER_50(50),
    }

    enum class TimeRange {
        ALL,
        TODAY,
        YESTERDAY,
        WITHIN_1_MONTH,
        WITHIN_3_MONTHS,
        EARLIER,
    }

    enum class CompleteState {
        ALL,
        COMPLETED,
        NOT_COMPLETED,
    }

    data class Criteria(
        val duration: DurationMin = DurationMin.ALL,
        val episodes: EpisodesMin = EpisodesMin.ALL,
        val time: TimeRange = TimeRange.ALL,
        val complete: CompleteState = CompleteState.ALL,
    ) {
        val isDefault: Boolean
            get() = duration == DurationMin.ALL &&
                episodes == EpisodesMin.ALL &&
                time == TimeRange.ALL &&
                complete == CompleteState.ALL
    }

    /** 估算已播放毫秒：有本集时长时按「前几集满时长 + 本集进度」。 */
    fun estimatedWatchedMs(item: WatchHistoryEntity): Long {
        val pos = item.positionMs.coerceAtLeast(0L)
        val dur = item.durationMs.coerceAtLeast(0L)
        val ep = item.episodeNumber.coerceAtLeast(0)
        return if (dur > 0L && ep > 0) {
            (ep - 1).toLong().coerceAtLeast(0L) * dur + pos
        } else {
            pos
        }
    }

    /**
     * 系列进度百分比（封面底边进度条）。
     * 优先：已看集/总集 + 本集 intra；否则本集 position/duration。
     */
    fun coverProgressPercent(item: WatchHistoryEntity, totalEpisodes: Int?): Int? {
        val total = totalEpisodes?.takeIf { it > 0 }
        val watched = item.episodeNumber.takeIf { it > 0 }
        if (total != null && watched != null) {
            val dur = item.durationMs
            val frac = if (dur > 0L) {
                (item.positionMs.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
            } else {
                1f
            }
            val series = ((watched - 1).coerceAtLeast(0) + frac) / total.toFloat()
            return (series * 100f).toInt().coerceIn(0, 100)
        }
        val dur = item.durationMs
        if (dur > 0L) {
            return ((item.positionMs * 100) / dur).toInt().coerceIn(0, 100)
        }
        return null
    }

    fun isCompleted(item: WatchHistoryEntity, totalEpisodes: Int?): Boolean {
        val total = totalEpisodes?.takeIf { it > 0 } ?: return false
        if (item.episodeNumber < total) return false
        if (item.episodeNumber > total) return true
        val dur = item.durationMs
        if (dur <= 5_000L) return true
        return item.positionMs >= (dur * 0.95).toLong()
    }

    fun matches(
        item: WatchHistoryEntity,
        totalEpisodes: Int?,
        criteria: Criteria,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (criteria.duration != DurationMin.ALL) {
            if (estimatedWatchedMs(item) < criteria.duration.minMs) return false
        }
        if (criteria.episodes != EpisodesMin.ALL) {
            if (item.episodeNumber < criteria.episodes.minEpisodes) return false
        }
        if (criteria.time != TimeRange.ALL) {
            if (!matchesTime(item.updatedAt, criteria.time, nowMs)) return false
        }
        if (criteria.complete != CompleteState.ALL) {
            val done = isCompleted(item, totalEpisodes)
            when (criteria.complete) {
                CompleteState.COMPLETED -> if (!done) return false
                CompleteState.NOT_COMPLETED -> {
                    // 无总集数时无法判定「已看完」，归入未看完。
                    if (done) return false
                }
                CompleteState.ALL -> Unit
            }
        }
        return true
    }

    fun filter(
        items: List<WatchHistoryEntity>,
        totalById: Map<String, Int>,
        criteria: Criteria,
        nowMs: Long = System.currentTimeMillis(),
    ): List<WatchHistoryEntity> {
        if (criteria.isDefault) return items
        return items.filter { item ->
            matches(item, totalById[item.seriesId], criteria, nowMs)
        }
    }

    fun matchesTime(updatedAt: Long, range: TimeRange, nowMs: Long): Boolean {
        if (range == TimeRange.ALL) return true
        val startOfToday = startOfDay(nowMs)
        val startOfYesterday = startOfToday - TimeUnit.DAYS.toMillis(1)
        val within1Month = nowMs - TimeUnit.DAYS.toMillis(30)
        val within3Months = nowMs - TimeUnit.DAYS.toMillis(90)
        return when (range) {
            TimeRange.ALL -> true
            TimeRange.TODAY -> updatedAt >= startOfToday
            TimeRange.YESTERDAY -> updatedAt >= startOfYesterday && updatedAt < startOfToday
            TimeRange.WITHIN_1_MONTH -> updatedAt >= within1Month
            TimeRange.WITHIN_3_MONTHS -> updatedAt >= within3Months
            TimeRange.EARLIER -> updatedAt < within3Months
        }
    }

    private fun startOfDay(nowMs: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = nowMs
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
