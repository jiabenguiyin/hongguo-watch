package com.hongguo.watch.data

import com.hongguo.watch.data.model.StreamInfo
import com.hongguo.watch.upstream.RequestScope
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** A narrow, public 360p fallback used only after the signed playback routes fail. */
internal class PublicPlaybackFallback(
    private val baseUrl: HttpUrl = ORIGIN.toHttpUrl(),
    client: OkHttpClient = OkHttpClient(),
) {
    private val http = client.newBuilder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2500, TimeUnit.MILLISECONDS)
        .callTimeout(4, TimeUnit.SECONDS)
        .cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .cache(null)
        .build()

    fun resolve(seriesId: String, episodeId: String): StreamInfo? {
        val series = seriesId.trim()
        val item = episodeId.trim()
        if (!ID.matches(series) || !ID.matches(item)) return null

        return RequestScope.withDeadline(System.nanoTime() + FALLBACK_BUDGET_NANOS) {
            RequestScope.ensureActive()
            if (readMetadata(series, item) == null) return@withDeadline null

            val mediaUrl = mediaUrl(item)
            if (!hasPlayableMp4Range(mediaUrl)) return@withDeadline null

            StreamInfo(
                url = mediaUrl.toString(),
                quality = QUALITY_LABEL,
                referer = "$ORIGIN/",
                userAgent = USER_AGENT,
                codec = "h264",
                sourceLabel = SOURCE_LABEL,
            )
        }
    }

    internal fun mediaUrl(episodeId: String): HttpUrl =
        baseUrl.newBuilder()
            .addPathSegments("video/public/$episodeId/360p.mp4")
            .build()

    private fun metadataUrl(seriesId: String, episodeId: String): HttpUrl =
        baseUrl.newBuilder()
            .addPathSegments("player/$seriesId/$episodeId")
            .build()

    private fun readMetadata(seriesId: String, episodeId: String): Int? {
        val request = Request.Builder()
            .url(metadataUrl(seriesId, episodeId))
            .header("User-Agent", USER_AGENT)
            .header("Accept-Encoding", "identity")
            .header("Referer", "$ORIGIN/")
            .get()
            .build()
        return try {
            RequestScope.execute(http.newCall(request)) { response ->
                if (response.code != 200) return@execute null
                val body = response.body ?: return@execute null
                if (body.contentLength() > MAX_METADATA_BYTES) return@execute null
                val source = body.source()
                source.request(MAX_METADATA_BYTES + 1)
                if (source.buffer.size > MAX_METADATA_BYTES) return@execute null
                metadataEpisodeNumber(source.readUtf8(), seriesId, episodeId)
            }
        } catch (_: IOException) {
            RequestScope.ensureActive()
            null
        }
    }

    private fun hasPlayableMp4Range(url: HttpUrl): Boolean {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Encoding", "identity")
            .header("Range", "bytes=0-31")
            .header("Referer", "$ORIGIN/")
            .get()
            .build()
        return RequestScope.execute(http.newCall(request)) { response ->
            if (response.code != HTTP_PARTIAL) return@execute false
            val contentType = response.header("Content-Type")
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase()
            if (contentType != null && contentType != "video/mp4") return@execute false
            val total = response.header("Content-Range")
                ?.let(CONTENT_RANGE::matchEntire)
                ?.groupValues
                ?.getOrNull(1)
                ?.toLongOrNull()
                ?: return@execute false
            if (total <= RANGE_BYTES) return@execute false

            val source = response.body?.source() ?: return@execute false
            source.request(RANGE_BYTES + 1)
            if (source.buffer.size != RANGE_BYTES) return@execute false
            isMp4Header(source.readByteArray())
        }
    }

    companion object {
        const val ORIGIN = "https://hongguoduanju.com"
        const val SOURCE_LABEL = "public-360p"
        const val QUALITY_LABEL = "360p"
        const val USER_AGENT = "HongguoTV/0.5.12"
        private const val HTTP_PARTIAL = 206
        private const val MAX_METADATA_BYTES = 1024L * 1024L
        private const val RANGE_BYTES = 32L
        private const val FALLBACK_BUDGET_NANOS = 6_000_000_000L
        private val ID = Regex("[0-9]{8,24}")
        private val CONTENT_RANGE = Regex("bytes 0-31/([0-9]+)")
        private val JSON_LD_SCRIPT =
            Regex("(?is)<script[^>]*type=[\"']application/ld\\+json[\"'][^>]*>(.*?)</script>")

        internal fun metadataEpisodeNumber(
            html: String,
            seriesId: String,
            episodeId: String,
        ): Int? {
            for (match in JSON_LD_SCRIPT.findAll(html)) {
                val root = try {
                    JSONObject(match.groupValues[1])
                } catch (_: JSONException) {
                    continue
                }
                findEpisode(root, seriesId, episodeId)?.let { return it }
            }
            return null
        }

        private fun findEpisode(root: JSONObject, seriesId: String, episodeId: String): Int? {
            val nodes = when (val graph = root.opt("@graph")) {
                is JSONArray -> (0 until graph.length()).mapNotNull { graph.optJSONObject(it) }
                is JSONObject -> listOf(graph)
                else -> listOf(root)
            }
            val episodes = nodes.flatMap { node ->
                val episodes = node.opt("episode")
                when (episodes) {
                    is JSONArray -> (0 until episodes.length()).mapNotNull { episodes.optJSONObject(it) }
                    is JSONObject -> listOf(episodes)
                    else -> emptyList()
                }
            } + nodes.filter { node ->
                node.opt("@type") == "TVEpisode" || node.opt("@type") == "Episode"
            }
            val seriesToken = "series_id=$seriesId"
            val episodePath = "/player/$seriesId/$episodeId"
            val match = episodes.firstOrNull { episode ->
                val url = episode.optString("url")
                val nodeId = episode.optString("@id")
                url.contains(episodePath) &&
                    nodeId.contains(episodePath) &&
                    episode.optJSONObject("partOfSeason")
                        ?.optString("@id")
                        ?.contains(seriesToken) == true
            } ?: return null
            val episodeNumber = match.optInt("episodeNumber")
            if (episodeNumber <= 0) return null
            return episodeNumber
        }

        internal fun isMp4Header(bytes: ByteArray): Boolean =
            bytes.size == RANGE_BYTES.toInt() &&
                bytes.copyOfRange(4, 8).contentEquals(byteArrayOf('f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte()))
    }
}
