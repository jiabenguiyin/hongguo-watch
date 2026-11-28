package com.hongguo.watch.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateMirrorPolicyTest {
    @Test
    fun defaultModeIsAutoWhenUnset() {
        assertEquals(UpdateMirrorPolicy.Mode.AUTO, UpdateMirrorPolicy.parseMode(null))
        assertEquals(UpdateMirrorPolicy.Mode.AUTO, UpdateMirrorPolicy.parseMode(""))
        assertEquals(UpdateMirrorPolicy.Mode.OFFICIAL, UpdateMirrorPolicy.parseMode("OFFICIAL"))
    }

    @Test
    fun normalizeDefaultsToImciel() {
        assertEquals(
            UpdateMirrorPolicy.DEFAULT_MIRROR_BASE,
            UpdateMirrorPolicy.normalizeMirrorBase(null),
        )
        assertEquals(
            "https://gh-proxy.com/",
            UpdateMirrorPolicy.normalizeMirrorBase("gh-proxy.com"),
        )
        assertEquals(
            UpdateMirrorPolicy.DEFAULT_MIRROR_BASE,
            UpdateMirrorPolicy.normalizeMirrorBase("https://evil.example/"),
        )
    }

    @Test
    fun rewritePrefixesFullGithubUrl() {
        val apk =
            "https://github.com/jiabenguiyin/hongguo-watch/releases/download/v0.5.8/hongguo-watch-0.5.8-arm64-v8a.apk"
        assertEquals(
            "https://ghproxy.imciel.com/$apk",
            UpdateMirrorPolicy.rewrite(apk, UpdateMirrorPolicy.DEFAULT_MIRROR_BASE),
        )
        assertEquals(
            "https://gh-proxy.com/$apk",
            UpdateMirrorPolicy.rewrite(apk, "https://gh-proxy.com/"),
        )
    }

    @Test
    fun rewriteDoesNotDoubleWrap() {
        val apk =
            "https://github.com/jiabenguiyin/hongguo-watch/releases/download/v0.5.8/a.apk"
        val once = UpdateMirrorPolicy.rewrite(apk, "https://ghproxy.imciel.com/")
        assertEquals(once, UpdateMirrorPolicy.rewrite(once, "https://ghproxy.imciel.com/"))
    }

    @Test
    fun autoDownloadCandidatesPreferDualSiteFailoverThenOfficial() {
        val apk =
            "https://github.com/jiabenguiyin/hongguo-watch/releases/download/v0.5.8/a.apk"
        val urls = UpdateMirrorPolicy.downloadCandidateUrls(
            UpdateMirrorPolicy.Preference(
                mode = UpdateMirrorPolicy.Mode.AUTO,
                mirrorBase = "https://ghproxy.imciel.com/",
            ),
            apk,
        )
        assertEquals("https://ghproxy.imciel.com/$apk", urls.first())
        assertEquals(
            listOf(
                "https://ghproxy.imciel.com/$apk",
                "https://jiashu.1win.eu.org/$apk",
                "https://xsadwsd.kdns.fr/$apk",
                "https://gitproxy.127731.xyz/$apk",
                "https://ghfast.top/$apk",
                "https://cdn.akaere.online/$apk",
                "https://gh.chjina.com/$apk",
                apk,
            ),
            urls,
        )
        // Dot 云电脑常 0 通过 / 上海弱节点不再进入 APK 下载回退
        assertFalse(urls.contains("https://gh-proxy.com/$apk"))
        assertFalse(urls.contains("https://cdn.gh-proxy.com/$apk"))
        assertFalse(urls.contains("https://gh.monlor.com/$apk"))
        assertFalse(urls.contains("https://gh.ddlc.top/$apk"))
        assertEquals(apk, urls.last())
        assertEquals(urls.size, urls.toSet().size)
    }

    @Test
    fun apiFailoverKeepsLegacyOrder() {
        val api = "https://api.github.com/repos/jiabenguiyin/hongguo-watch/releases/latest"
        val urls = UpdateMirrorPolicy.apiCandidateUrls(
            UpdateMirrorPolicy.Preference(
                mode = UpdateMirrorPolicy.Mode.AUTO,
                mirrorBase = "https://ghproxy.imciel.com/",
            ),
            api,
        )
        assertEquals(
            listOf(
                "https://ghproxy.imciel.com/$api",
                "https://gh-proxy.com/$api",
                "https://cdn.gh-proxy.com/$api",
                "https://ghfast.top/$api",
                "https://gh.ddlc.top/$api",
                "https://gh.monlor.com/$api",
                api,
            ),
            urls,
        )
    }

    @Test
    fun officialModeOnlyUsesGithub() {
        val api = "https://api.github.com/repos/jiabenguiyin/hongguo-watch/releases/latest"
        val urls = UpdateMirrorPolicy.apiCandidateUrls(
            UpdateMirrorPolicy.Preference(
                mode = UpdateMirrorPolicy.Mode.OFFICIAL,
                mirrorBase = "https://ghproxy.imciel.com/",
            ),
            api,
        )
        assertEquals(listOf(api), urls)
    }

    @Test
    fun catalogIncludesDefaultAndGhProxy() {
        assertTrue(UpdateMirrorPolicy.PROXY_NODE_KEYS.contains("ghproxy.imciel.com"))
        assertTrue(UpdateMirrorPolicy.PROXY_NODE_KEYS.contains("gh-proxy.com"))
        assertTrue(UpdateMirrorPolicy.PROXY_NODE_KEYS.contains("ghfast.top"))
        assertFalse(UpdateMirrorPolicy.isGithubFamilyUrl("https://example.com/a.apk"))
        assertTrue(
            UpdateMirrorPolicy.isGithubFamilyUrl(
                "https://api.github.com/repos/jiabenguiyin/hongguo-watch/releases/latest",
            ),
        )
    }

    @Test
    fun displayLabelShowsHostForAuto() {
        assertEquals(
            "GitHub 官方",
            UpdateMirrorPolicy.displayLabel(
                UpdateMirrorPolicy.Preference(UpdateMirrorPolicy.Mode.OFFICIAL, ""),
            ),
        )
        assertEquals(
            "ghproxy.imciel.com",
            UpdateMirrorPolicy.displayLabel(
                UpdateMirrorPolicy.Preference(
                    UpdateMirrorPolicy.Mode.AUTO,
                    UpdateMirrorPolicy.DEFAULT_MIRROR_BASE,
                ),
            ),
        )
    }
}
