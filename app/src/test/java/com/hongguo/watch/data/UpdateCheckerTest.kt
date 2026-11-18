package com.hongguo.watch.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    @Test
    fun normalizeStripsVPrefix() {
        assertEquals("0.4.2", UpdateChecker.normalizeVersionName("v0.4.2"))
        assertEquals("0.4.1", UpdateChecker.normalizeVersionName("0.4.1"))
    }

    @Test
    fun newerBySemver() {
        assertTrue(UpdateChecker.isNewer("0.4.2", "0.4.1", localCode = 7))
        assertFalse(UpdateChecker.isNewer("0.4.1", "0.4.1", localCode = 7))
        assertFalse(UpdateChecker.isNewer("0.4.0", "0.4.1", localCode = 7))
    }

    @Test
    fun pickApkPrefersDeviceAbi() {
        val assets = listOf(
            apk("hongguo-watch-0.4.4-armeabi-v7a.apk", "https://example/v7a.apk", 11),
            apk("hongguo-watch-0.4.4-arm64-v8a.apk", "https://example/arm64.apk", 22),
        )
        val picked = UpdateChecker.pickApkAsset(assets, "arm64-v8a")
        assertNotNull(picked)
        assertEquals("hongguo-watch-0.4.4-arm64-v8a.apk", picked!!.name)
        assertEquals("https://example/arm64.apk", picked.url)
        assertEquals(22L, picked.size)
    }

    @Test
    fun pickApkFallsBackToUniversalThenFirst() {
        val withUniversal = listOf(
            apk("hongguo-watch-0.4.4-armeabi-v7a.apk", "https://example/v7a.apk", 11),
            apk("hongguo-watch-0.4.4-universal.apk", "https://example/uni.apk", 33),
        )
        assertEquals(
            "hongguo-watch-0.4.4-universal.apk",
            UpdateChecker.pickApkAsset(withUniversal, "x86_64")!!.name,
        )

        val firstOnly = listOf(
            apk("hongguo-watch-0.4.4.apk", "https://example/plain.apk", 9),
        )
        assertEquals(
            "hongguo-watch-0.4.4.apk",
            UpdateChecker.pickApkAsset(firstOnly, "arm64-v8a")!!.name,
        )
    }

    @Test
    fun sanitizeKeepsApkNameSafe() {
        assertEquals(
            "hongguo-watch-0.4.4-arm64-v8a.apk",
            UpdateApkInstaller.sanitizeFileName("hongguo-watch-0.4.4-arm64-v8a.apk"),
        )
        assertEquals(
            "weird_name.apk",
            UpdateApkInstaller.sanitizeFileName("../weird name.apk"),
        )
    }

    private fun apk(name: String, url: String, size: Long): UpdateChecker.ApkAsset {
        return UpdateChecker.ApkAsset(name = name, url = url, size = size)
    }
}
