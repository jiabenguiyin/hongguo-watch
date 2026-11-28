package com.hongguo.watch.data

/**
 * GitHub Releases 检查 / APK 下载镜像策略。
 *
 * 节点来自 [hiwiki 镜像目录](https://git.hiwiki.org/zh-CN/mirrors) 的 `proxyNodeKeys`，
 * 默认与目录一致：`https://ghproxy.imciel.com/` 前缀代理（`{base}{完整 GitHub URL}`）。
 * 「自动镜像」失败时依次尝试备用节点，最后回落官方 GitHub。
 *
 * APK 下载回退顺序按 2026-11-28 测速（上海 + Dot 云电脑）单独维护；
 * 发布 API 未随该表测试，仍用 [API_FAILOVER_KEYS]，勿与下载列表混改。
 */
object UpdateMirrorPolicy {
    enum class Mode {
        /** 直连 GitHub / api.github.com */
        OFFICIAL,

        /** 前缀代理；默认 ghproxy.imciel.com，可在设置中调整 */
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
    const val DEFAULT_MIRROR_KEY = "ghproxy.imciel.com"
    const val DEFAULT_MIRROR_BASE = "https://ghproxy.imciel.com/"

    val DEFAULT_NODE = Node(DEFAULT_MIRROR_KEY, DEFAULT_MIRROR_BASE)

    /**
     * hiwiki `proxyNodeKeys`（2026-11）：前缀代理主机名列表。
     * 全部内置供「调整镜像」选择；下载时仅对选中项 + 少量备用做故障转移。
     * 靠前节点优先展示双站高通过率主机（2026-11-28 测速）。
     */
    val PROXY_NODE_KEYS: List<String> = listOf(
        "ghproxy.imciel.com",
        "jiashu.1win.eu.org",
        "xsadwsd.kdns.fr",
        "gitproxy.127731.xyz",
        "ghfast.top",
        "free.cn.eu.org",
        "cdn.akaere.online",
        "gh.inkchills.cn",
        "gh.chjina.com",
        "github.ednovas.xyz",
        "down.mxw.xx.kg",
        "tvv.tw",
        "git.669966.xyz",
        "gh.my-website.ccwu.cc",
        "fastgit.cc",
        "gh.acmsz.top",
        "ghm.078465.xyz",
        "gh.meali.top",
        "gh.monlor.com",
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
     * APK 下载备用前缀（2026-11-28）：优先双站共同轮次均通过的主机，
     * 再按通过率 / 中位速度；剔除 Dot 云电脑常 0 通过或上海弱的节点
     *（如 gh-proxy.com / cdn.gh-proxy.com / gh.monlor.com / gh.ddlc.top）。
     */
    private val DOWNLOAD_FAILOVER_KEYS: List<String> = listOf(
        "jiashu.1win.eu.org",
        "xsadwsd.kdns.fr",
        "gitproxy.127731.xyz",
        "ghfast.top",
        "cdn.akaere.online",
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

    fun downloadCandidateUrls(
        preference: Preference,
        officialApkUrl: String,
    ): List<String> = candidateUrls(officialApkUrl, preference, DOWNLOAD_FAILOVER_KEYS)

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
