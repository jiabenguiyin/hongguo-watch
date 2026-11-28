package com.hongguo.watch.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class UpdateMirrorPolicyTest {
    @Test
    fun defaultModeIsAutoWhenUnset() {
        assertEquals(UpdateMirrorPolicy.Mode.AUTO, UpdateMirrorPolicy.parseMode(null))
        assertEquals(UpdateMirrorPolicy.Mode.AUTO, UpdateMirrorPolicy.parseMode(""))
        assertEquals(UpdateMirrorPolicy.Mode.OFFICIAL, UpdateMirrorPolicy.parseMode("OFFICIAL"))
    }

    @Test
    fun normalizeDefaultsToMeali() {
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
            "https://gh.meali.top/$apk",
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
        val once = UpdateMirrorPolicy.rewrite(apk, "https://gh.meali.top/")
        assertEquals(once, UpdateMirrorPolicy.rewrite(once, "https://gh.meali.top/"))
    }

    @Test
    fun autoDownloadCandidatesFollowShanghaiLadderThenOfficial() {
        val apk =
            "https://github.com/jiabenguiyin/hongguo-watch/releases/download/v0.5.8/a.apk"
        val urls = UpdateMirrorPolicy.downloadCandidateUrls(
            preference = UpdateMirrorPolicy.Preference(
                mode = UpdateMirrorPolicy.Mode.AUTO,
                mirrorBase = UpdateMirrorPolicy.DEFAULT_MIRROR_BASE,
            ),
            officialApkUrl = apk,
            mainlandChina = true,
        )
        assertEquals(
            listOf(
                "https://gh.meali.top/$apk",
                "https://jiashu.1win.eu.org/$apk",
                "https://gh.monlor.com/$apk",
                "https://gitproxy.127731.xyz/$apk",
                "https://free.cn.eu.org/$apk",
                "https://cdn.akaere.online/$apk",
                "https://gh.inkchills.cn/$apk",
                "https://gh.chjina.com/$apk",
                apk,
            ),
            urls,
        )
        // 旧首选 / 仅 Dot 强节点不再进入 APK 主梯队
        assertFalse(urls.contains("https://ghproxy.imciel.com/$apk"))
        assertFalse(urls.contains("https://xsadwsd.kdns.fr/$apk"))
        assertFalse(urls.contains("https://ghfast.top/$apk"))
        assertFalse(urls.contains("https://gh-proxy.com/$apk"))
        assertEquals(apk, urls.last())
        assertEquals(urls.size, urls.toSet().size)
    }

    @Test
    fun nonMainlandAutoDownloadUsesOfficialOnly() {
        val apk =
            "https://github.com/jiabenguiyin/hongguo-watch/releases/download/v0.5.8/a.apk"
        val urls = UpdateMirrorPolicy.downloadCandidateUrls(
            preference = UpdateMirrorPolicy.Preference(
                mode = UpdateMirrorPolicy.Mode.AUTO,
                mirrorBase = UpdateMirrorPolicy.DEFAULT_MIRROR_BASE,
            ),
            officialApkUrl = apk,
            mainlandChina = false,
        )
        assertEquals(listOf(apk), urls)
    }

    @Test
    fun apiPoolIsIndependentFromApkSelectedMirror() {
        val api = "https://api.github.com/repos/jiabenguiyin/hongguo-watch/releases/latest"
        // 即使设置默认/选中 meali（APK 首选），检查更新也不得走 meali
        val urls = UpdateMirrorPolicy.apiCandidateUrls(
            UpdateMirrorPolicy.Preference(
                mode = UpdateMirrorPolicy.Mode.AUTO,
                mirrorBase = UpdateMirrorPolicy.DEFAULT_MIRROR_BASE,
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
        assertFalse(urls.any { it.contains("gh.meali.top") })
        assertFalse(urls.any { it.contains("jiashu.1win.eu.org") })
        assertEquals(api, urls.last())
        UpdateMirrorPolicy.API_PROXY_KEYS.forEach { key ->
            assertFalse(UpdateMirrorPolicy.APK_ONLY_MIRROR_KEYS.contains(key))
        }
    }

    @Test
    fun apiPoolIgnoresApkPreferenceEvenWhenNonDefault() {
        val api = "https://api.github.com/repos/jiabenguiyin/hongguo-watch/releases/latest"
        val urls = UpdateMirrorPolicy.apiCandidateUrls(
            UpdateMirrorPolicy.Preference(
                mode = UpdateMirrorPolicy.Mode.AUTO,
                mirrorBase = "https://free.cn.eu.org/",
            ),
            api,
        )
        assertFalse(urls.any { it.contains("free.cn.eu.org") })
        assertEquals("https://ghproxy.imciel.com/$api", urls.first())
        assertEquals(api, urls.last())
    }

    @Test
    fun officialModeOnlyUsesGithub() {
        val api = "https://api.github.com/repos/jiabenguiyin/hongguo-watch/releases/latest"
        val urls = UpdateMirrorPolicy.apiCandidateUrls(
            UpdateMirrorPolicy.Preference(
                mode = UpdateMirrorPolicy.Mode.OFFICIAL,
                mirrorBase = UpdateMirrorPolicy.DEFAULT_MIRROR_BASE,
            ),
            api,
        )
        assertEquals(listOf(api), urls)
    }

    @Test
    fun catalogIncludesDefaultAndLegacyNodes() {
        assertTrue(UpdateMirrorPolicy.PROXY_NODE_KEYS.contains("gh.meali.top"))
        assertTrue(UpdateMirrorPolicy.PROXY_NODE_KEYS.contains("ghproxy.imciel.com"))
        assertTrue(UpdateMirrorPolicy.PROXY_NODE_KEYS.contains("gh-proxy.com"))
        assertTrue(UpdateMirrorPolicy.PROXY_NODE_KEYS.contains("ghfast.top"))
        assertEquals("gh.meali.top", UpdateMirrorPolicy.PROXY_NODE_KEYS.first())
        assertFalse(UpdateMirrorPolicy.isGithubFamilyUrl("https://example.com/a.apk"))
        assertTrue(
            UpdateMirrorPolicy.isGithubFamilyUrl(
                "https://api.github.com/repos/jiabenguiyin/hongguo-watch/releases/latest",
            ),
        )
    }

    @Test
    fun mainlandDetectionUsesLocaleCountryOrTimezone() {
        assertTrue(
            UpdateMirrorPolicy.isLikelyMainlandChina(
                locale = Locale.SIMPLIFIED_CHINESE,
                timeZoneId = "Asia/Tokyo",
            ),
        )
        assertTrue(
            UpdateMirrorPolicy.isLikelyMainlandChina(
                locale = Locale.US,
                timeZoneId = "Asia/Shanghai",
            ),
        )
        assertFalse(
            UpdateMirrorPolicy.isLikelyMainlandChina(
                locale = Locale.JAPAN,
                timeZoneId = "Asia/Tokyo",
            ),
        )
        assertFalse(
            UpdateMirrorPolicy.isLikelyMainlandChina(
                locale = Locale.TRADITIONAL_CHINESE,
                timeZoneId = "Asia/Taipei",
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
            "gh.meali.top",
            UpdateMirrorPolicy.displayLabel(
                UpdateMirrorPolicy.Preference(
                    UpdateMirrorPolicy.Mode.AUTO,
                    UpdateMirrorPolicy.DEFAULT_MIRROR_BASE,
                ),
            ),
        )
    }
}
