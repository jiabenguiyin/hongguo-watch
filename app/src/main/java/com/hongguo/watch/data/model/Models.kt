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
}

/** 首页分类 Tab：推荐流 / 落地页筛选（对照 hongguoTV DiscoverySelection）。 */
data class HomeCategory(
    val id: String,
    val label: String,
    val filters: Map<String, String> = emptyMap(),
) {
    val isRecommend: Boolean get() = id == ID_RECOMMEND

    companion object {
        const val ID_RECOMMEND = "recommend"
        fun recommend(): HomeCategory = HomeCategory(ID_RECOMMEND, "推荐")
    }
}

data class CatalogPage(
    val items: List<Series>,
    val page: Int = 1,
    val hasMore: Boolean = false,
    /** 首次拉分类落地页时带回的可选分类（题材等）。 */
    val categories: List<HomeCategory> = emptyList(),
)

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

/** App 族相关季 / 同系列条目（对照 hongguoTV RelatedWork）。 */
data class RelatedWork(
    val series: Series,
    val seasonLabel: String = "",
    val upcoming: Boolean = false,
    val seasonIndex: Int = 0,
) {
    val label: String
        get() = listOf(
            seasonLabel,
            if (upcoming) "待上线" else if (series.episodeCount > 0) "全${series.episodeCount}集" else "已上线",
        ).filter { it.isNotBlank() }.joinToString(" · ")
}
