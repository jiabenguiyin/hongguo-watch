package com.hongguo.watch.data

import java.util.Locale
import java.util.TimeZone

/**
 * GitHub Releases 检查 / APK 下载镜像策略。
 *
 * 节点来自 [hiwiki 镜像目录](https://git.hiwiki.org/zh-CN/mirrors) 的 `proxyNodeKeys`，
 * 默认与上海测速首选一致：`https://gh.meali.top/` 前缀代理（`{base}{完整 GitHub URL}`）。
 * 「自动镜像」在大陆网络下失败时依次尝试备用节点，最后回落官方 GitHub。
 *
 * APK 下载回退顺序按 2026-11-28 **上海**测速单独维护（日本 Dot 仅作海外参考）；
 * 非大陆网络 APK 下载跳过镜像、直连 GitHub。
 * 发布 API 未随该表测试，仍用 [API_FAILOVER_KEYS]，勿与下载列表混改。
 */
object UpdateMirrorPolicy {
    enum class Mode {
        /** 直连 GitHub / api.github.com */
        OFFICIAL,

        /** 前缀代理；默认 gh.meali.top，可在设置中调整 */
        AUTO,
    }

    data class Node(
        val key: String,
        val base: String,
    )

    data class Preference(
        val mode: Mode,
        val mirrorBase: String,
    )

    const val GITHUB_HOME = "https://github.com/"
    const val DEFAULT_MIRROR_KEY = "gh.meali.top"
    const val DEFAULT_MIRROR_BASE = "https://gh.meali.top/"

    val DEFAULT_NODE = Node(DEFAULT_MIRROR_KEY, DEFAULT_MIRROR_BASE)

    /** 中国大陆常用时区（不含港台澳）。 */
    private val MAINLAND_TIME_ZONE_IDS: Set<String> = setOf(
        "Asia/Shanghai",
        "Asia/Chongqing",
        "Asia/Harbin",
        "Asia/Urumqi",
        "Asia/Kashgar",
        "PRC",
    )

    /**
     * hiwiki `proxyNodeKeys`（2026-11）：前缀代理主机名列表。
     * 全部内置供「调整镜像」选择；下载时仅对选中项 + 上海梯队做故障转移。
     * 列表靠前优先上海测速 TOP 主机（2026-11-28）。
     */
    val PROXY_NODE_KEYS: List<String> = listOf(
        "gh.meali.top",
        "jiashu.1win.eu.org",
        "gh.monlor.com",
        "gitproxy.127731.xyz",
        "free.cn.eu.org",
        "cdn.akaere.online",
        "gh.inkchills.cn",
        "gh.chjina.com",
        "ghproxy.imciel.com",
        "xsadwsd.kdns.fr",
        "ghfast.top",
        "github.ednovas.xyz",
        "down.mxw.xx.kg",
        "tvv.tw",
        "git.669966.xyz",
        "gh.my-website.ccwu.cc",
        "fastgit.cc",
        "gh.acmsz.top",
        "ghm.078465.xyz",
        "gg.z321.cc.cd",
        "git.yylx.win",
        "gh.idayer.com",
        "777.z321.cc.cd",
        "gh-proxy.com",
        "gh.ddlc.top",
        "cdn.gh-proxy.com",
        "g.z321.cc.cd",
        "gh.07150721.xyz",
        "github.tbap.top",
        "gh.noki.icu",
        "ghproxy.felicity.land",
        "github.xxlab.tech",
        "gh.sixyin.com",
        "ghfile.geekertao.top",
        "gh.dpik.top",
        "ghproxy.monkeyray.net",
        "ghproxy.net",
        "g.blfrp.cn",
        "github-proxy.memory-echoes.cn",
        "github.dpik.top",
        "down.mxw.qzz.io",
        "gitproxy.mrhjx.cn",
        "gh.927223.xyz",
        "gh.felicity.ac.cn",
        "gh.catmak.name",
        "github.chenc.dev",
        "ghpxy.hwinzniej.top",
        "github.geekery.cn",
        "ghproxy.1888866.xyz",
        "ghp.arslantu.xyz",
        "ghproxy.cxkpro.top",
        "ghp.keleyaa.com",
        "github.ikgy.top",
        "cfgh.ikgy.top",
        "github.nswrz.cn",
        "gh.qfmc0721.cc.cd",
        "github.mxw.qzz.io",
        "gh.bugdey.us.kg",
        "gh.jasonzeng.dev",
        "gp.zkitefly.eu.org",
        "githubdog.com",
        "gh.b52m.cn",
        "js.jiangss.shop",
        "proxy.yaoyaoling.net",
        "gh.ruan.dpdns.org",
        "ghf.xn--eqrr82bzpe.top",
    )

    /**
     * APK 下载备用前缀（2026-11-28 上海测速权威排序）。
     * 默认首选 [DEFAULT_MIRROR_KEY]；本表为其后的大陆梯队，最后回落 GitHub 官方。
     * 不含仅在日本 Dot 表现好的 xsadwsd.kdns.fr / ghfast.top，也不把旧首选
     * ghproxy.imciel.com 放进主梯队（目录中仍可选）。
     */
    private val DOWNLOAD_FAILOVER_KEYS: List<String> = listOf(
        "jiashu.1win.eu.org",
        "gh.monlor.com",
        "gitproxy.127731.xyz",
        "free.cn.eu.org",
        "cdn.akaere.online",
        "gh.inkchills.cn",
        "gh.chjina.com",
    )

    /**
     * 发布 API（api.github.com）备用前缀：未随 2026-11-28 APK 表测试，保持原序。
     */
    private val API_FAILOVER_KEYS: List<String> = listOf(
        "gh-proxy.com",
        "cdn.gh-proxy.com",
        "ghfast.top",
        "gh.ddlc.top",
        "gh.monlor.com",
    )

    val BUILT_IN_NODES: List<Node> = PROXY_NODE_KEYS.map { key ->
        Node(key = key, base = "https://$key/")
    }

    /**
     * 是否像中国大陆设备环境（不含港台澳）。
     * 依据系统国家码 `CN`，或大陆时区；不依赖电话权限。
     * 非大陆时 APK 自动镜像会跳过 CN 节点、直连 GitHub。
     */
    fun isLikelyMainlandChina(
        locale: Locale = Locale.getDefault(),
        timeZoneId: String = TimeZone.getDefault().id,
    ): Boolean {
        val country = locale.country.trim().uppercase(Locale.ROOT)
        if (country == "CN") return true
        return timeZoneId.trim() in MAINLAND_TIME_ZONE_IDS
    }

    fun parseMode(raw: String?): Mode = when (raw?.trim()?.uppercase()) {
        Mode.OFFICIAL.name -> Mode.OFFICIAL
        else -> Mode.AUTO
    }

    fun normalizeMirrorBase(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return DEFAULT_MIRROR_BASE
        val withScheme = when {
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.startsWith("http://", ignoreCase = true) -> "https://" + trimmed.removePrefix("http://")
            else -> "https://$trimmed"
        }
        val base = withScheme.trimEnd('/') + "/"
        val host = hostOf(base) ?: return DEFAULT_MIRROR_BASE
        // 仅允许内置目录主机，避免任意用户输入变成开放代理入口
        if (PROXY_NODE_KEYS.none { it.equals(host, ignoreCase = true) }) {
            return DEFAULT_MIRROR_BASE
        }
        return "https://$host/"
    }

    fun nodeForBase(base: String): Node {
        val normalized = normalizeMirrorBase(base)
        val host = hostOf(normalized) ?: DEFAULT_MIRROR_KEY
        return BUILT_IN_NODES.firstOrNull { it.key.equals(host, ignoreCase = true) }
            ?: DEFAULT_NODE
    }

    fun displayLabel(preference: Preference): String = when (preference.mode) {
        Mode.OFFICIAL -> "GitHub 官方"
        Mode.AUTO -> nodeForBase(preference.mirrorBase).key
    }

    /**
     * 前缀改写：`https://mirror.example/` + `https://github.com/...`
     * 已是某一内置镜像前缀时原样返回，避免双重包装。
     */
    fun rewrite(url: String, mirrorBase: String): String {
        val original = url.trim()
        if (original.isEmpty()) return original
        if (!isGithubFamilyUrl(original)) return original
        val base = normalizeMirrorBase(mirrorBase).trimEnd('/') + "/"
        if (original.startsWith(base)) return original
        for (node in BUILT_IN_NODES) {
            val prefix = node.base.trimEnd('/') + "/"
            if (original.startsWith(prefix)) return original
        }
        return base + original
    }

    fun apiCandidateUrls(
        preference: Preference,
        officialApi: String,
    ): List<String> = candidateUrls(officialApi, preference, API_FAILOVER_KEYS)

    /**
     * APK 下载候选地址。
     * - 官方模式：仅官方 URL
     * - 非大陆：即使自动镜像也只走官方 GitHub（跳过 CN 镜像）
     * - 大陆自动镜像：选中节点 → 上海梯队 → 官方
     */
    fun downloadCandidateUrls(
        preference: Preference,
        officialApkUrl: String,
        mainlandChina: Boolean = isLikelyMainlandChina(),
    ): List<String> {
        val original = officialApkUrl.trim()
        if (original.isEmpty()) return emptyList()
        if (preference.mode == Mode.OFFICIAL || !mainlandChina) {
            return listOf(original)
        }
        return candidateUrls(original, preference, DOWNLOAD_FAILOVER_KEYS)
    }

    private fun candidateUrls(
        officialUrl: String,
        preference: Preference,
        failoverKeys: List<String>,
    ): List<String> {
        val original = officialUrl.trim()
        if (original.isEmpty()) return emptyList()
        if (preference.mode == Mode.OFFICIAL) return listOf(original)

        val selected = normalizeMirrorBase(preference.mirrorBase)
        val out = LinkedHashSet<String>()
        out += rewrite(original, selected)
        for (key in failoverKeys) {
            val base = "https://$key/"
            if (base.equals(selected, ignoreCase = true)) continue
            out += rewrite(original, base)
        }
        out += original
        return out.toList()
    }

    fun isGithubFamilyUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.startsWith("https://github.com/") ||
            lower.startsWith("http://github.com/") ||
            lower.startsWith("https://api.github.com/") ||
            lower.startsWith("http://api.github.com/") ||
            lower.startsWith("https://objects.githubusercontent.com/") ||
            lower.startsWith("https://release-assets.githubusercontent.com/")
    }

    private fun hostOf(url: String): String? {
        return try {
            val uri = java.net.URI(url)
            uri.host?.lowercase()?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }
}
