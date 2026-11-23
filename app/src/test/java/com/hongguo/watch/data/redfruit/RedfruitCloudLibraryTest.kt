package com.hongguo.watch.data.redfruit

import com.hongguo.watch.data.db.WatchHistoryEntity
import com.hongguo.watch.upstream.VendorConstants
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class RedfruitCloudLibraryTest {
    @Test
    fun uploadProgressPostsOfficialBodyAndDoesNotRetry() = runBlocking {
        val requests = mutableListOf<okhttp3.Request>()
        val cloud = cloud(requests)
        cloud.uploadProgress(
            credentials,
            WatchHistoryEntity(
                seriesId = "9999999999999999999",
                seriesTitle = "测试",
                cover = "",
                episodeId = "9999999999999999997",
                episodeNumber = 3,
                positionMs = 43_210,
                durationMs = 90_000,
                updatedAt = 1_792_500_000_000,
                source = "APP",
            ),
            episodeCount = 80,
        )

        assertEquals(1, requests.size)
        val request = requests.single()
        assertEquals("POST", request.method)
        assertEquals("/reading/bookapi/read_progress/upload/v1/", request.url.encodedPath)
        assertEquals("application/json; charset=UTF-8", request.body?.contentType().toString())
        val body = JSONObject(request.bodyText())
        assertEquals(1, body.getInt("write_mode"))
        val entry = body.getJSONArray("books").getJSONObject(0)
        assertEquals("9999999999999999999", entry.getString("book_id"))
        assertEquals("9999999999999999997", entry.getString("item_id"))
        assertEquals(3, entry.getInt("vid_index"))
        assertEquals(43_210L, entry.getLong("current_play_time"))
        assertEquals(90_000L, entry.getLong("current_total_time"))
        assertEquals(80, entry.getInt("episode_count"))
        assertEquals(1_792_500_000_000L, entry.getLong("read_timestamp_ms"))
        assertEquals(43_210.0 / 90_000.0, entry.getString("progress_rate").toDouble(), 0.000001)
        assertTrue(request.header("x-ss-stub").orEmpty().isNotBlank())
    }

    @Test
    fun recentProgressUsesHongguoTvReadBackEndpoint() = runBlocking {
        val requests = mutableListOf<okhttp3.Request>()
        val cloud = cloud(
            requests,
            responseBody = """{"code":0,"data":{"video_data":[{"book_id":"12345","vid":"67890","vid_index":4,"current_play_time":21000,"current_total_time":90000,"last_watch_time":1792500000000}]}}""",
        )

        val rows = cloud.recentProgress(credentials)

        assertEquals(1, requests.size)
        assertEquals("/reading/bookapi/read_progress/list/v1/", requests.single().url.encodedPath)
        assertEquals("2", requests.single().url.queryParameter("book_types"))
        assertEquals("50", requests.single().url.queryParameter("limit"))
        assertEquals("12345", rows.single().seriesId)
        assertEquals("67890", rows.single().episodeId)
        assertEquals(4, rows.single().episodeNumber)
        assertEquals(21_000L, rows.single().positionMs)
    }

    @Test
    fun fullHistoryKeepsFurthestEpisodeWhenCloudReturnsDuplicateSeriesRows() = runBlocking {
        val requests = mutableListOf<okhttp3.Request>()
        val cloud = cloud(
            requests,
            responseBody = """{"code":0,"data":{"data_list":[
                {"book_id_str":"12345","book_name":"示例剧","vid_str":"300","vid_index":300,"current_play_time":1000,"current_total_time":10000,"read_timestamp_ms":2000},
                {"book_id_str":"12345","book_name":"示例剧","vid_str":"350","vid_index":350,"current_play_time":500,"current_total_time":10000,"read_timestamp_ms":1000}
            ],"has_more":false}}""",
        )

        val rows = cloud.history(credentials)

        assertEquals(1, requests.size)
        assertEquals(1, rows.size)
        assertEquals(350, rows.single().episodeNumber)
        assertEquals("12345", rows.single().seriesId)
    }

    @Test
    fun historyKeepsFurthestEpisodeWhenCloudReturnsDuplicateSeriesRows() = runBlocking {
        val requests = mutableListOf<okhttp3.Request>()
        val cloud = cloud(
            requests,
            responseBody = """{"code":0,"data":{"data_list":[
                {"book_id_str":"12345","book_name":"示例剧","vid_str":"300","vid_index":300,"current_play_time":1000,"current_total_time":10000,"read_timestamp_ms":2000},
                {"book_id_str":"12345","book_name":"示例剧","vid_str":"350","vid_index":350,"current_play_time":500,"current_total_time":10000,"read_timestamp_ms":1000}
            ],"has_more":false}}""",
        )

        val rows = cloud.history(credentials)

        assertEquals(1, rows.size)
        assertEquals(350, rows.single().episodeNumber)
        assertEquals("12345", rows.single().seriesId)
    }

    @Test
    fun setFavoriteUsesOfficialVideoShelfPayloadForAddAndRemove() = runBlocking {
        val requests = mutableListOf<okhttp3.Request>()
        val cloud = cloud(requests)

        cloud.setFavorite(credentials, "9999999999999999999", add = true)
        cloud.setFavorite(credentials, "9999999999999999999", add = false)

        assertEquals(2, requests.size)
        val add = JSONObject(requests[0].bodyText())
        assertEquals(false, add.getBoolean("is_cancelled"))
        val addEntry = add.getJSONArray("update_bookshelf_video_list").getJSONObject(0)
        assertEquals("9999999999999999999", addEntry.getString("book_id"))
        assertEquals(2, addEntry.getInt("book_type"))
        assertEquals(0, addEntry.getInt("video_shelf_operate_type"))
        assertTrue(add.has("shark_extra"))

        val remove = JSONObject(requests[1].bodyText())
        val removeEntry = remove.getJSONArray("update_bookshelf_video_list").getJSONObject(0)
        assertEquals(1, removeEntry.getInt("video_shelf_operate_type"))
        assertEquals("", removeEntry.getString("group_name"))
        assertFalse(remove.has("shark_extra"))
    }

    @Test
    fun writeRequestNeverFollowsRedirectOrRetriesTransportFailure() = runBlocking {
        val requests = mutableListOf<okhttp3.Request>()
        val cloud = cloud(requests, responseCode = 307)
        val error = try {
            cloud.setFavorite(credentials, "9999999999999999999", add = true)
            null
        } catch (caught: IOException) {
            caught
        }
        assertTrue(error is RedfruitCloudException)
        assertEquals(1, requests.size)
    }

    private fun cloud(
        requests: MutableList<okhttp3.Request>,
        responseCode: Int = 200,
        responseBody: String = """{"code":0,"data":{}}""",
    ): RedfruitCloudLibrary {
        val interceptor = Interceptor { chain ->
            requests += chain.request()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(responseCode)
                .message("fixture")
                .body(responseBody.toResponseBody())
                .build()
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build()
        val registered = VendorConstants.device + mapOf(
            "device_id" to "1905892595378490",
            "iid" to "1905892595382586",
        )
        return RedfruitCloudLibrary(client) { registered }
    }

    private fun okhttp3.Request.bodyText(): String {
        val buffer = Buffer()
        body!!.writeTo(buffer)
        return buffer.readUtf8()
    }

    private companion object {
        val credentials = RedfruitCredentials("sessionid=fixture", "token-fixture")
    }
}
