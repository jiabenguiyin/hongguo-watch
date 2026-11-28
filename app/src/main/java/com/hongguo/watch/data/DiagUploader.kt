package com.hongguo.watch.data

import com.hongguo.watch.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 将诊断文本传到可达的公开粘贴服务（或 BuildConfig 自定义端点）。
 * 不在仓库写入任何密钥；自定义 URL 仅通过构建环境变量注入。
 *
 * 公开通道按序尝试；任一成功即返回。全部失败时抛出简短中文说明（勿把英文原文甩给 UI）。
 */
object DiagUploader {
    data class Result(
        val url: String,
        val channel: String,
    )

    /** 公开通道全部失败时抛出，[message] 已是可直接展示的中文。 */
    class UploadFailedException(
        override val message: String = "诊断上传失败，请稍后重试",
        cause: Throwable? = null,
    ) : IllegalStateException(message, cause)

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val plain = "text/plain; charset=utf-8".toMediaType()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    fun upload(report: String): Result {
        val custom = BuildConfig.DIAG_UPLOAD_URL.trim()
        if (custom.isNotEmpty()) {
            return postRaw(custom, report, channel = "custom")
        }
        val failures = ArrayList<String>(4)
        // paste.rs 仍优先（历史可用）；其后为实测可达的后备
        val attempts: List<() -> Result> = listOf(
            { postRaw("https://paste.rs", report, channel = "paste.rs") },
            { postPasteGg(report) },
            { postDpaste(report) },
            { postUguu(report) },
            { postTmpfiles(report) },
        )
        for (attempt in attempts) {
            try {
                return attempt()
            } catch (error: Throwable) {
                failures += error.message?.take(80).orEmpty()
            }
        }
        throw UploadFailedException(
            message = "诊断上传失败，请稍后重试",
            cause = IllegalStateException(failures.filter { it.isNotBlank() }.joinToString(" | ")),
        )
    }

    private fun postRaw(url: String, body: String, channel: String): Result {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent())
            .header("Accept", "text/plain")
            .post(body.toRequestBody(plain))
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string()?.trim().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("$channel HTTP ${response.code}")
            }
            val link = extractUrl(text)
                ?: throw IllegalStateException("$channel 未返回链接")
            return Result(url = link, channel = channel)
        }
    }

    /** paste.gg JSON API → 可分享网页链接 */
    private fun postPasteGg(report: String): Result {
        val payload = JSONObject()
            .put("name", "hongguo-watch-diag")
            .put("description", "HongguoWatch diagnostic")
            .put("visibility", "unlisted")
            .put(
                "files",
                org.json.JSONArray().put(
                    JSONObject()
                        .put("name", "hongguo-watch-diag.txt")
                        .put(
                            "content",
                            JSONObject()
                                .put("format", "text")
                                .put("value", report),
                        ),
                ),
            )
        val request = Request.Builder()
            .url("https://api.paste.gg/v1/pastes")
            .header("User-Agent", userAgent())
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody(jsonMedia))
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("paste.gg HTTP ${response.code}")
            }
            val root = JSONObject(text)
            if (root.optString("status") != "success") {
                throw IllegalStateException("paste.gg 状态异常")
            }
            val id = root.optJSONObject("result")?.optString("id").orEmpty()
            if (id.isBlank()) throw IllegalStateException("paste.gg 未返回链接")
            return Result(url = "https://paste.gg/p/anonymous/$id", channel = "paste.gg")
        }
    }

    /** dpaste.com form API → 纯文本 URL */
    private fun postDpaste(report: String): Result {
        val form = okhttp3.FormBody.Builder()
            .add("content", report)
            .add("syntax", "text")
            .add("expiry_days", "7")
            .build()
        val request = Request.Builder()
            .url("https://dpaste.com/api/v2/")
            .header("User-Agent", userAgent())
            .header("Accept", "text/plain")
            .post(form)
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string()?.trim().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("dpaste HTTP ${response.code}")
            }
            val link = extractUrl(text)
                ?: throw IllegalStateException("dpaste 未返回链接")
            return Result(url = link, channel = "dpaste")
        }
    }

    /** uguu.se 临时文件上传 → 直链 */
    private fun postUguu(report: String): Result {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "files[]",
                "hongguo-watch-diag.txt",
                report.toRequestBody(plain),
            )
            .build()
        val request = Request.Builder()
            .url("https://uguu.se/upload.php")
            .header("User-Agent", userAgent())
            .post(body)
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("uguu.se HTTP ${response.code}")
            }
            val root = JSONObject(text)
            if (!root.optBoolean("success", false)) {
                throw IllegalStateException("uguu.se 上传失败")
            }
            val files = root.optJSONArray("files")
            val url = files?.optJSONObject(0)?.optString("url").orEmpty()
            if (url.isBlank() || !url.startsWith("http")) {
                throw IllegalStateException("uguu.se 未返回链接")
            }
            return Result(url = url, channel = "uguu.se")
        }
    }

    /** tmpfiles.org → 可分享页；附带 /dl/ 直链便于拉取原文 */
    private fun postTmpfiles(report: String): Result {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                "hongguo-watch-diag.txt",
                report.toRequestBody(plain),
            )
            .build()
        val request = Request.Builder()
            .url("https://tmpfiles.org/api/v1/upload")
            .header("User-Agent", userAgent())
            .post(body)
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("tmpfiles HTTP ${response.code}")
            }
            val root = JSONObject(text)
            if (root.optString("status") != "success") {
                throw IllegalStateException("tmpfiles 状态异常")
            }
            val pageUrl = root.optJSONObject("data")?.optString("url").orEmpty()
            if (pageUrl.isBlank() || !pageUrl.startsWith("http")) {
                throw IllegalStateException("tmpfiles 未返回链接")
            }
            // https://tmpfiles.org/{id}/name → https://tmpfiles.org/dl/{id}/name
            val direct = pageUrl.replace("://tmpfiles.org/", "://tmpfiles.org/dl/")
            return Result(url = direct, channel = "tmpfiles.org")
        }
    }

    private fun extractUrl(text: String): String? {
        val line = text.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("http") }
        return line?.takeWhile { !it.isWhitespace() }
    }

    private fun userAgent(): String = "HongguoWatch/${BuildConfig.VERSION_NAME}"
}
