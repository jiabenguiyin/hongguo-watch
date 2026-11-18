package com.hongguo.watch.data

import android.content.Context
import com.hongguo.watch.data.model.CatalogPage
import com.hongguo.watch.data.model.Episode
import com.hongguo.watch.data.model.HomeCategory
import com.hongguo.watch.data.model.RelatedWork
import com.hongguo.watch.data.model.SearchPage
import com.hongguo.watch.data.model.Series
import com.hongguo.watch.data.model.SeriesDetail
import com.hongguo.watch.data.model.SeriesSource
import com.hongguo.watch.data.model.StreamInfo
import com.hongguo.watch.upstream.DeviceRegister
import com.hongguo.watch.upstream.MobilePlayback
import com.hongguo.watch.upstream.RequestScope
import com.hongguo.watch.upstream.Signer
import com.hongguo.watch.upstream.VendorConstants
import com.hongguo.watch.upstream.cleanUrl
import com.hongguo.watch.upstream.objects
import com.hongguo.watch.upstream.str
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

/**
 * 默认片源：开源 App 族上游（对照 N3urda/hongguoTV Signer / DeviceRegister / MobilePlayback，
 * 以及 jackotom/hongguo-drama 的 App 分集 preload）。
 *
 * 不依赖本地 Node 桥。
 */
class AppSourceClient(
    context: Context,
    private val client: OkHttpClient = defaultClient(),
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val signer = Signer()
    private val clock: () -> Long = System::currentTimeMillis
    private val relatedCache = ConcurrentHashMap<String, List<RelatedWork>>()

    fun search(query: String, page: Int = 1): SearchPage {
        val keyword = query.trim()
        require(keyword.isNotEmpty()) { "请输入搜索词" }
        ensureDevice()

        val values = VendorConstants.device.toMutableMap()
        for (k in listOf("version_code", "manifest_version_code", "update_version_code", "pv_player")) {
            values[k] = "72232"
        }
        values["version_name"] = "7.2.2.32"
        values["device_id"] = prefs.getString(KEY_DEVICE_ID, "").orEmpty()
        values["iid"] = prefs.getString(KEY_IID, "").orEmpty()
        values["cdid"] = prefs.getString(KEY_CDID, "").orEmpty()
        values.putAll(
            linkedMapOf(
                "query" to keyword,
                "count" to "20",
                "offset" to ((page - 1) * 20).toString(),
                "tab_type" to "11",
                "bookshelf_search_plan" to "4",
                "use_correct" to "true",
                "user_is_login" to "0",
            ),
        )

        val signed = signer.sign(
            "https://api5-normal-sinfonlinea.fqnovel.com/reading/bookapi/search/tab/v",
            values,
        )
        val data = JSONObject(execute(signed))
        if (data.optInt("code", -1) != 0) throw IllegalStateException("App 搜索暂不可用")
        val tabs = data.optJSONArray("search_tabs")
            ?: data.optJSONObject("data")?.optJSONArray("search_tabs")
            ?: throw IllegalStateException("搜索格式异常")
        val tab = tabs.objects().firstOrNull { it.optInt("tab_type") == 11 }
            ?: throw IllegalStateException("短剧搜索暂不可用")

        val items = mutableListOf<Series>()
        val seen = HashSet<String>()
        for (cell in (tab.optJSONArray("data") ?: JSONArray()).objects()) {
            val cells = mutableListOf(cell)
            runCatching { JSONObject(cell.str("lynx_data")).optJSONArray("cell_data")?.objects() }
                .getOrNull()
                ?.let { cells.addAll(it) }
            for (row in cells) {
                val video = row.optJSONArray("video_data")?.objects()?.firstOrNull() ?: continue
                val id = video.str("series_id").ifEmpty {
                    row.str("book_id").ifEmpty { row.str("search_result_id") }
                }
                if (!SERIES_ID.matches(id) || !seen.add(id)) continue
                val title = row.optJSONObject("search_high_light")
                    ?.optJSONObject("title")
                    ?.str("text")
                    .orEmpty()
                    .ifEmpty { video.str("title").ifEmpty { row.str("cell_name") } }
                if (title.isBlank()) continue
                val count = video.optInt("episode_cnt")
                items += Series(
                    id = id,
                    title = title,
                    cover = safeCover(video.str("cover").ifEmpty { video.str("cover_url") }),
                    description = video.str("video_desc"),
                    episodeCount = count,
                    tags = emptyList(),
                    source = SeriesSource.APP,
                )
            }
        }
        return SearchPage(
            items = items,
            page = page.coerceAtLeast(1),
            hasMore = tab.optBoolean("has_more", false) && page < 100,
        )
    }

    /**
     * 书城推荐流。对照 hongguoTV `RecommendationFeed`：
     * `GET .../reading/bookapi/bookmall/tab/v1/`，`tab_type=16`。
     * 手表首屏只取第一页；续页 / cell 无限流未接。
     */
    fun fetchRecommend(page: Int = 1): CatalogPage {
        require(page in 1..20)
        ensureDevice()
        val values = feedIdentity().toMutableMap()
        values.putAll(
            linkedMapOf(
                "tab_type" to "16",
                "bottom_tab_type" to "7",
                "landing_bottom_tab_type" to "7",
                "offset" to ((page - 1) * 12).toString(),
                "client_req_type" to if (page <= 1) "1" else "2",
            ),
        )
        var last: Exception? = null
        for (host in FEED_HOSTS) {
            try {
                val signed = signer.sign("https://$host/reading/bookapi/bookmall/tab/v1/", values)
                val root = JSONObject(execute(signed))
                if (root.optInt("code", -1) != 0) {
                    throw IllegalStateException("推荐暂不可用（code=${root.optInt("code")}）")
                }
                val items = parseBookmallTab(root)
                if (items.isEmpty()) throw IllegalStateException("推荐未返回可播放剧集")
                return CatalogPage(items = items, page = page, hasMore = page < 20)
            } catch (e: Exception) {
                last = e
            }
        }
        throw IllegalStateException(last?.message ?: "推荐暂时无法加载", last)
    }

    /**
     * App 热播榜。对照 jackotom/hongguo-drama `hongguo-app-catalog.js`：
     * `GET .../reading/bookapi/bookmall/cell/change/v`，
     * `selected_items` + `sub_selected_items` 指定榜单。
     * 手表首屏只取第 1 页（offset=0）；续页游标未接。
     */
    fun fetchRank(rankKey: String, page: Int = 1): CatalogPage {
        require(page in 1..20)
        val selectors = RANK_SELECTORS[rankKey]
            ?: throw IllegalArgumentException("未知榜单：$rankKey")
        ensureDevice()
        val values = feedIdentity().toMutableMap()
        values.putAll(
            linkedMapOf(
                "tab_type" to if (page <= 1) "26" else "18",
                "client_req_type" to "2",
                "client_template" to "2",
                "background_selected_items" to "",
                "category_id" to "0",
                "limit" to "20",
                "cell_sub_id" to "0",
                "gender" to "2",
                "cell_id" to RANK_CELL_ID,
                "selected_items" to selectors.first,
                "sub_selected_items" to selectors.second,
                "panel_selected_items" to "",
                "offset" to "0",
                "session_id" to "",
                "filter_ids" to "",
                "unlimited_selector_change_type" to "0",
            ),
        )
        var last: Exception? = null
        for (host in FEED_HOSTS) {
            for (path in RANK_PATHS) {
                try {
                    val signed = signer.sign("https://$host$path", values)
                    val root = JSONObject(execute(signed))
                    if (root.optInt("code", -1) != 0) {
                        throw IllegalStateException("榜单暂不可用（code=${root.optInt("code")}）")
                    }
                    val data = root.optJSONObject("data")
                        ?: throw IllegalStateException("榜单数据缺失")
                    val items = parseRankCellView(data, selectors)
                    if (items.isEmpty()) throw IllegalStateException("榜单未返回可播放剧集")
                    return CatalogPage(items = items, page = page, hasMore = false)
                } catch (e: Exception) {
                    last = e
                }
            }
        }
        throw IllegalStateException(last?.message ?: "榜单暂时无法加载", last)
    }

    /**
     * 分类落地页。对照 hongguoTV `DiscoverySelection` / `filteredDiscovery`：
     * `POST .../reading/distribution/category/landpage/v/`。
     * `need_selector_panel=true` 时解析 `selector_rows` 作为首页分类 Tab。
     */
    fun fetchCategory(
        filters: Map<String, String> = emptyMap(),
        offset: Int = 0,
        sessionId: String = "",
        withSelector: Boolean = offset == 0,
    ): CatalogPage {
        require(offset in 0..200_000)
        ensureDevice()
        val selected = JSONObject()
        for (key in SELECTOR_KEYS) {
            selected.put(key, JSONArray().apply {
                filters[key]?.takeIf { it.isNotBlank() }?.let { put(it) }
            })
        }
        val bodyObj = JSONObject()
            .put("offset", offset)
            .put("limit", 24)
            .put("need_selector_panel", withSelector)
            .put("select_items", selected)
            .put("session_id", sessionId)
            .put("req_type", if (offset == 0) "default" else "only_content")
            .put("client_req_type", 3)
            .put("filter_ids", "")
            .put(
                "req_scene",
                when (filters["genre"]) {
                    "comic_series", "ai_series" -> filters.getValue("genre")
                    else -> "default"
                },
            )
        val body = bodyObj.toString().toByteArray(Charsets.UTF_8)
        val values = feedIdentity()
        var last: Exception? = null
        for (host in FEED_HOSTS) {
            try {
                val signed = signer.sign(
                    "https://$host/reading/distribution/category/landpage/v/",
                    values,
                    body = body,
                )
                val root = JSONObject(execute(signed))
                if (root.optInt("code", -1) != 0) {
                    throw IllegalStateException("分类暂不可用（code=${root.optInt("code")}）")
                }
                val data = root.optJSONObject("data")
                    ?: throw IllegalStateException("分类数据缺失")
                val items = parseLandpageVideos(data)
                val categories = if (withSelector) parseSelectorCategories(data) else emptyList()
                if (items.isEmpty() && categories.isEmpty()) {
                    throw IllegalStateException("分类未返回内容")
                }
                return CatalogPage(
                    items = items,
                    page = 1,
                    hasMore = data.optBoolean("has_more", false),
                    categories = categories,
                )
            } catch (e: Exception) {
                last = e
            }
        }
        throw IllegalStateException(last?.message ?: "分类暂时无法加载", last)
    }

    fun fetchDetail(seriesId: String): SeriesDetail {
        val id = seriesId.trim()
        require(SERIES_ID.matches(id)) { "无效剧集 ID" }
        ensureDevice()

        val body = JSONObject()
            .put(
                "biz_param",
                JSONObject()
                    .put("detail_page_version", 0)
                    .put("disable_digg_stat", false)
                    .put("need_all_video_definition", false)
                    .put("need_mp4_align", false)
                    .put("source", 7)
                    .put("use_os_player", false)
                    .put("use_server_dns", false),
            )
            .put("dr_scene", "preload")
            .put("series_id", id)
            .toString()
            .toByteArray(Charsets.UTF_8)

        val values = identityQuery()
        val signed = signer.sign(
            "https://api5-normal-sinfonlineb.fqnovel.com/novel/player/multi_video_detail/preload/v1",
            values,
            body = body,
        )
        val root = JSONObject(execute(signed))
        if (root.optInt("code", -1) != 0) {
            throw IllegalStateException("App 分集接口暂不可用（code=${root.optInt("code")})")
        }
        val data = root.optJSONObject("data") ?: throw IllegalStateException("分集数据为空")
        if (!data.has(id)) throw IllegalStateException("返回剧集与请求不一致")
        val videoData = data.optJSONObject(id)?.optJSONObject("video_data")
            ?: throw IllegalStateException("无 video_data")
        val list = videoData.optJSONArray("video_list") ?: JSONArray()
        if (list.length() == 0) throw IllegalStateException("分集列表为空")

        val title = videoData.str("series_title").ifBlank { "未命名短剧" }
        val cover = safeCover(
            videoData.str("series_cover").ifEmpty { videoData.str("cover_url") },
        )
        val episodes = ArrayList<Episode>(list.length())
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val vid = item.str("vid")
            if (!SERIES_ID.matches(vid)) continue
            val number = item.optInt("vid_index", i + 1).coerceAtLeast(1)
            episodes += Episode(
                id = vid,
                number = number,
                title = item.str("title").ifBlank { "第 $number 集" },
                seriesId = id,
                seriesTitle = title,
                cover = safeCover(item.str("cover_url").ifEmpty { cover }),
            )
        }
        if (episodes.isEmpty()) throw IllegalStateException("分集列表为空")
        episodes.sortBy { it.number }

        return SeriesDetail(
            series = Series(
                id = id,
                title = title,
                cover = cover,
                description = videoData.str("series_intro").ifEmpty { videoData.str("video_desc") },
                episodeCount = episodes.size,
                source = SeriesSource.APP,
            ),
            episodes = episodes,
        )
    }

    /**
     * 相关作品 / 同系列季表。对照 hongguoTV：
     * `GET .../reading/bookapi/plan/v:version/` + `scene=10` + `from=detail_page_more_related`。
     */
    fun fetchRelated(seriesId: String, force: Boolean = false): List<RelatedWork> {
        val id = seriesId.trim()
        require(SERIES_ID.matches(id)) { "无效剧集 ID" }
        if (!force) relatedCache[id]?.let { return it }
        ensureDevice()

        val values = identityQuery().toMutableMap()
        for (k in listOf("version_code", "manifest_version_code", "update_version_code", "pv_player")) {
            values[k] = "72232"
        }
        values["version_name"] = "7.2.2.32"
        values.putAll(
            mapOf(
                "scene" to "10",
                "from" to "detail_page_more_related",
                "book_id" to id,
                "new_search_middle_page" to "false",
                "bookstore_tab_type" to "0",
                "query_history_removed" to "false",
                "user_is_login" to "0",
                "bookstore_tab" to "0",
                "search_middle_page_version" to "0",
                "pad_column_detail" to "0",
                "hot_word_exchange" to "false",
                "device_level" to "3",
                "limit" to "0",
                "offset" to "0",
                "is_horizontal_screen" to "false",
                "reader_single_col_from_ip_strategy" to "false",
            ),
        )

        var lastError: Exception? = null
        for (host in RELATED_HOSTS) {
            try {
                val signed = signer.sign("https://$host/reading/bookapi/plan/v:version/", values)
                val raw = execute(signed)
                if (raw.isBlank()) {
                    lastError = IllegalStateException("相关作品接口暂无响应")
                    continue
                }
                val rows = RelatedWorks.parse(raw, id)
                relatedCache[id] = rows
                return rows
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw IllegalStateException(
            lastError?.message ?: "相关作品暂时无法加载",
            lastError,
        )
    }

    fun fetchStream(seriesId: String, episodeId: String, maxHeight: Int): StreamInfo {
        require(SERIES_ID.matches(seriesId.trim())) { "无效剧集 ID" }
        val vid = episodeId.trim()
        require(SERIES_ID.matches(vid)) { "无效分集 ID" }
        ensureDevice()

        val device = identityQuery()
        val ladder = RequestScope.withDeadline(
            System.nanoTime() + TimeUnit.SECONDS.toNanos(12),
        ) {
            MobilePlayback.ladder(vid, device, client, MobilePlayback.signer, clock())
        }
        val selected = ladder.select(maxHeight.coerceIn(240, 1080)) {
            // 手表优先 AVC；加密流仍可返回（Player 侧走 RemoteVideo）
            true
        } ?: throw IllegalStateException("没有可播放清晰度（上限 ${maxHeight}p）")

        val qualityLabel = if (selected.quality > 0) {
            "${selected.quality}p" + if (selected.quality > maxHeight) "（兼容）" else ""
        } else {
            "auto"
        }
        return StreamInfo(
            url = selected.url,
            quality = qualityLabel,
            isPreview = false,
            referer = VIDEO_REFERER,
            userAgent = VendorConstants.PLAYER_UA,
            decryptionKey = selected.key,
        )
    }

    @Synchronized
    private fun ensureDevice() {
        val existingId = prefs.getString(KEY_DEVICE_ID, "").orEmpty()
        val existingIid = prefs.getString(KEY_IID, "").orEmpty()
        if (DEVICE_ID.matches(existingId) && DEVICE_ID.matches(existingIid)) {
            if (prefs.getString(KEY_CDID, "").isNullOrBlank()) {
                prefs.edit().putString(KEY_CDID, UUID.randomUUID().toString()).apply()
            }
            return
        }
        val body = DeviceRegister.payload(VendorConstants.device)
        val request = Request.Builder().url(DeviceRegister.url())
        DeviceRegister.headers(body, VendorConstants.device).forEach { (k, v) -> request.header(k, v) }
        request.post(body.toRequestBody("application/octet-stream".toMediaType()))
        val transport = client.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
        val registered = RequestScope.execute(transport.newCall(request.build())) { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("设备登记失败（HTTP ${response.code}）")
            }
            val text = response.body?.string().orEmpty()
            DeviceRegister.parse(text, response.headers("Set-Cookie"))
                ?: throw IllegalStateException("设备登记响应无效")
        }
        prefs.edit()
            .putString(KEY_DEVICE_ID, registered.deviceId)
            .putString(KEY_IID, registered.installId)
            .putString(KEY_CDID, UUID.randomUUID().toString())
            .apply()
    }

    private fun identityQuery(): Map<String, String> {
        ensureDevice()
        return VendorConstants.device + mapOf(
            "device_id" to prefs.getString(KEY_DEVICE_ID, "").orEmpty(),
            "iid" to prefs.getString(KEY_IID, "").orEmpty(),
            "cdid" to prefs.getString(KEY_CDID, "").orEmpty(),
        )
    }

    /** 推荐 / 分类用 73532 版本组合（对照 hongguoTV RecommendationFeed 游客态）。 */
    private fun feedIdentity(): Map<String, String> {
        val base = identityQuery().toMutableMap()
        for (k in listOf("version_code", "manifest_version_code", "update_version_code", "pv_player")) {
            base[k] = "73532"
        }
        base["version_name"] = "7.3.5.32"
        return base
    }

    private fun parseBookmallTab(root: JSONObject): List<Series> {
        val tab = root.optJSONObject("data")?.optJSONArray("tab_item")?.objects()
            ?.firstOrNull { it.str("tab_type") == "16" || it.optInt("tab_type") == 16 }
            ?: throw IllegalStateException("推荐数据格式已变化")
        val rows = tab.optJSONArray("cell_data")?.objects().orEmpty().flatMap { cell ->
            cell.optJSONArray("cell_data")?.objects().orEmpty().flatMap { card ->
                card.optJSONArray("video_data")?.objects().orEmpty().map {
                    it.optJSONObject("video_detail") ?: it
                }
            }
        }
        return rows.mapNotNull { row -> seriesFromFeedRow(row) }.distinctBy { it.id }
    }

    /**
     * 热播榜响应：`data.cell_view.cell_data[].video_data`。
     * 若带 selector，核对外层/子层选中项与请求一致；演员榜（仅 ugc_user_data）拒绝。
     */
    private fun parseRankCellView(
        data: JSONObject,
        selectors: Pair<String, String>,
    ): List<Series> {
        val view = data.optJSONObject("cell_view")
            ?: throw IllegalStateException("榜单结构异常")
        val selected = view.optJSONObject("cell_selector")
            ?.optJSONObject("outer_row")
            ?.optJSONArray("items")
            ?.objects()
            ?.firstOrNull { it.optBoolean("is_selected", false) }
        if (selected != null) {
            val outer = selected.str("selector_item_id")
            val child = selected.optJSONObject("sub_cell_selector")
                ?.optJSONObject("outer_row")
                ?.optJSONArray("items")
                ?.objects()
                ?.firstOrNull { it.optBoolean("is_selected", false) }
                ?.str("selector_item_id")
            if (outer != selectors.first || child != selectors.second) {
                throw IllegalStateException("榜单返回与请求不一致")
            }
        }
        val cells = view.optJSONArray("cell_data")?.objects().orEmpty()
        val rows = cells.flatMap { cell ->
            when (val video = cell.opt("video_data")) {
                is JSONArray -> video.objects()
                is JSONObject -> listOf(video)
                else -> emptyList()
            }
        }
        if (rows.isEmpty() && cells.any { it.optJSONArray("ugc_user_data")?.length() ?: 0 > 0 }) {
            throw IllegalStateException("返回了演员榜，未作为短剧展示")
        }
        val allowed = RANK_CONTENT_TYPES[selectors.first]
        return rows.mapNotNull { row ->
            if (allowed != null) {
                val type = row.optInt("content_type", -1)
                if (type !in allowed) return@mapNotNull null
            }
            seriesFromRankRow(row)
        }.distinctBy { it.id }
    }

    /** 榜单行：题材标签 + 热度文案（收藏/点赞）进 tags，供列表 meta。 */
    private fun seriesFromRankRow(row: JSONObject): Series? {
        val base = seriesFromFeedRow(row) ?: return null
        val genres = row.optJSONArray("sub_title_list")?.objects().orEmpty().mapNotNull { item ->
            val type = item.optInt("data_type", -1)
            if (type != 3 && type != 32) return@mapNotNull null
            item.str("content").takeIf { it.isNotBlank() && it.length <= 12 }
        }.distinct().take(2)
        val heat = row.optJSONArray("secondary_info_list")?.objects().orEmpty()
            .map { it.str("content") }
            .firstOrNull { content ->
                content.isNotBlank() && (
                    content.contains("收藏") ||
                        content.contains("点赞") ||
                        content.contains("热") ||
                        content.contains("评分") ||
                        content.contains("播放")
                    )
            }
            ?.takeIf { it.length <= 16 }
            ?: formatPlayCount(row.optLong("play_cnt", 0L))
        val tags = buildList {
            if (!heat.isNullOrBlank()) add(heat)
            addAll(genres)
        }.distinct().take(3)
        return base.copy(
            tags = tags.ifEmpty { base.tags },
            description = base.description.ifBlank { row.str("sub_title") },
        )
    }

    private fun formatPlayCount(count: Long): String? {
        if (count <= 0L) return null
        return when {
            count >= 100_000_000L -> String.format("%.1f亿播放", count / 100_000_000.0)
            count >= 10_000L -> String.format("%.1f万播放", count / 10_000.0)
            else -> "${count}播放"
        }
    }

    private fun parseLandpageVideos(data: JSONObject): List<Series> {
        val rows = data.optJSONArray("video_data")?.objects().orEmpty()
        return rows.mapNotNull { row -> seriesFromFeedRow(row) }.distinctBy { it.id }
    }

    private fun seriesFromFeedRow(row: JSONObject): Series? {
        val id = row.str("series_id").ifBlank { row.str("book_id") }
        if (!SERIES_ID.matches(id)) return null
        val title = row.str("series_title")
            .ifBlank { row.str("book_name") }
            .ifBlank { row.str("title") }
        if (title.isBlank()) return null
        val count = row.optInt("episode_cnt")
        return Series(
            id = id,
            title = title,
            cover = safeCover(
                row.str("series_cover").ifBlank { row.str("cover").ifBlank { row.str("cover_url") } },
            ),
            description = row.str("series_intro").ifBlank { row.str("video_desc") },
            episodeCount = count,
            source = SeriesSource.APP,
        )
    }

    /** 从 selector_rows 抽出类型 + 主题，拼成手表横向分类 Tab。 */
    private fun parseSelectorCategories(data: JSONObject): List<HomeCategory> {
        val rows = data.optJSONArray("selector_rows")?.objects().orEmpty()
        val out = mutableListOf<HomeCategory>()
        val seen = HashSet<String>()
        for (row in rows) {
            val type = row.str("type")
            if (type !in setOf("genre", "category_dim_theme")) continue
            val options = row.optJSONArray("items")?.objects().orEmpty()
            for (opt in options) {
                val sid = opt.str("selector_item_id")
                val name = opt.str("show_name")
                if (!SELECTOR_ID.matches(sid) || name.isBlank() || name.length > 40) continue
                val id = "$type:$sid"
                if (!seen.add(id)) continue
                out += HomeCategory(
                    id = id,
                    label = name,
                    filters = mapOf(type to sid),
                )
            }
        }
        return out.take(48)
    }

    private fun execute(signed: Signer.Request): String {
        val builder = Request.Builder()
            .url(signed.url)
            .header("User-Agent", VendorConstants.VIDEO_UA)
            .header("Accept-Language", "zh-CN,zh;q=0.9")
        signed.headers.forEach { (k, v) -> builder.header(k, v) }
        signed.body?.let {
            builder.post(it.toRequestBody("application/json; charset=utf-8".toMediaType()))
        }
        return RequestScope.execute(client.newCall(builder.build())) { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}")
            }
            val text = response.body?.string().orEmpty()
            if (text.isBlank()) throw IllegalStateException("上游返回空响应")
            text
        }
    }

    companion object {
        private const val PREFS = "app_source_device"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_IID = "iid"
        private const val KEY_CDID = "cdid"
        private const val VIDEO_REFERER = "https://novel.snssdk.com/"
        private val SERIES_ID = Regex("^[1-9][0-9]{5,29}$")
        private val DEVICE_ID = Regex("^[1-9][0-9]{0,29}$")
        private val RELATED_HOSTS = listOf(
            "api5-normal-sinfonlinec.fqnovel.com",
            "api5-normal-hl.fqnovel.com",
            "api5-normal-lf.fqnovel.com",
            "api5-normal-sinfonlineb.fqnovel.com",
        )
        private val FEED_HOSTS = listOf(
            "api5-normal-sinfonlinea.fqnovel.com",
            "api5-normal-hl.fqnovel.com",
            "api5-normal-lf.fqnovel.com",
            "api5-normal-sinfonlineb.fqnovel.com",
        )
        /** drama 现行 `/v`；TV 推荐续刷用 `/v1/`，作备选。 */
        private val RANK_PATHS = listOf(
            "/reading/bookapi/bookmall/cell/change/v",
            "/reading/bookapi/bookmall/cell/change/v1/",
        )
        private const val RANK_CELL_ID = "7470092475068071998"
        private val RANK_SELECTORS = mapOf(
            "hot-drama" to ("all" to "ranklist_hot_play_sc"),
            "hot-real-drama" to ("human" to "human_hot_play"),
            "hot-comic-drama" to ("comic_series_rank" to "comic_series_hot_play"),
            "hot-ai-drama" to ("ai_playlet" to "ai_playlet_hot_play"),
        )
        /** 与 drama `cards()` 一致：综合=真人+漫剧，其余按类型过滤。 */
        private val RANK_CONTENT_TYPES = mapOf(
            "all" to setOf(1, 1004),
            "human" to setOf(1),
            "comic_series_rank" to setOf(1004),
            "ai_playlet" to setOf(1004),
        )
        private val SELECTOR_KEYS = listOf(
            "genre",
            "category_dim_theme",
            "category_dim_role",
            "category_dim_epoch",
            "sort",
            "gender",
            "online_time",
            "creation_status",
        )
        private val SELECTOR_ID = Regex("^[a-zA-Z0-9_-]{1,80}$")

        private fun defaultClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
        }

        private fun safeCover(value: String): String {
            val url = cleanUrl(value)
            return if (url.startsWith("http://") || url.startsWith("https://")) url else ""
        }
    }
}
