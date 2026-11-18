package com.hongguo.watch.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverUrlsTest {
    @Test
    fun heicNovelPicRewritesToJpeg() {
        val raw =
            "https://p6-novel.byteimg.com/novel-pic/tos-cn-i-xxx/abcdef~tplv-crop-center:360:480.heic"
        val out = CoverUrls.normalize(raw)
        assertTrue(out.startsWith("https://p3-novel.byteimg.com/novel-pic/"))
        assertTrue(out.endsWith("~tplv-shrink:640:0.image"))
        assertFalse(out.contains(".heic", ignoreCase = true))
    }

    @Test
    fun jpegPassthroughHttpsUpgrade() {
        val raw = "http://example.com/cover.jpg"
        assertEquals("https://example.com/cover.jpg", CoverUrls.normalize(raw))
    }

    @Test
    fun blankAndInvalidRejected() {
        assertEquals("", CoverUrls.normalize(""))
        assertEquals("", CoverUrls.normalize("not-a-url"))
    }
}
