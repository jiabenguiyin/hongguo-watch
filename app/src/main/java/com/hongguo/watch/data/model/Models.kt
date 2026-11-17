package com.hongguo.watch.data.model

data class Series(
    val id: String,
    val title: String,
    val cover: String = "",
    val description: String = "",
    val episodeCount: Int = 0,
    val tags: List<String> = emptyList(),
    val source: SeriesSource = SeriesSource.APP,
)

enum class SeriesSource {
    APP,
    OFFICIAL,
    DEMO,
}

data class Episode(
    val id: String,
    val number: Int,
    val title: String = "",
    val seriesId: String,
    val seriesTitle: String,
    val cover: String = "",
)

data class SeriesDetail(
    val series: Series,
    val episodes: List<Episode>,
)

data class StreamInfo(
    val url: String,
    val quality: String = "",
    val isPreview: Boolean = false,
    val referer: String? = null,
    val userAgent: String? = null,
    /** App 族 spade_a 派生密钥；非空时播放走 RemoteVideo / MediaCrypto。 */
    val decryptionKey: ByteArray? = null,
)

data class SearchPage(
    val items: List<Series>,
    val page: Int,
    val hasMore: Boolean,
)
