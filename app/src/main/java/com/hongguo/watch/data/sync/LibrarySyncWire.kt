package com.hongguo.watch.data.sync

import com.hongguo.watch.data.db.FavoriteEntity
import com.hongguo.watch.data.db.LibraryChangeEntity
import com.hongguo.watch.data.db.WatchHistoryEntity
import com.hongguo.watch.data.model.SeriesSource
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

internal object LibrarySyncWire {
    private const val MAX_NUMBER = 9_007_199_254_740_991L
    private const val MAX_EPISODES = 100_000
    private const val MAX_CHANGES = 250
    private const val MAX_IMPORT_ROWS = 100
    private const val MAX_REMOTE_ROWS = 2000
    private const val MAX_ID_LENGTH = 128
    private const val MAX_TITLE_LENGTH = 256
    private const val MAX_SOURCE_LENGTH = 32
    private const val MAX_COVER_LENGTH = 1024
    private val mutationPattern = Regex("[A-Za-z0-9._:-]{1,128}")

    data class Result(
        val cursor: Long,
        val history: List<WatchHistoryEntity>,
        val favorites: List<FavoriteEntity>,
        val accepted: Map<Long, Long>,
        val imported: Boolean,
    )

    fun request(
        cursor: Long,
        installationId: String,
        changes: List<LibraryChangeEntity>,
        history: List<WatchHistoryEntity>,
        favorites: List<FavoriteEntity>,
    ): JSONObject {
        validateCursor(cursor)
        val historyById = history.associateBy { it.seriesId }
        val favoritesById = favorites.associateBy { it.seriesId }
        val mutations = JSONArray()
        for ((id, change) in mutationIds(installationId, changes)) {
            val kind = change.kind
            val seriesId = change.seriesId
            val deleted = change.deleted
            val mutation = JSONObject().put("id", id).put("kind", kind).put("deleted", deleted)
            validateCursor(change.baseCursor)
            if (change.baseCursor > cursor) throw SyncServiceException()
            mutation.put("baseCursor", change.baseCursor)
            when (kind) {
                LibraryChangeEntity.HISTORY_CLEAR -> {
                    if (!deleted || seriesId.isNotEmpty()) throw SyncServiceException()
                    mutation.put("seriesId", seriesId)
                }
                LibraryChangeEntity.HISTORY, LibraryChangeEntity.FAVORITE -> {
                    mutation.put("seriesId", validateText(seriesId, MAX_ID_LENGTH))
                    if (!deleted) {
                        val row = when (kind) {
                            LibraryChangeEntity.HISTORY -> historyById[seriesId]?.toJson()
                            else -> favoritesById[seriesId]?.toJson()
                        }
                        mutation.put("value", row ?: throw SyncServiceException())
                    }
                }
                else -> throw SyncServiceException()
            }
            mutations.put(mutation)
        }
        return JSONObject().put("cursor", cursor).put("changes", mutations)
    }

    fun importRequest(cursor: Long, history: List<WatchHistoryEntity>, favorites: List<FavoriteEntity>): JSONObject {
        validateCursor(cursor)
        validateRows(history.size, favorites.size, MAX_IMPORT_ROWS)
        if (history.distinctBy { it.seriesId }.size != history.size ||
            favorites.distinctBy { it.seriesId }.size != favorites.size
        ) {
            throw SyncServiceException()
        }
        val historyRows = JSONArray()
        val favoriteRows = JSONArray()
        history.sortedBy { it.seriesId }.forEach { historyRows.put(it.toJson()) }
        favorites.sortedBy { it.seriesId }.forEach { favoriteRows.put(it.toJson()) }
        val rows = JSONObject().put("history", historyRows).put("favorites", favoriteRows)
        return JSONObject().put("cursor", cursor).put("import", rows)
    }

    fun parse(
        json: JSONObject,
        sentCursor: Long,
        installationId: String,
        sentChanges: List<LibraryChangeEntity>,
    ): Result {
        validateCursor(sentCursor)
        val cursor = json.number("cursor")
        if (cursor < sentCursor) throw SyncServiceException()
        val expected = mutationIds(installationId, sentChanges)
        val accepted = json.opt("accepted") as? JSONObject ?: throw SyncServiceException()
        val conflicts = json.array("conflicts")
        if (accepted.length() > expected.size || conflicts.length() != expected.size - accepted.length()) {
            throw SyncServiceException()
        }
        val acknowledgedIds = HashSet<String>(expected.size)
        val receipts = buildMap<Long, Long> {
            for (id in accepted.keys()) {
                val change = expected[id] ?: throw SyncServiceException()
                val revision = accepted.number(id, cursor)
                if (revision == 0L || !acknowledgedIds.add(id)) throw SyncServiceException()
                put(change.sequence, revision)
            }
        }
        for (index in 0 until conflicts.length()) {
            val id = conflicts.opt(index) as? String ?: throw SyncServiceException()
            if (id !in expected || !acknowledgedIds.add(id)) throw SyncServiceException()
        }
        if (acknowledgedIds != expected.keys) throw SyncServiceException()

        val history = json.array("history")
        val favorites = json.array("favorites")
        validateRows(history.length(), favorites.length(), MAX_REMOTE_ROWS)
        val imported = json.opt("imported") as? Boolean ?: throw SyncServiceException()
        return Result(cursor, history.rows { toHistory() }, favorites.rows { toFavorite() }, receipts, imported)
    }

    private fun mutationIds(
        installationId: String,
        changes: List<LibraryChangeEntity>,
    ): Map<String, LibraryChangeEntity> {
        if (changes.size > MAX_CHANGES) throw SyncServiceException()
        return buildMap {
            for (change in changes) {
                val id = "$installationId:${change.sequence}"
                if (installationId.isEmpty() || change.sequence <= 0 || !mutationPattern.matches(id) ||
                    put(id, change) != null
                ) {
                    throw SyncServiceException()
                }
            }
        }
    }

    private fun WatchHistoryEntity.toJson(): JSONObject = record(seriesId, seriesTitle, cover, source, updatedAt)
        .put("episodeId", validateText(episodeId, MAX_ID_LENGTH))
        .put("episodeNumber", episodeNumber.coerceIn(0, MAX_EPISODES))
        .put("positionMs", positionMs.coerceIn(0, MAX_NUMBER))
        .put("durationMs", durationMs.coerceIn(0, MAX_NUMBER))

    private fun FavoriteEntity.toJson(): JSONObject = record(seriesId, seriesTitle, cover, source, updatedAt)
        .put("episodeCount", episodeCount.coerceIn(0, MAX_EPISODES))

    private fun record(seriesId: String, title: String, cover: String, source: String, updatedAt: Long): JSONObject =
        JSONObject()
            .put("seriesId", validateText(seriesId, MAX_ID_LENGTH))
            .put("seriesTitle", sanitizeMetadata(title, MAX_TITLE_LENGTH))
            .put("cover", sanitizedCover(cover))
            .put("source", sanitizeMetadata(source, MAX_SOURCE_LENGTH).ifBlank { SeriesSource.APP.name })
            .put("updatedAt", updatedAt.coerceIn(0, MAX_NUMBER))

    private fun JSONObject.toHistory(): WatchHistoryEntity = WatchHistoryEntity(
        seriesId = text("seriesId", MAX_ID_LENGTH),
        seriesTitle = text("seriesTitle", MAX_TITLE_LENGTH, blankAllowed = true),
        cover = cover(),
        episodeId = text("episodeId", MAX_ID_LENGTH),
        episodeNumber = number("episodeNumber", MAX_EPISODES.toLong()).toInt(),
        positionMs = number("positionMs"),
        durationMs = number("durationMs"),
        updatedAt = number("updatedAt"),
        source = text("source", MAX_SOURCE_LENGTH),
    )

    private fun JSONObject.toFavorite(): FavoriteEntity = FavoriteEntity(
        seriesId = text("seriesId", MAX_ID_LENGTH),
        seriesTitle = text("seriesTitle", MAX_TITLE_LENGTH, blankAllowed = true),
        cover = cover(),
        source = text("source", MAX_SOURCE_LENGTH),
        episodeCount = number("episodeCount", MAX_EPISODES.toLong()).toInt(),
        updatedAt = number("updatedAt"),
    )

    private fun <T> JSONArray.rows(parse: JSONObject.() -> T): List<T> {
        val keys = HashSet<String>(length())
        return List(length()) { index ->
            val row = opt(index) as? JSONObject ?: throw SyncServiceException()
            if (!keys.add(row.text("seriesId", MAX_ID_LENGTH))) throw SyncServiceException()
            row.parse()
        }
    }

    private fun JSONObject.array(name: String): JSONArray = opt(name) as? JSONArray ?: throw SyncServiceException()

    private fun JSONObject.number(name: String, max: Long = MAX_NUMBER): Long {
        val number = when (val value = opt(name)) {
            is Int -> value.toLong()
            is Long -> value
            else -> throw SyncServiceException()
        }
        if (number !in 0..max) throw SyncServiceException()
        return number
    }

    private fun JSONObject.text(name: String, limit: Int, blankAllowed: Boolean = false): String =
        validateText(opt(name) as? String ?: throw SyncServiceException(), limit, blankAllowed)

    private fun JSONObject.cover(): String {
        val value = text("cover", MAX_COVER_LENGTH, blankAllowed = true)
        if (value.isEmpty()) return ""
        val url = httpsCoverUrl(value) ?: throw SyncServiceException()
        if (url.encodedQuery != null || url.fragment != null) throw SyncServiceException()
        return url.toString()
    }

    private fun sanitizedCover(value: String): String = httpsCoverUrl(value)
        ?.newBuilder()?.query(null)?.fragment(null)?.build()?.toString()
        ?.takeIf { it.length <= MAX_COVER_LENGTH }.orEmpty()

    private fun httpsCoverUrl(value: String) = value.toHttpUrlOrNull()?.takeIf {
        value.startsWith("https://", ignoreCase = true) && it.isHttps &&
            it.encodedUsername.isEmpty() && it.encodedPassword.isEmpty() &&
            value.none { character -> character.code < 32 || character == '\\' } &&
            !value.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#').contains('@')
    }

    private fun validateCursor(cursor: Long) {
        if (cursor !in 0..MAX_NUMBER) throw SyncServiceException()
    }

    private fun validateRows(historyCount: Int, favoriteCount: Int, limit: Int) {
        if (historyCount > limit || favoriteCount > limit - historyCount) throw SyncServiceException()
    }

    private fun validateText(value: String, limit: Int, blankAllowed: Boolean = false): String {
        if ((!blankAllowed && value.isBlank()) || Character.codePointCount(value, 0, value.length) > limit) {
            throw SyncServiceException()
        }
        var index = 0
        while (index < value.length) {
            val point = Character.codePointAt(value, index)
            if (!isAllowedPoint(point)) throw SyncServiceException()
            index += Character.charCount(point)
        }
        return value
    }

    private fun sanitizeMetadata(value: String, limit: Int): String = buildString {
        var index = 0
        var points = 0
        while (index < value.length && points < limit) {
            val point = Character.codePointAt(value, index)
            appendCodePoint(if (isAllowedPoint(point)) point else ' '.code)
            index += Character.charCount(point)
            points++
        }
    }

    private fun isAllowedPoint(point: Int): Boolean = point >= 32 && point !in 0xD800..0xDFFF
}
