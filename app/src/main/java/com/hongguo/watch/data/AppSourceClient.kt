package com.hongguo.watch.data

import android.content.Context
import com.hongguo.watch.data.model.Episode
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
