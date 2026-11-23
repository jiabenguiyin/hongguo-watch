package com.hongguo.watch.ui

import com.hongguo.watch.data.cache.VideoCacheEntry

internal data class InitialEpisode(val index: Int, val positionMs: Long)

internal fun offlineCacheEpisodes(
    entries: List<VideoCacheEntry>,
    seriesId: String,
): List<VideoCacheEntry> = entries
    .asSequence()
    .filter { it.ready && it.seriesId == seriesId }
    .sortedWith(compareBy<VideoCacheEntry> { it.episodeNumber }.thenBy { it.episodeId })
    .toList()

internal fun selectInitialEpisode(
    episodeIds: List<String>,
    episodeNumbers: List<Int>,
    requestedId: String?,
    requestedNumber: Int,
    requestedPositionMs: Long,
): InitialEpisode? {
    val indexById = if (requestedId.isNullOrBlank()) -1 else episodeIds.indexOf(requestedId)
    if (indexById >= 0) return InitialEpisode(indexById, requestedPositionMs.coerceAtLeast(0))

    // Cloud history may provide a different episode ID or only the number.
    val indexByNumber = if (requestedNumber > 0) episodeIds.indices.firstOrNull { index ->
        episodeIds[index].isNotBlank() && episodeNumbers.getOrNull(index) == requestedNumber
    } else null
    if (indexByNumber != null) return InitialEpisode(indexByNumber, requestedPositionMs.coerceAtLeast(0))

    val firstPlayable = episodeIds.indexOfFirst { it.isNotBlank() }
    return if (firstPlayable >= 0) InitialEpisode(firstPlayable, 0) else null
}
