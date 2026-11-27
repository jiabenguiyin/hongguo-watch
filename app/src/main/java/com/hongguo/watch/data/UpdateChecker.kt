package com.hongguo.watch.data

import com.hongguo.watch.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 从 GitHub Releases 拉取最新版并与本地 versionName / versionCode 比较。
 * 按设备 ABI 优先挑选 APK 资源，供本地下载安装。
 */
object UpdateChecker {
    private const val LATEST_API =
        "https://api.github.com/repos/jiabenguiyin/hongguo-watch/releases/latest"
    private const val RELEASES_PAGE =
        "https://github.com/jiabenguiyin/hongguo-watch/releases/latest"

    data class Result(
        val tagName: String,
        val versionName: String,
        val releaseName: String,
        val body: String,
        val htmlUrl: String,
        val apkUrl: String?,
        val apkName: String?,
        val apkSize: Long,
        val isNewer: Boolean,
    )

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun check(
        preferredAbi: String = preferredAbi(),
        apiUrls: List<String> = listOf(LATEST_API),
    ): Result {
        val urls = apiUrls.map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf(LATEST_API) }
        var lastError: Exception? = null
        for (apiUrl in urls) {
            try {
                return checkOnce(apiUrl, preferredAbi)
            } catch (error: Exception) {
                lastError = error
            }
        }
        throw lastError ?: IllegalStateException("检查更新失败")
    }

    private fun checkOnce(apiUrl: String, preferredAbi: String): Result {
        val request = Request.Builder()
            .url(apiUrl)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "HongguoWatch/${BuildConfig.VERSION_NAME}")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("检查更新失败（HTTP ${response.code}）")
            }
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) throw IllegalStateException("检查更新返回空响应")
            val root = JSONObject(body)
            val tag = root.optString("tag_name").trim()
            if (tag.isBlank()) throw IllegalStateException("未找到发行版本")
            val versionName = normalizeVersionName(tag)
            val picked = pickApkAsset(parseApkAssets(root.optJSONArray("assets")), preferredAbi)
            val htmlUrl = root.optString("html_url").ifBlank { RELEASES_PAGE }
            val releaseName = root.optString("name").ifBlank { tag }
            val notes = root.optString("body").trim()
            val newer = isNewer(
                remoteName = versionName,
                localName = BuildConfig.VERSION_NAME,
                localCode = BuildConfig.VERSION_CODE,
            )
            return Result(
                tagName = tag,
                versionName = versionName,
                releaseName = releaseName,
                body = notes,
                htmlUrl = htmlUrl,
                apkUrl = picked?.url,
                apkName = picked?.name,
                apkSize = picked?.size ?: 0L,
                isNewer = newer,
            )
        }
    }

    fun latestApiUrl(): String = LATEST_API

    fun releasesPageUrl(): String = RELEASES_PAGE

    data class ApkAsset(
        val name: String,
        val url: String,
        val size: Long,
    )

    fun parseApkAssets(assets: org.json.JSONArray?): List<ApkAsset> {
        if (assets == null) return emptyList()
        val out = ArrayList<ApkAsset>(assets.length())
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url")
            if (!name.endsWith(".apk", ignoreCase = true) || url.isBlank()) continue
            out.add(ApkAsset(name = name, url = url, size = asset.optLong("size", 0L)))
        }
        return out
    }

    /**
     * 优先匹配设备 ABI；其次 universal；否则取第一个 .apk。
     */
    fun pickApkAsset(assets: List<ApkAsset>, preferredAbi: String): ApkAsset? {
        if (assets.isEmpty()) return null
        var abiMatch: ApkAsset? = null
        var universal: ApkAsset? = null
        for (candidate in assets) {
            if (preferredAbi.isNotBlank() &&
                candidate.name.contains(preferredAbi, ignoreCase = true)
            ) {
                abiMatch = candidate
                break
            }
            if (candidate.name.contains("universal", ignoreCase = true) && universal == null) {
                universal = candidate
            }
        }
        return abiMatch ?: universal ?: assets.first()
    }

    /** 去掉前导 v，保留主版本号段。 */
    fun normalizeVersionName(tagOrName: String): String {
        var s = tagOrName.trim()
        if (s.startsWith("v", ignoreCase = true)) s = s.substring(1)
        // 取到空格 / 括号前
        s = s.split(' ', limit = 2).first().trim()
        return s
    }

    /**
     * 先比 versionName（semver 数字段），无法解析时退回字符串；
     * 若远程名等于本地名则视为非更新（避免同版本误报）。
     */
    fun isNewer(remoteName: String, localName: String, localCode: Int): Boolean {
        val remote = parseVersionParts(remoteName)
        val local = parseVersionParts(localName)
        if (remote != null && local != null) {
            val cmp = compareParts(remote, local)
            if (cmp != 0) return cmp > 0
            // 同名：不提示（本仓 release 不单独带 versionCode）
            return false
        }
        if (remoteName == localName) return false
        // 无法解析时保守：仅当远程名不同且本地 code 偏低时提示打开页面
        return remoteName.isNotBlank() && remoteName != localName && localCode > 0
    }

    private fun parseVersionParts(name: String): IntArray? {
        val core = normalizeVersionName(name).substringBefore('-').substringBefore('+')
        val bits = core.split('.')
        if (bits.isEmpty() || bits.any { it.toIntOrNull() == null }) return null
        return IntArray(bits.size) { bits[it].toInt() }
    }

    private fun compareParts(a: IntArray, b: IntArray): Int {
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val av = a.getOrElse(i) { 0 }
            val bv = b.getOrElse(i) { 0 }
            if (av != bv) return av.compareTo(bv)
        }
        return 0
    }

    fun preferredAbi(): String {
        val abis = android.os.Build.SUPPORTED_ABIS
        return when {
            abis.any { it.contains("arm64", ignoreCase = true) } -> "arm64-v8a"
            abis.any { it.contains("armeabi", ignoreCase = true) } -> "armeabi-v7a"
            abis.any { it.contains("x86_64", ignoreCase = true) } -> "x86_64"
            else -> abis.firstOrNull().orEmpty()
        }
    }
}
