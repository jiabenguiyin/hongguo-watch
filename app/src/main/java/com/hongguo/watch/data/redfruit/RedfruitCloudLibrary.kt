// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.watch.data.redfruit

import com.hongguo.watch.data.CoverUrls
import com.hongguo.watch.data.db.FavoriteEntity
import com.hongguo.watch.data.db.WatchHistoryEntity
import com.hongguo.watch.data.model.SeriesSource
import com.hongguo.watch.upstream.Signer
import com.hongguo.watch.upstream.VendorConstants
import com.hongguo.watch.upstream.str
import java.io.IOException
import java.math.BigInteger
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal data class RedfruitCloudProfile(
    val id: String,
    val name: String,
    internal val shelfUserId: String?,
)

/**
 * 官方追更条目（`chase_book/list` / `bookshelf/chase_book`）。
 * [episodeCount] 为当前总集；[lastSerialCount] 对齐官方本地 `last_serial_count`。
 */
internal data class RedfruitChaseBook(
    val seriesId: String,
    val seriesTitle: String,
    val cover: String,
    val episodeCount: Int,
    val lastSerialCount: Int,
    val isChasedUpdates: Boolean,
)

/** 官方「我的点赞」条目（`ugc/action/user/get`，action_type=Digg≈3）。 */
internal data class RedfruitLikedSeries(
    val seriesId: String,
    val seriesTitle: String,
    val cover: String,
    val episodeCount: Int,
    val likedAt: Long,
)

/**
 * 官方「预约」条目（`user/subscribe/list`）。
 * [hotValue] / [hotText] 有真实字段才显示热度；[online] null 表示未知。
 */
internal data class RedfruitReservation(
    val seriesId: String,
    val seriesTitle: String,
    val cover: String,
    val episodeCount: Int,
    val hotValue: Long,
    val hotText: String,
    val online: Boolean?,
    val subscribedAt: Long,
)

/**
 * 官方「收藏夹」（`/reading/distribution/bookshelf_folder/…`）。
 * 名称上限 15 个 UTF-16 码元；[published] 对齐 `is_publish`。
 */
internal data class RedfruitBookshelfFolder(
    val id: Long,
    val name: String,
    val itemCount: Int,
    val published: Boolean,
    val covers: List<String>,
)

/** 收藏夹内一条内容；短剧 [seriesId] 常与 [itemId] 相同。 */
internal data class RedfruitBookshelfFolderItem(
    val itemId: String,
    val seriesId: String,
    val title: String,
    val cover: String,
    val episodeCount: Int,
    val contentType: Int,
    val bookType: Int,
)

internal class RedfruitCloudException(
    message: String,
    val authenticationRejected: Boolean = false,
    internal val httpStatus: Int? = null,
) : IOException(message)

/**
 * First-party account requests. The website's cookie jar and the playback client stay
 * separate. Account metadata follows the read API used by hongguo-drama and the list
 * shapes documented by hongguoTV and aston314/hongguo-tv. Writes are deliberately
 * single-shot; callers must verify a mutation with a later read before reporting it
 * as confirmed.
 */
internal class RedfruitCloudLibrary(
    client: OkHttpClient,
    private val device: suspend () -> Map<String, String>,
) {
    private val http = client.newBuilder()
        .cache(null)
        .cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false)
        .followSslRedirects(false)
        .authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .retryOnConnectionFailure(false)
        .callTimeout(18, TimeUnit.SECONDS)
        .build()

    suspend fun profile(credentials: RedfruitCredentials): RedfruitCloudProfile {
        val data = request(credentials, "/reading/user/info/v1/").requireCloudData()
        if (data.hasExplicitGuestState()) {
            throw RedfruitCloudException("红果账号会话已失效，请重新登录", authenticationRejected = true)
        }
        val businessId = data.validAccountId("biz_user_id")
        val userId = data.validAccountId("user_id_str", "user_id", "uid")
        val id = businessId ?: userId?.takeIf { data.confirmsLogin() }
            ?: throw RedfruitCloudException("红果尚未确认手机云端账号身份")
        return RedfruitCloudProfile(
            id,
            data.firstText("user_name", "screen_name", "name").take(80).ifBlank { "红果用户" },
            userId ?: id,
        )
    }

    suspend fun history(credentials: RedfruitCredentials): List<WatchHistoryEntity> {
        val rowsBySeries = linkedMapOf<String, WatchHistoryEntity>()
        val seenPages = mutableSetOf<List<String>>()
        var offset = 0
        repeat(MAX_HISTORY_PAGES) {
            val data = requestVariants(
                credentials,
                listOf(
                    "/reading/bookapi/read_history/list/v1/",
                    "/reading/bookapi/read_history/list/v",
                ),
                mapOf(
                    "limit" to "0",
                    "offset" to offset.toString(),
                    "book_type" to "2",
                    "full_field" to "true",
                    "query_soft_deleted" to "false",
                    "last_min_read_timestamp_ms" to "0",
                    "is_first_load" to (offset == 0).toString(),
                ),
            ).requireCloudData()
            val page = data.requiredArray("data_list", "云端观看历史", PAGE_ROWS_LIMIT)
            val history = parseHistory(page)
            if (history.isNotEmpty() && !seenPages.add(history.map { it.seriesId })) {
                throw RedfruitCloudException("红果返回了重复的历史分页，请稍后重试")
            }
            for (row in history) {
                val previous = rowsBySeries[row.seriesId]
                if (previous == null || RedfruitSyncPolicy.compareProgress(row, previous) > 0) {
                    rowsBySeries[row.seriesId] = row
                }
            }
            if (!data.booleanValue("has_more")) {
                return rowsBySeries.values.sortedByDescending { it.updatedAt }
            }
            if (page.length() == 0) throw RedfruitCloudException("云端观看历史分页未完整返回")
            val suggestedOffset = data.exactNumber("next_offset")?.toIntOrNull()
            // Older history responses omit the next offset even when reporting more pages.
            offset = suggestedOffset?.takeIf { it > offset } ?: (offset + HISTORY_PAGE_STRIDE)
        }
        throw RedfruitCloudException("云端观看历史超过本次读取范围")
    }

    suspend fun favorites(
        credentials: RedfruitCredentials,
        profile: RedfruitCloudProfile,
    ): List<FavoriteEntity> {
        val shelfUserId = profile.shelfUserId ?: throw RedfruitCloudException("红果云端收藏账号未确认")
        val payload = try {
            requestVariants(
                credentials,
                listOf(
                    "/reading/bookapi/bookshelf/video/list/v1/",
                    "/reading/bookapi/bookshelf/video/list/v",
                ),
                mapOf("target_user_id" to shelfUserId),
            )
        } catch (error: RedfruitCloudException) {
            if (error.httpStatus != 404 && error.httpStatus != 405) throw error
            requestVariants(
                credentials,
                listOf(
                    "/reading/bookapi/bookshelf/video/info/v1/",
                    "/reading/bookapi/bookshelf/video/info/v",
                ),
            )
        }
        val rows = payload.requireCloudData()
            .requiredArray("video_shelf_info", "云端收藏", MAX_FAVORITE_ROWS, nullMeansEmpty = true)
        val favorites = parseFavorites(rows)
        val missing = favorites.filter { it.seriesTitle.isBlank() }.take(MAX_FAVORITE_DETAILS)
        val details = favoriteDetails(credentials, missing.map { it.seriesId })
        return favorites.map { row ->
            val detail = details[row.seriesId]
            row.copy(
                seriesTitle = row.seriesTitle.ifBlank { detail?.title.orEmpty().ifBlank { "剧集 ${row.seriesId}" } },
                cover = row.cover.ifBlank { detail?.cover.orEmpty() },
                episodeCount = row.episodeCount.takeIf { it > 0 } ?: detail?.episodeCount ?: 0,
            )
        }
    }

    /**
     * 官方追更列表。优先 `chase_book/list`，404/405 再试 `bookshelf/chase_book`。
     * Schema 仅有 DEX 字段名证据，解析保持宽松；结构无效时抛错由调用方降级本地游标。
     */
    suspend fun chaseBooks(credentials: RedfruitCredentials): List<RedfruitChaseBook> {
        val payload = try {
            requestVariants(
                credentials,
                listOf(
                    "/reading/bookapi/chase_book/list/v1/",
                    "/reading/bookapi/chase_book/list/v",
                ),
            )
        } catch (error: RedfruitCloudException) {
            if (error.httpStatus != 404 && error.httpStatus != 405) throw error
            requestVariants(
                credentials,
                listOf(
                    "/reading/bookapi/bookshelf/chase_book/v1/",
                    "/reading/bookapi/bookshelf/chase_book/v",
                ),
            )
        }
        val data = payload.requireCloudData()
        val rows = firstPresentArray(
            data,
            "chase_book_data_list",
            "chase_book_info_list",
            "chase_book_data",
            "chase_books",
            "data_list",
            "video_shelf_info",
        ) ?: JSONArray()
        if (rows.length() > MAX_CHASE_ROWS) {
            throw RedfruitCloudException("云端追更超出本次读取范围")
        }
        return parseChaseBooks(rows)
    }

    /**
     * 官方「我的点赞」列表。`POST …/ugc/action/user/get/v1/`（DEX `MGetUserActionListRequest`）。
     * `action_type=3` 对齐 Digg / aston 对照；Schema 仅有字段名证据 → 宽松解析。
     */
    suspend fun likedSeries(credentials: RedfruitCredentials): List<RedfruitLikedSeries> {
        val body = JSONObject()
            .put("action_type", UGC_ACTION_TYPE_DIGG)
            .put("count", LIKES_PAGE_SIZE)
            .put("offset", 0)
            .put("cursor", "0")
            .toString()
            .toByteArray(Charsets.UTF_8)
        val payload = try {
            request(
                credentials,
                "/reading/ugc/action/user/get/v1/",
                params = emptyMap(),
                body = body,
            )
        } catch (error: RedfruitCloudException) {
            if (error.httpStatus != 404 && error.httpStatus != 405) throw error
            request(
                credentials,
                "/reading/ugc/action/user/get/v/",
                params = emptyMap(),
                body = body,
            )
        }
        val data = payload.requireCloudData()
        val rows = firstPresentArray(
            data,
            "user_action_list",
            "action_list",
            "action_result_list",
            "data_list",
            "digg_list",
            "my_digg",
            "item_list",
            "video_data",
        ) ?: JSONArray()
        if (rows.length() > MAX_LIKES_ROWS) {
            throw RedfruitCloudException("云端点赞超出本次读取范围")
        }
        val likes = parseLikedSeries(rows)
        val missing = likes.filter { it.seriesTitle.isBlank() || it.cover.isBlank() }
            .take(MAX_FAVORITE_DETAILS)
        if (missing.isEmpty()) return likes
        val details = favoriteDetails(credentials, missing.map { it.seriesId })
        return likes.map { row ->
            val detail = details[row.seriesId] ?: return@map row
            row.copy(
                seriesTitle = row.seriesTitle.ifBlank { detail.title.ifBlank { "剧集 ${row.seriesId}" } },
                cover = row.cover.ifBlank { detail.cover },
                episodeCount = row.episodeCount.takeIf { it > 0 } ?: detail.episodeCount,
            )
        }
    }

    /**
     * 官方预约列表。`GET …/user/subscribe/list/v1/`（DEX `GetUserSubscribeListRequest`）。
     * 热度只取响应里的 `hot_*` / `secondary_info`，不造假。
     */
    suspend fun reservations(credentials: RedfruitCredentials): List<RedfruitReservation> {
        val payload = requestVariants(
            credentials,
            listOf(
                "/reading/user/subscribe/list/v1/",
                "/reading/user/subscribe/list/v",
            ),
            mapOf(
                "offset" to "0",
                "limit" to RESERVATIONS_PAGE_SIZE.toString(),
                "count" to RESERVATIONS_PAGE_SIZE.toString(),
                "subscribe_offset" to "0",
            ),
        )
        val data = payload.requireCloudData()
        val rows = firstPresentArray(
            data,
            "subscribe_items",
            "subscribe_item",
            "subscribe_data",
            "data_list",
            "item_list",
            "video_data",
        ) ?: JSONArray()
        if (rows.length() > MAX_RESERVATION_ROWS) {
            throw RedfruitCloudException("云端预约超出本次读取范围")
        }
        val items = parseReservations(rows)
        val missing = items.filter { it.seriesTitle.isBlank() || it.cover.isBlank() }
            .take(MAX_FAVORITE_DETAILS)
        if (missing.isEmpty()) return items
        val details = favoriteDetails(credentials, missing.map { it.seriesId })
        return items.map { row ->
            val detail = details[row.seriesId] ?: return@map row
            row.copy(
                seriesTitle = row.seriesTitle.ifBlank { detail.title.ifBlank { "剧集 ${row.seriesId}" } },
                cover = row.cover.ifBlank { detail.cover },
                episodeCount = row.episodeCount.takeIf { it > 0 } ?: detail.episodeCount,
            )
        }
    }

    /**
     * Recent progress endpoint used by hongguoTV for account read-back. Keep this
     * bounded for routine player updates; the full history reader remains paginated.
     */
    suspend fun recentProgress(credentials: RedfruitCredentials): List<WatchHistoryEntity> {
        val data = request(
            credentials,
            "/reading/bookapi/read_progress/list/v1/",
            mapOf("book_types" to "2", "limit" to "50"),
        ).requireCloudData()
        return parseHistory(
            data.requiredArray("video_data", "云端观看进度", RECENT_PROGRESS_LIMIT, nullMeansEmpty = true),
        )
    }

    /**
     * Upload the local playback cursor to the account's official reading history.
     *
     * [WatchHistoryEntity.episodeNumber] is one-based throughout this app and is sent
     * as the official `vid_index` value. The cloud API accepts a zero episode count
     * when the player has not fetched the series total yet.
     */
    suspend fun uploadProgress(
        credentials: RedfruitCredentials,
        history: WatchHistoryEntity,
        episodeCount: Int = 0,
    ) {
        require(history.seriesId.isSeriesId()) { "红果进度剧集编号无效" }
        require(history.episodeId.isSeriesId()) { "红果进度分集编号无效" }
        require(history.episodeNumber in 1 until Int.MAX_VALUE) { "红果进度分集位置无效" }
        val duration = history.durationMs.coerceAtLeast(0L)
        val position = history.positionMs.coerceAtLeast(0L)
        val entry = JSONObject()
            .put("book_id", history.seriesId)
            .put("item_id", history.episodeId)
            .put("vid_index", history.episodeNumber)
            .put("current_play_time", position)
            .put("current_total_time", duration)
            .put("episode_count", episodeCount.coerceAtLeast(0))
            .put("read_timestamp_ms", history.updatedAt.coerceAtLeast(0L))
            .put("book_type", 2)
            .put(
                "progress_rate",
                if (duration > 0L) {
                    (position.toDouble() / duration).coerceIn(0.0, 1.0).toString()
                } else {
                    "0"
                },
            )
            .put("player_cumulative_total_duration", position)
        val body = JSONObject()
            .put("books", JSONArray().put(entry))
            .put("write_mode", 1)
            .toString()
            .toByteArray(Charsets.UTF_8)
        request(
            credentials,
            "/reading/bookapi/read_progress/upload/v1/",
            params = emptyMap(),
            body = body,
        )
    }

    /**
     * Add or remove a video from the official account shelf.
     *
     * The endpoint acknowledges the operation but does not guarantee that the
     * shelf changed. Callers should read [favorites] afterwards and only then
     * present a confirmed result to the user.
     */
    suspend fun setFavorite(
        credentials: RedfruitCredentials,
        seriesId: String,
        add: Boolean,
    ) {
        require(seriesId.isSeriesId()) { "红果收藏剧集编号无效" }
        val entry = JSONObject()
            .put("book_id", seriesId)
            .put("book_type", 2)
            .put("modify_time", System.currentTimeMillis())
            .put("video_shelf_operate_type", if (add) 0 else 1)
        if (!add) entry.put("group_name", "")
        val body = JSONObject()
            .put("is_cancelled", false)
            .put("update_bookshelf_video_list", JSONArray().put(entry))
        if (add) {
            body.put(
                "shark_extra",
                JSONObject()
                    .put("enter_from", "MainFragmentActivity")
                    .put("previous_page", "")
                    .put("page_list", "MainFragmentActivity")
                    .put("is_active_behavior", "true")
                    .put("inactive_type", "0"),
            )
        }
        request(
            credentials,
            "/reading/bookapi/bookshelf/video/update/v1/",
            params = emptyMap(),
            body = body.toString().toByteArray(Charsets.UTF_8),
        )
    }

    /**
     * 官方收藏夹列表。
     * SoT：`POST /reading/distribution/bookshelf_folder/list/v1/`
     *（hongguo-desktop DistributionApiService / fqapp account_library）。
     */
    suspend fun bookshelfFolders(credentials: RedfruitCredentials): List<RedfruitBookshelfFolder> {
        val folders = ArrayList<RedfruitBookshelfFolder>()
        var offset = 0L
        repeat(MAX_FOLDER_PAGES) {
            val data = postFolderJson(
                credentials,
                FOLDER_LIST_PATH,
                JSONObject()
                    .put("offset", offset)
                    .put("count", FOLDER_PAGE_SIZE)
                    .put("preview_items_limit", FOLDER_PREVIEW_LIMIT)
                    .put("has_detail", true),
            ).requireCloudData()
            val rows = data.optJSONArray("folders") ?: JSONArray()
            if (rows.length() > MAX_FOLDER_ROWS) {
                throw RedfruitCloudException("云端收藏夹超出本次读取范围")
            }
            for (index in 0 until rows.length()) {
                val folder = parseBookshelfFolder(rows.optJSONObject(index) ?: continue) ?: continue
                folders.add(folder)
            }
            if (!data.booleanValue("has_more")) return folders
            val next = data.exactNumber("next_offset")?.toLongOrNull()
                ?.takeIf { it > offset }
                ?: throw RedfruitCloudException("云端收藏夹分页未完整返回")
            offset = next
        }
        throw RedfruitCloudException("云端收藏夹超过本次读取范围")
    }

    /** 收藏夹内容页。 */
    suspend fun bookshelfFolderItems(
        credentials: RedfruitCredentials,
        folderId: Long,
    ): Pair<RedfruitBookshelfFolder?, List<RedfruitBookshelfFolderItem>> {
        require(folderId > 0L) { "收藏夹编号无效" }
        val items = ArrayList<RedfruitBookshelfFolderItem>()
        var folder: RedfruitBookshelfFolder? = null
        var offset = 0L
        repeat(MAX_FOLDER_PAGES) {
            val data = postFolderJson(
                credentials,
                FOLDER_DETAIL_PATH,
                JSONObject()
                    .put("folder_id", folderId)
                    .put("offset", offset)
                    .put("limit", FOLDER_PAGE_SIZE)
                    .put("has_detail", true),
            ).requireCloudData()
            if (folder == null) {
                folder = parseBookshelfFolder(data)
                    ?: data.optJSONObject("folder")?.let(::parseBookshelfFolder)
            }
            val rows = data.optJSONArray("items") ?: JSONArray()
            if (rows.length() > MAX_FOLDER_ITEM_ROWS) {
                throw RedfruitCloudException("云端收藏夹内容超出本次读取范围")
            }
            for (index in 0 until rows.length()) {
                val item = parseBookshelfFolderItem(rows.optJSONObject(index) ?: continue) ?: continue
                items.add(item)
            }
            if (!data.booleanValue("has_more")) {
                return folder to items.distinctBy { it.itemId }
            }
            val next = data.exactNumber("next_offset")?.toLongOrNull()
                ?.takeIf { it > offset }
                ?: throw RedfruitCloudException("云端收藏夹内容分页未完整返回")
            offset = next
        }
        throw RedfruitCloudException("云端收藏夹内容超过本次读取范围")
    }

    /** 新建收藏夹：`operate_type=1`，名称 1–15。 */
    suspend fun createBookshelfFolder(
        credentials: RedfruitCredentials,
        name: String,
        publish: Boolean,
    ) {
        val folderName = validatedFolderName(name)
        postFolderJson(
            credentials,
            FOLDER_UPDATE_PATH,
            JSONObject().put(
                "operate_data",
                JSONArray().put(
                    JSONObject()
                        .put("operate_type", FOLDER_OP_CREATE)
                        .put("folder_name", folderName)
                        .put("is_publish", publish),
                ),
            ),
        )
    }

    /** 修改名称 / 公开性：`operate_type=3`。 */
    suspend fun updateBookshelfFolder(
        credentials: RedfruitCredentials,
        folderId: Long,
        name: String,
        publish: Boolean,
    ) {
        require(folderId > 0L) { "收藏夹编号无效" }
        val folderName = validatedFolderName(name)
        postFolderJson(
            credentials,
            FOLDER_UPDATE_PATH,
            JSONObject().put(
                "operate_data",
                JSONArray().put(
                    JSONObject()
                        .put("operate_type", FOLDER_OP_UPDATE)
                        .put("folder_id", folderId)
                        .put("folder_name", folderName)
                        .put("is_publish", publish),
                ),
            ),
        )
    }

    /** 删除收藏夹：`operate_type=2`。 */
    suspend fun deleteBookshelfFolder(credentials: RedfruitCredentials, folderId: Long) {
        require(folderId > 0L) { "收藏夹编号无效" }
        postFolderJson(
            credentials,
            FOLDER_UPDATE_PATH,
            JSONObject().put(
                "operate_data",
                JSONArray().put(
                    JSONObject()
                        .put("operate_type", FOLDER_OP_DELETE)
                        .put("folder_id", folderId),
                ),
            ),
        )
    }

    /**
     * 向收藏夹添加短剧条目。
     * `POST …/bookshelf_folder/item/update/v1/`，`operate_type=1`。
     */
    suspend fun addBookshelfFolderItems(
        credentials: RedfruitCredentials,
        folderId: Long,
        seriesIds: List<String>,
        contentType: Int = FOLDER_CONTENT_SHORT_SERIES,
    ) {
        require(folderId > 0L) { "收藏夹编号无效" }
        require(seriesIds.isNotEmpty()) { "未选择要添加的内容" }
        val operators = JSONArray()
        for (seriesId in seriesIds.distinct()) {
            require(seriesId.isSeriesId()) { "红果收藏剧集编号无效" }
            operators.put(
                folderItemOperator(
                    operateType = FOLDER_ITEM_OP_ADD,
                    itemId = seriesId,
                    folderId = folderId,
                    bookType = 2,
                    contentType = contentType.coerceAtLeast(1),
                ),
            )
        }
        postFolderJson(
            credentials,
            FOLDER_ITEM_UPDATE_PATH,
            JSONObject().put("operators", operators),
        )
    }

    /**
     * 从收藏夹移出条目（不删全部收藏）。
     * `POST …/bookshelf_folder/item/update/v1/`，`operate_type=2`。
     */
    suspend fun removeBookshelfFolderItems(
        credentials: RedfruitCredentials,
        folderId: Long,
        items: List<RedfruitBookshelfFolderItem>,
    ) {
        require(folderId > 0L) { "收藏夹编号无效" }
        require(items.isNotEmpty()) { "未选择要移除的内容" }
        val operators = JSONArray()
        for (item in items.distinctBy { it.itemId }) {
            require(item.itemId.isSeriesId()) { "红果收藏条目编号无效" }
            operators.put(
                folderItemOperator(
                    operateType = FOLDER_ITEM_OP_REMOVE,
                    itemId = item.itemId,
                    folderId = folderId,
                    bookType = item.bookType.takeIf { it > 0 } ?: 2,
                    contentType = item.contentType.coerceAtLeast(1),
                ),
            )
        }
        postFolderJson(
            credentials,
            FOLDER_ITEM_UPDATE_PATH,
            JSONObject().put("operators", operators),
        )
    }

    private fun folderItemOperator(
        operateType: Int,
        itemId: String,
        folderId: Long,
        bookType: Int,
        contentType: Int,
    ): JSONObject = JSONObject()
        .put("operate_type", operateType)
        .put("item_id", itemId)
        .put("folder_id", folderId)
        .put("book_type", bookType)
        .put("content_type", contentType)

    private suspend fun postFolderJson(
        credentials: RedfruitCredentials,
        path: String,
        body: JSONObject,
    ): JSONObject = request(
        credentials,
        path,
        params = emptyMap(),
        body = body.toString().toByteArray(Charsets.UTF_8),
    )

    private data class FavoriteDetail(val title: String, val cover: String, val episodeCount: Int)

    private suspend fun favoriteDetails(
        credentials: RedfruitCredentials,
        seriesIds: List<String>,
    ): Map<String, FavoriteDetail> {
        val details = mutableMapOf<String, FavoriteDetail>()
        // Card metadata is optional. Never turn a valid favorites list into a
        // failed refresh just because enriching a card takes too long.
        withTimeoutOrNull(6_000) {
            for (batch in seriesIds.chunked(20)) {
                val data = try {
                    request(
                        credentials, "/reading/bookapi/multi-detail/v1/",
                        mapOf("book_id" to batch.joinToString(","), "book_type" to "2"),
                    ).optJSONArray("data")
                } catch (_: IOException) {
                    null
                } ?: break
                for (index in 0 until minOf(data.length(), 20)) {
                    val row = data.optJSONObject(index) ?: continue
                    val id = row.validSeriesId("book_id_str", "book_id", "series_id") ?: continue
                    if (id !in batch) continue
                    details[id] = FavoriteDetail(
                        row.firstText("book_name", "series_title", "title"),
                        safeCover(row.firstText("thumb_url", "series_cover_url", "cover_url")),
                        row.episodeCount(),
                    )
                }
            }
        }
        return details
    }

    private suspend fun request(
        credentials: RedfruitCredentials,
        path: String,
        params: Map<String, String> = emptyMap(),
    ): JSONObject = request(credentials, path, params, body = null)

    private suspend fun request(
        credentials: RedfruitCredentials,
        path: String,
        params: Map<String, String>,
        body: ByteArray?,
    ): JSONObject {
        val cookie = credentials.cookie
        val token = credentials.token
        if ((!cookie.isSafeHeader() || !token.isSafeHeader()) || (cookie.isBlank() && token.isBlank())) {
            throw RedfruitCloudException("红果账号会话无效，请重新登录", authenticationRejected = true)
        }
        val registered = device()
        if (registered["device_id"]?.isSeriesId() != true || registered["iid"]?.isSeriesId() != true) {
            throw RedfruitCloudException("红果账号设备尚未注册")
        }
        val signingDevice = registered.filterKeys { it in SIGNING_DEVICE_KEYS }
        val identity = VendorConstants.device + signingDevice + ACCOUNT_VERSION
        val signer = Signer(device = identity)
        val signed = signer.sign(
            "${API_ORIGIN}${path}",
            identity + params,
            body = body,
            identity = identity,
        )
        val url = signed.url.toHttpUrlOrNull()
            ?.takeIf { it.isHttps && it.host == API_HOST && it.port == 443 && it.encodedPath == path }
            ?: throw RedfruitCloudException("红果账号请求地址无效")
        val request = Request.Builder().url(url).apply {
            signed.headers.forEach { (name, value) -> header(name, value) }
            val deviceCookies = registered.entries
                .filter { it.key.startsWith("cookie_") }
                .map { "${it.key.removePrefix("cookie_")}=${it.value}" }
                .toMutableList()
            if (registered["iid"] != null && cookie.substringBefore(';')
                    .trim().substringBefore('=').trim() != "install_id"
            ) {
                deviceCookies += "install_id=${registered.getValue("iid")}"
            }
            val cookieHeader = listOf(cookie, deviceCookies.joinToString("; "))
                .filter(String::isNotBlank)
                .joinToString("; ")
            if (cookieHeader.isNotBlank()) header("Cookie", cookieHeader)
            if (token.isNotBlank()) header("X-Tt-Token", token)
            if (body == null) get()
            else post(body.toRequestBody(JSON_MEDIA_TYPE))
        }.build()
        return read(request)
    }

    private suspend fun requestVariants(
        credentials: RedfruitCredentials,
        paths: List<String>,
        params: Map<String, String> = emptyMap(),
    ): JSONObject {
        var last: RedfruitCloudException? = null
        for (path in paths) {
            try {
                return request(credentials, path, params)
            } catch (error: RedfruitCloudException) {
                if (error.httpStatus != 404 && error.httpStatus != 405) throw error
                last = error
            }
        }
        throw last ?: RedfruitCloudException("红果账号请求地址无效")
    }

    private suspend fun read(request: Request): JSONObject = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWith(Result.failure(RedfruitCloudException("红果账号服务连接失败")))
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resumeWith(runCatching { response.use { it.readCloudJson() } })
            }
        })
    }

    private fun Response.readCloudJson(): JSONObject {
        if (!isSuccessful) {
            val message = when (code) {
                401 -> "红果账号会话已失效，请重新登录"
                429 -> "红果账号请求过于频繁，请稍后再试"
                else -> "红果账号读取失败（HTTP $code）"
            }
            throw RedfruitCloudException(message, authenticationRejected = code == 401, httpStatus = code)
        }
        val jsonBody = body ?: throw RedfruitCloudException("红果账号响应为空")
        val source = jsonBody.source()
        if (jsonBody.contentLength() > MAX_RESPONSE_BYTES || source.request(MAX_RESPONSE_BYTES + 1)) {
            throw RedfruitCloudException("红果账号响应超过大小限制")
        }
        val raw = source.readUtf8()
        val json = try {
            JSONObject(raw)
        } catch (_: JSONException) {
            throw RedfruitCloudException("红果账号返回的数据格式无效")
        }
        val code = json.exactNumber("code")
        val innerCode = json.optJSONObject("data")?.exactNumber("error_code")
        if (code != "0" || (innerCode != null && innerCode != "0")) {
            val safeCode = (innerCode?.takeUnless { it == "0" } ?: code)?.takeIf { it.length <= 10 }
            val hint = if (safeCode == null) "" else "（$safeCode）"
            throw RedfruitCloudException("红果账号服务未确认读取成功$hint")
        }
        return json
    }

    private companion object {
        const val API_ORIGIN = "https://api5-normal-sinfonlinea.fqnovel.com"
        val API_HOST = API_ORIGIN.toHttpUrl().host
        val ACCOUNT_VERSION = mapOf(
            "version_code" to "72232",
            "manifest_version_code" to "72232",
            "update_version_code" to "72232",
            "pv_player" to "72232",
            "version_name" to "7.2.2.32",
        )
        const val MAX_RESPONSE_BYTES = 4L * 1024 * 1024
        const val MAX_HISTORY_PAGES = 24
        const val HISTORY_PAGE_STRIDE = 500
        const val PAGE_ROWS_LIMIT = 1000
        const val MAX_FAVORITE_ROWS = 12_000
        const val MAX_FAVORITE_DETAILS = 200
        const val MAX_CHASE_ROWS = 2_000
        const val MAX_LIKES_ROWS = 2_000
        const val MAX_RESERVATION_ROWS = 2_000
        const val LIKES_PAGE_SIZE = 100
        const val RESERVATIONS_PAGE_SIZE = 100
        /** DEX / aston：点赞 Digg ≈ 3（`UgcActionType` / DiggAction）。 */
        const val UGC_ACTION_TYPE_DIGG = 3
        const val FOLDER_LIST_PATH = "/reading/distribution/bookshelf_folder/list/v1/"
        const val FOLDER_DETAIL_PATH = "/reading/distribution/bookshelf_folder/detail/v1/"
        const val FOLDER_UPDATE_PATH = "/reading/distribution/bookshelf_folder/update/v1/"
        const val FOLDER_ITEM_UPDATE_PATH = "/reading/distribution/bookshelf_folder/item/update/v1/"
        const val FOLDER_OP_CREATE = 1L
        const val FOLDER_OP_DELETE = 2L
        const val FOLDER_OP_UPDATE = 3L
        const val FOLDER_ITEM_OP_ADD = 1
        const val FOLDER_ITEM_OP_REMOVE = 2
        const val FOLDER_CONTENT_SHORT_SERIES = 1
        const val FOLDER_PAGE_SIZE = 20
        const val FOLDER_PREVIEW_LIMIT = 4
        const val MAX_FOLDER_PAGES = 50
        const val MAX_FOLDER_ROWS = 2_000
        const val MAX_FOLDER_ITEM_ROWS = 5_000
        const val FOLDER_NAME_MAX_UNITS = 15
        const val RECENT_PROGRESS_LIMIT = 50
        val JSON_MEDIA_TYPE = "application/json; charset=UTF-8".toMediaType()
        val SIGNING_DEVICE_KEYS = setOf("device_id", "iid", "cdid", "openudid")
    }
}

internal fun validatedFolderName(name: String): String {
    val trimmed = name.trim()
    // Kotlin String.length == UTF-16 code units（官方上限 15）
    if (trimmed.isEmpty() || trimmed.length > 15) {
        throw RedfruitCloudException("收藏夹名称需为 1 至 15 个字符")
    }
    return trimmed
}

internal fun parseBookshelfFolder(row: JSONObject): RedfruitBookshelfFolder? {
    val id = row.exactNumber("folder_id")?.toLongOrNull()?.takeIf { it > 0 } ?: return null
    val name = row.firstText("folder_name", "name").trim()
    if (name.isEmpty()) return null
    val covers = ArrayList<String>(4)
    val preview = row.optJSONArray("items")
    if (preview != null) {
        for (index in 0 until minOf(preview.length(), 4)) {
            val item = preview.optJSONObject(index) ?: continue
            val cover = folderItemCover(item)
            if (cover.isNotBlank()) covers.add(cover)
        }
    }
    val coverField = safeCover(row.firstText("folder_cover", "cover", "cover_url"))
    if (covers.isEmpty() && coverField.isNotBlank()) covers.add(coverField)
    return RedfruitBookshelfFolder(
        id = id,
        name = name,
        itemCount = row.boundedNumber("item_count", "count", max = 100_000).toInt(),
        published = when {
            row.has("is_publish") && !row.isNull("is_publish") -> row.optBoolean("is_publish")
            row.has("published") && !row.isNull("published") -> row.optBoolean("published")
            else -> row.booleanValue("is_publish") || row.booleanValue("published")
        },
        covers = covers,
    )
}

internal fun parseBookshelfFolderItem(row: JSONObject): RedfruitBookshelfFolderItem? {
    val video = row.optJSONObject("video_detail")
    val videoData = video?.optJSONObject("video_data")
    val itemId = row.validSeriesId("item_id", "item_id_str", "book_id_str", "book_id", "series_id_str", "series_id")
        ?: video?.validSeriesId("item_id", "book_id_str", "book_id", "series_id_str", "series_id")
        ?: videoData?.validSeriesId("series_id_str", "series_id", "book_id_str", "book_id")
        ?: return null
    val bookType = row.boundedNumber("book_type", max = 100).toInt()
        .takeIf { it > 0 }
        ?: video?.boundedNumber("book_type", max = 100)?.toInt()
        ?: 2
    val seriesId = videoData?.validSeriesId("series_id_str", "series_id", "book_id_str", "book_id")
        ?: video?.validSeriesId("series_id_str", "series_id", "book_id_str", "book_id")
        ?: row.validSeriesId("series_id_str", "series_id", "book_id_str", "book_id")
        ?: itemId.takeIf { bookType == 2 }
        ?: return null
    val title = row.firstText("series_title", "book_name", "title")
        .ifBlank { video?.firstText("series_title", "book_name", "title").orEmpty() }
        .ifBlank { videoData?.firstText("title", "book_name", "series_title").orEmpty() }
        .ifBlank { "剧集 $seriesId" }
    val contentType = row.boundedNumber("content_type", max = 10_000).toInt()
        .takeIf { it > 0 }
        ?: video?.boundedNumber("content_type", max = 10_000)?.toInt()
        ?: videoData?.boundedNumber("content_type", max = 10_000)?.toInt()
        ?: 1
    return RedfruitBookshelfFolderItem(
        itemId = itemId,
        seriesId = seriesId,
        title = title,
        cover = folderItemCover(row),
        episodeCount = maxOf(
            row.episodeCount(),
            video?.episodeCount() ?: 0,
            videoData?.episodeCount() ?: 0,
        ),
        contentType = contentType,
        bookType = bookType,
    )
}

private fun folderItemCover(row: JSONObject): String {
    val video = row.optJSONObject("video_detail")
    val videoData = video?.optJSONObject("video_data")
    return safeCover(
        row.firstText("series_cover_url", "thumb_url", "cover_url", "cover", "folder_cover")
            .ifBlank { video?.firstText("series_cover_url", "thumb_url", "cover_url", "cover").orEmpty() }
            .ifBlank { videoData?.firstText("cover", "cover_url", "thumb_url").orEmpty() },
    )
}

internal fun parseHistory(rows: JSONArray): List<WatchHistoryEntity> {
    val result = ArrayList<WatchHistoryEntity>(rows.length())
    for (index in 0 until rows.length()) {
        val row = rows.optJSONObject(index)
            ?: throw RedfruitCloudException("红果观看历史条目格式无效")
        if (row.booleanValue("is_delete")) continue
        val seriesId = row.validSeriesId("book_id_str", "series_id_str", "book_id", "series_id")
            ?: throw RedfruitCloudException("红果观看历史剧集编号无效")
        result.add(
            WatchHistoryEntity(
                seriesId = seriesId,
                seriesTitle = row.firstText("book_name", "series_title", "title").ifBlank { "剧集 $seriesId" },
                cover = safeCover(row.firstText("thumb_url", "series_cover_url", "cover_url")),
                episodeId = row.validSeriesId("vid_str", "item_id_str", "vid", "item_id").orEmpty(),
                episodeNumber = row.boundedNumber("vid_index", "episode_number", max = 100_000).toInt(),
                positionMs = row.boundedNumber("current_play_position", "current_play_time", max = 86_400_000),
                durationMs = row.boundedNumber("duration", "current_total_time", max = 86_400_000),
                updatedAt = row.epochMillis("read_timestamp_ms", "last_watch_time"),
                source = SeriesSource.APP.name,
            ),
        )
    }
    return result
}

internal fun parseFavorites(rows: JSONArray): List<FavoriteEntity> {
    val result = linkedMapOf<String, FavoriteEntity>()
    for (index in 0 until rows.length()) {
        val row = rows.optJSONObject(index)
            ?: throw RedfruitCloudException("红果云端收藏条目格式无效")
        val video = row.optJSONObject("video_data") ?: row
        val seriesId = row.validSeriesId("series_id_str", "book_id_str", "series_id", "book_id")
            ?: video.validSeriesId("series_id_str", "book_id_str", "series_id", "book_id")
            ?: throw RedfruitCloudException("红果收藏剧集编号无效")
        val favorite = FavoriteEntity(
            seriesId = seriesId,
            seriesTitle = row.firstText("series_title", "book_name", "title")
                .ifBlank { video.firstText("title", "book_name", "series_title") },
            cover = safeCover(
                row.firstText("series_cover_url", "thumb_url", "cover_url", "cover")
                    .ifBlank { video.firstText("cover", "cover_url", "thumb_url") },
            ),
            source = SeriesSource.APP.name,
            episodeCount = maxOf(row.episodeCount(), video.episodeCount()),
            updatedAt = row.epochMillis("collect_time"),
        )
        val previous = result[seriesId]
        if (previous == null || previous.updatedAt < favorite.updatedAt) result[seriesId] = favorite
    }
    return result.values.sortedByDescending { it.updatedAt }
}

internal fun parseChaseBooks(rows: JSONArray): List<RedfruitChaseBook> {
    val result = linkedMapOf<String, RedfruitChaseBook>()
    for (index in 0 until rows.length()) {
        val row = rows.optJSONObject(index) ?: continue
        val nested = row.optJSONObject("video_data")
            ?: row.optJSONObject("book_data")
            ?: row.optJSONObject("chase_book_data")
            ?: row
        val seriesId = row.validSeriesId("series_id_str", "book_id_str", "series_id", "book_id")
            ?: nested.validSeriesId("series_id_str", "book_id_str", "series_id", "book_id")
            ?: continue
        val episodeCount = maxOf(row.episodeCount(), nested.episodeCount())
        val lastSerial = row.boundedNumber("last_serial_count", "last_episode_cnt", max = 100_000).toInt()
            .takeIf { it > 0 }
            ?: nested.boundedNumber("last_serial_count", "last_episode_cnt", max = 100_000).toInt()
        val chased = row.booleanValue("is_chased_updates") ||
            nested.booleanValue("is_chased_updates") ||
            row.booleanValue("is_chase_book") ||
            nested.booleanValue("is_chase_book") ||
            (episodeCount > 0 && lastSerial > 0 && episodeCount > lastSerial)
        val item = RedfruitChaseBook(
            seriesId = seriesId,
            seriesTitle = row.firstText("series_title", "book_name", "title")
                .ifBlank { nested.firstText("title", "book_name", "series_title") }
                .ifBlank { "剧集 $seriesId" },
            cover = safeCover(
                row.firstText("series_cover_url", "thumb_url", "cover_url", "cover")
                    .ifBlank { nested.firstText("cover", "cover_url", "thumb_url") },
            ),
            episodeCount = episodeCount,
            lastSerialCount = lastSerial,
            isChasedUpdates = chased,
        )
        result[seriesId] = item
    }
    return result.values.toList()
}

internal fun parseLikedSeries(rows: JSONArray): List<RedfruitLikedSeries> {
    val result = linkedMapOf<String, RedfruitLikedSeries>()
    for (index in 0 until rows.length()) {
        val row = rows.optJSONObject(index) ?: continue
        val nested = row.optJSONObject("video_data")
            ?: row.optJSONObject("book_data")
            ?: row.optJSONObject("object_data")
            ?: row.optJSONObject("action_data")
            ?: row.optJSONObject("item")
            ?: row
        val seriesId = row.validSeriesId(
            "series_id_str", "book_id_str", "object_id_str", "object_id",
            "series_id", "book_id", "item_id_str", "item_id",
        ) ?: nested.validSeriesId(
            "series_id_str", "book_id_str", "object_id_str", "object_id",
            "series_id", "book_id", "item_id_str", "item_id",
        ) ?: continue
        val likedAt = row.epochMillis("action_time", "digg_time", "create_time", "modify_time")
            .takeIf { it > 0 }
            ?: nested.epochMillis("action_time", "digg_time", "create_time", "modify_time")
        val item = RedfruitLikedSeries(
            seriesId = seriesId,
            seriesTitle = row.firstText("series_title", "book_name", "title", "object_name")
                .ifBlank { nested.firstText("title", "book_name", "series_title", "object_name") },
            cover = safeCover(
                row.firstText("series_cover_url", "thumb_url", "cover_url", "cover")
                    .ifBlank { nested.firstText("cover", "cover_url", "thumb_url") },
            ),
            episodeCount = maxOf(row.episodeCount(), nested.episodeCount()),
            likedAt = likedAt,
        )
        val previous = result[seriesId]
        if (previous == null || previous.likedAt < item.likedAt) result[seriesId] = item
    }
    return result.values.sortedByDescending { it.likedAt }
}

internal fun parseReservations(rows: JSONArray): List<RedfruitReservation> {
    val result = linkedMapOf<String, RedfruitReservation>()
    for (index in 0 until rows.length()) {
        val row = rows.optJSONObject(index) ?: continue
        val nested = row.optJSONObject("video_data")
            ?: row.optJSONObject("book_data")
            ?: row.optJSONObject("subscribe_item")
            ?: row.optJSONObject("item")
            ?: row
        val seriesId = row.validSeriesId(
            "series_id_str", "book_id_str", "subscribe_item_id", "object_id_str",
            "series_id", "book_id", "object_id",
        ) ?: nested.validSeriesId(
            "series_id_str", "book_id_str", "subscribe_item_id", "object_id_str",
            "series_id", "book_id", "object_id",
        ) ?: continue
        val hotText = reservationHotText(row, nested)
        val hotValue = row.boundedNumber("hot_value", "hot_score", "hot_val", max = 9_999_999_999_999)
            .takeIf { it > 0 }
            ?: nested.boundedNumber("hot_value", "hot_score", "hot_val", max = 9_999_999_999_999)
        val online = reservationOnline(row, nested)
        val subscribedAt = row.epochMillis("subscribe_time", "create_time", "modify_time")
            .takeIf { it > 0 }
            ?: nested.epochMillis("subscribe_time", "create_time", "modify_time")
        val item = RedfruitReservation(
            seriesId = seriesId,
            seriesTitle = row.firstText("series_title", "book_name", "title")
                .ifBlank { nested.firstText("title", "book_name", "series_title") },
            cover = safeCover(
                row.firstText("series_cover_url", "thumb_url", "cover_url", "cover")
                    .ifBlank { nested.firstText("cover", "cover_url", "thumb_url") },
            ),
            episodeCount = maxOf(row.episodeCount(), nested.episodeCount()),
            hotValue = hotValue,
            hotText = hotText,
            online = online,
            subscribedAt = subscribedAt,
        )
        val previous = result[seriesId]
        if (previous == null || previous.subscribedAt < item.subscribedAt) result[seriesId] = item
    }
    return result.values.sortedByDescending { it.subscribedAt }
}

private fun reservationHotText(row: JSONObject, nested: JSONObject): String {
    val direct = row.firstText("hot_score_text", "hot_text")
        .ifBlank { nested.firstText("hot_score_text", "hot_text") }
        .trim()
    if (direct.isNotBlank()) return direct
    val secondary = row.optJSONArray("secondary_info_list")
        ?: nested.optJSONArray("secondary_info_list")
        ?: return ""
    for (i in 0 until secondary.length()) {
        val content = secondary.optJSONObject(i)?.firstText("content", "text", "title")
            ?.trim()
            .orEmpty()
        if (content.contains("热度")) return content
    }
    return ""
}

private fun reservationOnline(row: JSONObject, nested: JSONObject): Boolean? {
    when {
        row.booleanValue("is_online") || nested.booleanValue("is_online") -> return true
        row.booleanValue("already_online") || nested.booleanValue("already_online") -> return true
        row.booleanValue("to_be_online") || nested.booleanValue("to_be_online") -> return false
        row.booleanValue("not_online") || nested.booleanValue("not_online") -> return false
    }
    val status = row.firstText("online_status", "subscribe_status", "status")
        .ifBlank { nested.firstText("online_status", "subscribe_status", "status") }
        .lowercase()
    return when {
        status.contains("online") && !status.contains("not") -> true
        status.contains("offline") || status.contains("待上线") -> false
        status.contains("已上线") -> true
        else -> null
    }
}

private fun firstPresentArray(data: JSONObject, vararg keys: String): JSONArray? {
    for (key in keys) {
        if (!data.has(key) || data.isNull(key)) continue
        val value = data.opt(key)
        when (value) {
            is JSONArray -> return value
            is JSONObject -> {
                // 单对象包一层，便于宽松兼容
                val wrapped = JSONArray()
                wrapped.put(value)
                return wrapped
            }
        }
    }
    return null
}

private fun JSONObject.requireCloudData(): JSONObject =
    optJSONObject("data") ?: throw RedfruitCloudException("红果账号响应缺少数据")

private fun JSONObject.requiredArray(
    key: String,
    label: String,
    max: Int,
    nullMeansEmpty: Boolean = false,
): JSONArray {
    if (nullMeansEmpty && has(key) && isNull(key)) return JSONArray()
    val rows = optJSONArray(key) ?: throw RedfruitCloudException("红果响应缺少$label")
    if (rows.length() > max) throw RedfruitCloudException("${label}超出本次读取范围")
    return rows
}

/** Android's JSON parser can return a Double for an out-of-range integer. Never round an ID. */
private fun JSONObject.exactNumber(key: String): String? = when (val value = opt(key)) {
    is Int, is Long, is BigInteger -> value.toString()
    is String -> value.trim().takeIf { it.matches(decimalPattern) }
    else -> null
}

private fun JSONObject.validSeriesId(vararg keys: String): String? =
    keys.firstNotNullOfOrNull { exactNumber(it)?.takeIf(String::isSeriesId) }

private fun JSONObject.validAccountId(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
    val value = opt(key)
    val text = if (value is String) value.trim() else exactNumber(key)
    text?.takeIf { it.matches(accountIdPattern) && it.any { char -> char != '0' } }
}

private fun JSONObject.firstText(vararg keys: String): String =
    keys.firstNotNullOfOrNull { str(it).takeIf(String::isNotBlank) }.orEmpty()

private fun JSONObject.boundedNumber(vararg keys: String, max: Long): Long =
    keys.firstNotNullOfOrNull { exactNumber(it)?.toLongOrNull()?.takeIf { value -> value in 0..max } } ?: 0

private fun JSONObject.epochMillis(vararg keys: String): Long =
    boundedNumber(*keys, max = 99_999_999_999_999).let { if (it in 1..9_999_999_999) it * 1_000 else it }

private fun JSONObject.episodeCount(): Int =
    boundedNumber("episode_cnt", "episode_count", "drama_chapter_number", max = 100_000).toInt()

private fun JSONObject.booleanValue(key: String): Boolean = str(key).let { it == "1" || it.equals("true", true) }

private fun JSONObject.hasExplicitGuestState(): Boolean =
    booleanValue("is_guest") || listOf("is_login", "has_login").any { key ->
        str(key).let { it == "0" || it.equals("false", true) }
    }

private fun JSONObject.confirmsLogin(): Boolean = booleanValue("is_login") || booleanValue("has_login")

private fun String.isSafeHeader(): Boolean = length <= 24_000 && all { it in ' '..'~' }

private fun String.isSeriesId(): Boolean = length in 1..30 && all { it in '0'..'9' } && any { it != '0' }

private val decimalPattern = Regex("-?[0-9]{1,30}")
private val accountIdPattern = Regex("[A-Za-z0-9_+./=:#-]{1,160}")

private fun safeCover(raw: String): String {
    val normalized = CoverUrls.normalize(raw).toHttpUrlOrNull() ?: return ""
    if (!normalized.isHttps || normalized.username.isNotEmpty() || normalized.password.isNotEmpty()) return ""
    return normalized.toString()
}
