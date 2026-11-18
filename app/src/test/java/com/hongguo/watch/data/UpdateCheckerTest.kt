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

    @Test
    fun parseMirrorBasesSplitsAndTrims() {
        assertEquals(
            listOf("https://ghproxy.net/", "https://ghfast.top/"),
            UpdateChecker.parseMirrorBases("https://ghproxy.net/, https://ghfast.top/"),
        )
        assertTrue(UpdateChecker.parseMirrorBases("").isEmpty())
        assertTrue(UpdateChecker.parseMirrorBases("  , , ").isEmpty())
    }

    @Test
    fun rewritePrefixMirrorKeepsFullGithubUrl() {
        val github =
            "https://github.com/jiabenguiyin/hongguo-watch/releases/download/v0.4.10/hongguo-watch-0.4.10-arm64-v8a.apk"
        assertEquals(
            "https://ghproxy.net/https://github.com/jiabenguiyin/hongguo-watch/releases/download/v0.4.10/hongguo-watch-0.4.10-arm64-v8a.apk",
            UpdateChecker.rewriteViaMirror("https://ghproxy.net/", github, "hongguo-watch-0.4.10-arm64-v8a.apk"),
        )
    }

    @Test
    fun rewriteFileMirrorUsesApkName() {
        val github =
            "https://github.com/jiabenguiyin/hongguo-watch/releases/download/v0.4.10/hongguo-watch-0.4.10-arm64-v8a.apk"
        assertEquals(
            "https://cdn.example.com/apk/hongguo-watch-0.4.10-arm64-v8a.apk",
            UpdateChecker.rewriteViaMirror(
                "file:https://cdn.example.com/apk/",
                github,
                "hongguo-watch-0.4.10-arm64-v8a.apk",
            ),
        )
    }

    @Test
    fun rewritePlaceholders() {
        val github =
            "https://github.com/jiabenguiyin/hongguo-watch/releases/download/v0.4.10/a.apk"
        assertEquals(
            "https://proxy.example/p/https://github.com/jiabenguiyin/hongguo-watch/releases/download/v0.4.10/a.apk",
            UpdateChecker.rewriteViaMirror(
                "https://proxy.example/p/{url}",
                github,
                "a.apk",
            ),
        )
        assertEquals(
            "https://cdn.example.com/hongguo/a.apk",
            UpdateChecker.rewriteViaMirror(
                "https://cdn.example.com/hongguo/{file}",
                github,
                "a.apk",
            ),
        )
    }

    @Test
    fun rewriteGithubFastInsertsFastInHost() {
        val github =
            "https://github.com/jiabenguiyin/hongguo-watch/releases/download/v0.4.10/hongguo-watch-0.4.10-arm64-v8a.apk"
        assertEquals(
            "https://githubfast.com/jiabenguiyin/hongguo-watch/releases/download/v0.4.10/hongguo-watch-0.4.10-arm64-v8a.apk",
            UpdateChecker.rewriteGithubFast(github),
        )
        assertEquals(
            null,
            UpdateChecker.rewriteGithubFast(
                "https://githubfast.com/jiabenguiyin/hongguo-watch/releases/download/v0.4.10/a.apk",
            ),
        )
        assertEquals(null, UpdateChecker.rewriteGithubFast("https://cdn.example.com/a.apk"))
    }

    @Test
    fun candidateUrlsPreferGithubFastThenOptionalMirrorsThenGithub() {
        val github =
            "https://github.com/jiabenguiyin/hongguo-watch/releases/download/v0.4.10/hongguo-watch-0.4.10-arm64-v8a.apk"
        val fast =
            "https://githubfast.com/jiabenguiyin/hongguo-watch/releases/download/v0.4.10/hongguo-watch-0.4.10-arm64-v8a.apk"
        val urls = UpdateChecker.candidateDownloadUrls(
            githubApkUrl = github,
            apkName = "hongguo-watch-0.4.10-arm64-v8a.apk",
            mirrorBases = listOf(
                "https://ghproxy.net/",
                "file:https://cdn.example.com/apk/",
            ),
        )
        assertEquals(
            listOf(
                fast,
                "https://ghproxy.net/https://github.com/jiabenguiyin/hongguo-watch/releases/download/v0.4.10/hongguo-watch-0.4.10-arm64-v8a.apk",
                "https://cdn.example.com/apk/hongguo-watch-0.4.10-arm64-v8a.apk",
                github,
            ),
            urls,
        )
    }

    @Test
    fun candidateUrlsDefaultOnlyGithubFastThenGithub() {
        val github =
            "https://github.com/jiabenguiyin/hongguo-watch/releases/download/v0.4.10/x.apk"
        assertEquals(
            listOf(
                "https://githubfast.com/jiabenguiyin/hongguo-watch/releases/download/v0.4.10/x.apk",
                github,
            ),
            UpdateChecker.candidateDownloadUrls(github, "x.apk", mirrorBases = emptyList()),
        )
    }

    private fun apk(name: String, url: String, size: Long): UpdateChecker.ApkAsset {
        return UpdateChecker.ApkAsset(name = name, url = url, size = size)
    }
}
