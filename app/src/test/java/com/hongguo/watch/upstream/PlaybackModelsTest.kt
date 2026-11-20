package com.hongguo.watch.upstream

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class PlaybackModelsTest {
    @Test
    fun retainsSameRenditionMirrorsWithoutAddingQualityChoices() {
        val ladder = PlaybackModels.renditions(
            JSONObject(
                """{"video_list":{
                    "high":{"quality_desc":"720p","codec_type":"h264",
                        "main_url":"https://v1.qznovelvod.com/high-main",
                        "play_url":"https://v1.qznovelvod.com/other-primary",
                        "backup_url_1":"https://v1.qznovelvod.com/high-one",
                        "backup_url_2":"https://v1.qznovelvod.com/high-two"},
                    "low":{"quality_desc":"480p","codec_type":"h264",
                        "main_url":"https://v1.qznovelvod.com/low-main",
                        "backup_url_1":"https://v1.qznovelvod.com/low-one"}
                }}""",
            ),
        )

        assertEquals(2, ladder.size)
        val first = requireNotNull(StreamFailover.pick(ladder, 720, emptySet()))
        assertEquals("https://v1.qznovelvod.com/high-main", first.url)
        assertEquals(
            listOf("https://v1.qznovelvod.com/high-one", "https://v1.qznovelvod.com/high-two"),
            first.backupUrls,
        )
        val afterHigh = setOf(StreamFailover.key(first))
        val second = requireNotNull(StreamFailover.pick(ladder, 720, afterHigh))
        assertEquals("https://v1.qznovelvod.com/low-main", second.url)
        assertEquals(listOf("https://v1.qznovelvod.com/low-one"), second.backupUrls)
        assertNull(StreamFailover.pick(ladder, 720, afterHigh + StreamFailover.key(second)))
    }

    @Test
    fun removesDuplicateMirrorsAndPreservesExistingBackupOnePromotion() {
        val parsed = PlaybackModels.renditions(
            JSONObject(
                """{"video_list":[
                    {"main_url":"https://v1.qznovelvod.com/main",
                        "backup_url_1":"https://V1.QZNOVELVOD.COM:443/main",
                        "backup_url_2":"https://v1.qznovelvod.com/mirror"},
                    {"backup_url_1":"https://v1.qznovelvod.com/primary",
                        "backup_url_2":"https://v1.qznovelvod.com/secondary"},
                    {"main_url":"https://v1.qznovelvod.com/third",
                        "backup_url_1":"https://v1.qznovelvod.com/duplicate",
                        "backup_url_2":"https://V1.QZNOVELVOD.COM:443/duplicate"}
                ]}""",
            ),
        )

        assertEquals(listOf("https://v1.qznovelvod.com/mirror"), parsed[0].backupUrls)
        assertFalse(parsed[0].isCdnBackup)
        assertEquals("https://v1.qznovelvod.com/primary", parsed[1].url)
        assertEquals(listOf("https://v1.qznovelvod.com/secondary"), parsed[1].backupUrls)
        assertTrue(parsed[1].isCdnBackup)
        assertEquals(listOf("https://v1.qznovelvod.com/duplicate"), parsed[2].backupUrls)
    }

    @Test
    fun mirrorsDoNotMakePreviouslyRejectedRenditionsPlayable() {
        val parsed = PlaybackModels.renditions(
            JSONObject(
                """{"video_list":[
                    {"codec_type":"bytevc2","backup_url_1":"https://v1.qznovelvod.com/unsupported"},
                    {"encrypt_info":{"encrypt":true},"backup_url_1":"https://v1.qznovelvod.com/no-key"},
                    {"backup_url_1":"http://v1.qznovelvod.com/insecure"},
                    {"backup_url_2":"https://untrusted.example.test/no-primary"},
                    {"main_url":"https://v1.qznovelvod.com/valid",
                        "backup_url_1":"https://user:pass@v1.qznovelvod.com/credentials",
                        "backup_url_2":"file:///tmp/not-video",
                        "backup_url_3":"https://v1.qznovelvod.com/ignored"}
                ]}""",
            ),
        )

        assertEquals("https://v1.qznovelvod.com/valid", parsed.single().url)
        assertEquals(emptyList<String>(), parsed.single().backupUrls)
    }

    @Test
    fun resolvesEncodedHttpsMirrorsWithoutAcceptingInsecureBackupAddresses() {
        val encoded = Base64.getEncoder().encodeToString("https://v1.qznovelvod.com/cdn".toByteArray())
        val parsed = PlaybackModels.renditions(
            JSONObject(
                """{"video_list":[{"main_url":"https://v1.qznovelvod.com/main",
                    "backup_url_1":"$encoded","backup_url_2":"http://v1.qznovelvod.com/insecure"}]}""",
            ),
        )
        assertEquals(listOf("https://v1.qznovelvod.com/cdn"), parsed.single().backupUrls)
    }

    @Test
    fun recognizesSingularAndListBackupsAndCanStartFromAListOnlyModel() {
        val parsed = PlaybackModels.renditions(
            JSONObject(
                """{"video_list":[
                    {"main_url":"https://v1.qznovelvod.com/main",
                        "backup_url":"https://v2.fqnovelvod.com/a",
                        "backup_url_list":["https://v2.fqnovelvod.com/a",
                            "https://v3.douyinvod.com/b","https://qznovelvod.com.example.test/not-official"]},
                    {"backup_url_list":["https://v2.fqnovelvod.com/only"]}
                ]}""",
            ),
        )
        assertEquals(listOf("https://v2.fqnovelvod.com/a", "https://v3.douyinvod.com/b"), parsed[0].backupUrls)
        assertEquals("https://v2.fqnovelvod.com/only", parsed[1].url)
        assertEquals(emptyList<String>(), parsed[1].backupUrls)
        assertTrue(parsed[1].isCdnBackup)
    }
}
