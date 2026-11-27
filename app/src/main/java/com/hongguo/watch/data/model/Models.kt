package com.hongguo.watch.data.model

data class Series(
    val id: String,
    val title: String,
    val cover: String = "",
    val description: String = "",
    val episodeCount: Int = 0,
    val tags: List<String> = emptyList(),
    val source: SeriesSource = SeriesSource.APP,
    /** 官方社交收藏人数（详情按钮）；0=未知。库卡进度勿用此字段。 */
    val collectCount: Long = 0L,
)

enum class SeriesSource {
    APP,
    OFFICIAL,
}

/**
 * 首页分类 Tab：推荐流 / App 热播榜 / 落地页筛选。
 * 榜单对照 jackotom/hongguo-drama `hongguo-app-catalog.js` RANKS。
 */
data class HomeCategory(
    val id: String,
    val label: String,
    val filters: Map<String, String> = emptyMap(),
    /** 非空时为 App 热播榜 slug（如 hot-drama）。 */
    val rankKey: String? = null,
) {
    val isRecommend: Boolean get() = id == ID_RECOMMEND
    val isRank: Boolean get() = !rankKey.isNullOrBlank()

    companion object {
        const val ID_RECOMMEND = "recommend"
        fun recommend(): HomeCategory = HomeCategory(ID_RECOMMEND, "推荐")

        /** 手表首屏短标签；对应 App 四类热播榜。无独立「飙升」App 榜。 */
        fun ranks(): List<HomeCategory> = listOf(
            HomeCategory(id = "rank:hot-drama", label = "热榜", rankKey = "hot-drama"),
            HomeCategory(id = "rank:hot-real-drama", label = "真人榜", rankKey = "hot-real-drama"),
            HomeCategory(id = "rank:hot-comic-drama", label = "漫剧榜", rankKey = "hot-comic-drama"),
            HomeCategory(id = "rank:hot-ai-drama", label = "AI榜", rankKey = "hot-ai-drama"),
        )
    }
}

data class CatalogPage(
    val items: List<Series>,
    val page: Int = 1,
    val hasMore: Boolean = false,
    /** 首次拉分类落地页时带回的可选分类（题材等）。 */
    val categories: List<HomeCategory> = emptyList(),
    /** 服务端续页游标（推荐/榜单/分类）。 */
    val nextOffset: Int = 0,
    val sessionId: String = "",
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
    /** 上游 codec_type 归一化标签（如 h264 / bytevc1）。 */
    val codec: String = "",
    val isCdnBackup: Boolean = false,
    /** Additional provenance shown to the user for a non-default fallback. */
    val sourceLabel: String = "",
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
