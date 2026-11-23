package com.hongguo.watch.data.redfruit

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StoredRedfruitSessionTest {
    @Test
    fun retainsDistinctBusinessAndShelfIdsAfterRestore() {
        val session = StoredRedfruitSession(
            credentials = RedfruitCredentials("sessionid=example", "example-token"),
            profile = RedfruitCloudProfile("business-123", "示例用户", "456"),
        )

        assertEquals(session, StoredRedfruitSession.read(JSONObject(session.toJson().toString())))
    }

    @Test
    fun legacyFileStillRestoresCredentialsButDoesNotAssumeShelfId() {
        val legacyJson = JSONObject()
            .put("cookie", "sessionid=example")
            .put("accountId", "business-123")
            .put("accountName", "示例用户")

        val restored = StoredRedfruitSession.read(legacyJson)

        assertEquals(RedfruitCredentials("sessionid=example"), restored.credentials)
        assertEquals("business-123", restored.profile.id)
        assertNull(restored.profile.shelfUserId)
    }

    @Test
    fun persistsAutoSyncPreferenceAndPendingFavoriteMutations() {
        val session = StoredRedfruitSession(
            credentials = RedfruitCredentials("sessionid=example", "example-token"),
            profile = RedfruitCloudProfile("business-123", "示例用户", "456"),
            autoSyncEnabled = true,
            pendingFavoriteChanges = mapOf("123456" to false, "789012" to true),
        )

        assertEquals(session, StoredRedfruitSession.read(JSONObject(session.toJson().toString())))
    }

    @Test
    fun keepsInitialMergePromptCountsAcrossRestart() {
        val session = StoredRedfruitSession(
            credentials = RedfruitCredentials("sessionid=example", "example-token"),
            profile = RedfruitCloudProfile("business-123", "示例用户", "456"),
            initialMergeHistoryCount = 3,
            initialMergeFavoriteCount = 2,
        )

        val restored = StoredRedfruitSession.read(JSONObject(session.toJson().toString()))

        assertEquals(session, restored)
        assertEquals(3 to 2, restored.initialMergeCounts)
    }
}
