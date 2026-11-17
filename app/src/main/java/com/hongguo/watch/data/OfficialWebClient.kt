package com.hongguo.watch.data

import com.hongguo.watch.data.model.Episode
import com.hongguo.watch.data.model.SearchPage
import com.hongguo.watch.data.model.Series
import com.hongguo.watch.data.model.SeriesDetail
import com.hongguo.watch.data.model.SeriesSource
import com.hongguo.watch.data.model.StreamInfo
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * 【已弃用 / 不推荐】官网 hongguoduanju.com 路径。
 *
 * 片源少且限制多；默认关闭。仅当设置中显式开启「已弃用官网源」时使用。
 * 默认片源请用 [AppSourceClient]（开源 App 族）。
 *
 * 对照 jackotom/hongguo-drama 的 hongguo-search.js / hongguo.js（官网优先策略，手表不默认采用）。
 */
class OfficialWebClient(
    private val client: OkHttpClient = defaultClient(),
) {
    fun search(query: String, page: Int = 1): SearchPage {
        val keyword = query.trim()
        require(keyword.isNotEmpty()) { "请输入搜索词" }

        val url = "$SITE/api/search/result".toHttpUrl().newBuilder()
            .addQueryParameter("aid", "8662")
            .addQueryParameter("query", keyword)
            .addQueryParameter("page", page.coerceIn(1, 50).toString())
            .build()

        val body = executeJson(url.toString())
        val list = body.optJSONArray("searchList") ?: JSONArray()
        val items = ArrayList<Series>(list.length())
        val seen = HashSet<String>()
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            val video = item.optJSONObject("video_data")?.takeIf { it.length() > 0 } ?: item
            val id = (video.optString("series_id")
                .ifBlank { item.optString("series_id") }
                .ifBlank { item.optString("keyword") }).trim()
            val title = cleanText(
                video.optString("series_name")
                    .ifBlank { video.optString("series_title") }
                    .ifBlank { item.optString("name") }
                    .ifBlank { video.optString("title") },
            )
            if (!SERIES_ID.matcher(id).matches() || title.isBlank() || !seen.add(id)) continue
            val episodeCount = video.optInt("episode_cnt", 0).takeIf { it > 0 }
                ?: Regex("""(\d+)\s*集""").find(video.optString("episode_right_text"))
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: 0
            items += Series(
                id = id,
                title = title,
                cover = safeCover(video.optString("series_cover").ifBlank { video.optString("cover") }),
                description = cleanText(video.optString("series_intro")),
                episodeCount = episodeCount,
                tags = tagsOf(video),
                source = SeriesSource.OFFICIAL,
            )
        }
        return SearchPage(
            items = items,
            page = body.optInt("pageNum", page).coerceAtLeast(1),
            hasMore = body.optBoolean("hasMore", false),
        )
    }

    fun fetchDetail(seriesId: String): SeriesDetail {
        val id = seriesId.trim()
        require(SERIES_ID.matcher(id).matches()) { "无效剧集 ID" }

        val errors = mutableListOf<String>()
        for (url in listOf("$SITE/detail?series_id=$id", "$SITE/player/$id")) {
            try {
                return parseDetailHtml(executeText(url), id)
            } catch (e: Exception) {
                errors += "${e.message}"
            }
        }
        throw IllegalStateException(errors.joinToString("；").ifBlank { "分集加载失败" })
    }

    fun fetchStream(seriesId: String, episodeId: String, maxHeight: Int): StreamInfo {
        val sid = seriesId.trim()
        val vid = episodeId.trim()
        require(SERIES_ID.matcher(sid).matches() && SERIES_ID.matcher(vid).matches()) {
            "无效播放参数"
        }

        val html = executeText("$SITE/player/$sid/$vid")
        val router = readRouterData(html)
        val page = router.optJSONObject("loaderData")
            ?.optJSONObject("player_(series_id)/(vid)/page")
            ?: throw IllegalStateException("播放页缺少 loaderData")

        if (page.optString("vid") != vid) {
            throw IllegalStateException("播放页分集不匹配")
        }

        val protectedPlayback = page.optBoolean("protectedPlayback", false)
        val contentUrl = extractVideoObjectContentUrl(html, sid, vid)
            ?: synthesizePublicCandidate(vid, maxHeight)

        if (contentUrl.isNullOrBlank()) {
            throw IllegalStateException("暂无可用播放地址（官网可能仅预览或需 App 签名路径）")
        }

        val quality = Regex("""/(\d+p)\.mp4""", RegexOption.IGNORE_CASE)
            .find(contentUrl)?.groupValues?.getOrNull(1)
            ?: "web"

        return StreamInfo(
            url = contentUrl,
            quality = quality,
            isPreview = protectedPlayback || contentUrl.contains("/video/public/"),
            referer = "$SITE/",
            userAgent = WEB_UA,
        )
    }

    private fun parseDetailHtml(html: String, seriesId: String): SeriesDetail {
        val router = readRouterData(html)
        val loader = router.optJSONObject("loaderData") ?: throw IllegalStateException("无 loaderData")
        val page = loader.optJSONObject("detail_page")
            ?: loader.optJSONObject("player_(series_id)/page")
            ?: loader.optJSONObject("player_(series_id)/(vid)/page")
            ?: throw IllegalStateException("无详情页数据")

        val detail = page.optJSONObject("seriesDetail")
            ?: throw IllegalStateException("无 seriesDetail")
        val actualId = detail.optString("series_id")
        if (actualId != seriesId) {
            throw IllegalStateException("官网返回的剧集与请求不一致")
        }

        val vids = detail.optJSONArray("vid_list") ?: throw IllegalStateException("无 vid_list")
        if (vids.length() == 0) throw IllegalStateException("分集列表为空")

        val title = detail.optString("series_name").ifBlank { "未命名短剧" }
        val cover = safeCover(detail.optString("series_cover"))
        val intro = cleanText(detail.optString("series_intro"))
        val episodes = ArrayList<Episode>(vids.length())
        for (i in 0 until vids.length()) {
            val vid = vids.optString(i).trim()
            if (!SERIES_ID.matcher(vid).matches()) {
                throw IllegalStateException("官网分集数据异常")
            }
            episodes += Episode(
                id = vid,
                number = i + 1,
                title = "第 ${i + 1} 集",
                seriesId = seriesId,
                seriesTitle = title,
                cover = cover,
            )
        }

        return SeriesDetail(
            series = Series(
                id = seriesId,
                title = title,
                cover = cover,
                description = intro,
                episodeCount = episodes.size,
                source = SeriesSource.OFFICIAL,
            ),
            episodes = episodes,
        )
    }

    private fun extractVideoObjectContentUrl(html: String, seriesId: String, vid: String): String? {
        val matcher = LD_JSON.matcher(html)
        while (matcher.find()) {
            val raw = matcher.group(1) ?: continue
            try {
                val root = JSONObject(raw)
                val candidates = mutableListOf<JSONObject>()
                if (root.optString("@type") == "VideoObject") {
                    candidates += root
                }
                root.optJSONArray("@graph")?.let { graph ->
                    for (i in 0 until graph.length()) {
                        val node = graph.optJSONObject(i) ?: continue
                        if (node.optString("@type") == "VideoObject") candidates += node
                    }
                }
                for (video in candidates) {
                    val contentUrl = normalizeUrl(video.optString("contentUrl"))
                    if (contentUrl.isBlank()) continue
                    if (!contentUrl.contains(vid) && !contentUrl.contains("/video/public/$vid/")) {
                        continue
                    }
                    val pageUrl = normalizeUrl(video.optString("url"))
                    if (pageUrl.isNotBlank() &&
                        !pageUrl.contains("/player/$seriesId") &&
                        !pageUrl.contains("/player/$seriesId/$vid")
                    ) {
                        continue
                    }
                    if (contentUrl.startsWith("http")) return contentUrl
                }
            } catch (_: Exception) {
                // keep scanning other script blocks
            }
        }
        return null
    }

    private fun synthesizePublicCandidate(vid: String, maxHeight: Int): String? {
        val qualities = listOf(480, 360, 240).filter { it <= maxHeight.coerceAtLeast(240) }
        for (q in qualities) {
            val candidate = "$SITE/video/public/$vid/${q}p.mp4"
            if (headOk(candidate)) return candidate
        }
        return null
    }

    private fun headOk(url: String): Boolean {
        val request = Request.Builder()
            .url(url)
            .head()
            .header("User-Agent", WEB_UA)
            .header("Referer", "$SITE/")
            .build()
        return runCatching {
            client.newCall(request).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    private fun executeJson(url: String): JSONObject {
        val text = executeText(url, accept = "application/json, text/plain, */*")
        return JSONObject(text)
    }

    private fun executeText(url: String, accept: String = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", WEB_UA)
            .header("Accept", accept)
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("Referer", "$SITE/")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}")
            }
            return response.body?.string().orEmpty()
        }
    }

    private fun readRouterData(html: String): JSONObject {
        val marker = "_ROUTER_DATA = "
        val start = html.indexOf(marker)
        if (start < 0) throw IllegalStateException("页面无 _ROUTER_DATA")
        var i = start + marker.length
        while (i < html.length && html[i].isWhitespace()) i++
        if (i >= html.length || html[i] != '{') throw IllegalStateException("_ROUTER_DATA 格式异常")
        var depth = 0
        val begin = i
        while (i < html.length) {
            when (html[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return JSONObject(html.substring(begin, i + 1))
                    }
                }
            }
            i++
        }
        throw IllegalStateException("_ROUTER_DATA 截断")
    }

    companion object {
        private const val SITE = "https://hongguoduanju.com"
        private const val WEB_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
        private val SERIES_ID: Pattern = Pattern.compile("^\\d{6,30}$")
        private val LD_JSON: Pattern = Pattern.compile(
            "<script\\b[^>]*type\\s*=\\s*[\"']application/ld\\+json[\"'][^>]*>([\\s\\S]*?)</script\\s*>",
            Pattern.CASE_INSENSITIVE,
        )

        private fun defaultClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(12, TimeUnit.SECONDS)
                .writeTimeout(8, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
        }

        private fun cleanText(value: String): String {
            return value
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&amp;", "&")
                .trim()
        }

        private fun normalizeUrl(value: String): String {
            return value
                .replace("\\u002F", "/")
                .replace("\\/", "/")
                .replace("\\u0026", "&")
                .replace("&amp;", "&")
                .trim()
        }

        private fun safeCover(value: String): String {
            val url = normalizeUrl(value)
            return if (url.startsWith("http://") || url.startsWith("https://")) url else ""
        }

        private fun tagsOf(video: JSONObject): List<String> {
            val tags = mutableListOf<String>()
            val list = video.optJSONArray("tags") ?: video.optJSONArray("category_list") ?: return tags
            for (i in 0 until list.length()) {
                val item = list.opt(i)
                val label = when (item) {
                    is String -> item
                    is JSONObject -> item.optString("name")
                        .ifBlank { item.optString("tag_name") }
                        .ifBlank { item.optString("title") }
                    else -> ""
                }.trim()
                if (label.isNotEmpty() && label !in tags) tags += label
                if (tags.size >= 4) break
            }
            return tags
        }
    }
}
