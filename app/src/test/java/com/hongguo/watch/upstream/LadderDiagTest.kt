package com.hongguo.watch.upstream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LadderDiagTest {
    @Test
    fun summaryDistinguishesMissingAvcVsPresent() {
        val hevcOnly = LadderParseResult(
            accepted = listOf(
                Rendition(480, "bytevc1", 854, 480, "https://example.test/h", null),
            ),
            diag = listOf(
                LadderEntryDiag(
                    index = 0,
                    listKey = "v0",
                    codecType = "bytevc1",
                    gearDesKey = "bytevc1_480",
                    videoMetaCodec = "bytevc1",
                    videoMetaSummary = "codec_type=bytevc1,definition=480p",
                    width = 854,
                    height = 480,
                    quality = 480,
                    normalizedCodec = "bytevc1",
                    isAvc = false,
                    isHevc = true,
                    hasUrl = true,
                    accepted = true,
                    filterReason = "accepted",
                ),
            ),
        )
        assertTrue(
            hevcOnly.summaryLine(480, preferAvc = true, avcOnly = true)
                .contains("verdict=AVC_MISSING_HEVC_ONLY"),
        )

        val withAvc = LadderParseResult(
            accepted = listOf(
                Rendition(360, "h264", 640, 360, "https://example.test/a", null),
            ),
            diag = listOf(
                LadderEntryDiag(
                    index = 0,
                    listKey = "v1",
                    codecType = "",
                    gearDesKey = "avc1_360",
                    videoMetaCodec = "h264",
                    videoMetaSummary = "codec_type=h264",
                    width = 640,
                    height = 360,
                    quality = 360,
                    normalizedCodec = "h264",
                    isAvc = true,
                    isHevc = false,
                    hasUrl = true,
                    accepted = true,
                    filterReason = "accepted",
                ),
                LadderEntryDiag(
                    index = 1,
                    listKey = "v2",
                    codecType = "bytevc2",
                    gearDesKey = "bvc2_720",
                    videoMetaCodec = "",
                    videoMetaSummary = "-",
                    width = null,
                    height = null,
                    quality = 720,
                    normalizedCodec = "bytevc2",
                    isAvc = false,
                    isHevc = false,
                    hasUrl = false,
                    accepted = false,
                    filterReason = "unsupported_bytevc2",
                ),
            ),
        )
        val line = withAvc.summaryLine(480, preferAvc = true, avcOnly = true)
        assertTrue(line.contains("verdict=AVC_PRESENT"))
        assertEquals(1, withAvc.avcAccepted)
        assertTrue(withAvc.diag.any { it.filterReason == "unsupported_bytevc2" })
        val keep = withAvc.diag.first { it.accepted }.toLogLine()
        assertTrue(keep.contains("codec_type="))
        assertTrue(keep.contains("gear_des_key=avc1_360"))
        assertTrue(keep.contains("video_meta.codec=h264"))
    }
}
