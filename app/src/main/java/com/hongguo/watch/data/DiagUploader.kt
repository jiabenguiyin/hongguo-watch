package com.hongguo.watch.data

import com.hongguo.watch.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 将诊断文本传到可达的公开粘贴服务（或 BuildConfig 自定义端点）。
 * 不在仓库写入任何密钥；自定义 URL 仅通过构建环境变量注入。
 */
object DiagUploader {
    data class Result(
        val url: String,
        val channel: String,
    )

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val plain = "text/plain; charset=utf-8".toMediaType()

    fun upload(report: String): Result {
        val custom = BuildConfig.DIAG_UPLOAD_URL.trim()
        if (custom.isNotEmpty()) {
            return postRaw(custom, report, channel = "custom")
        }
        // paste.rs：POST body → 响应纯文本 URL
        runCatching {
            return postRaw("https://paste.rs", report, channel = "paste.rs")
        }
        // 0x0.st 后备（form 字段 file）
        return postForm0x0(report)
    }

    private fun postRaw(url: String, body: String, channel: String): Result {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "HongguoWatch/${BuildConfig.VERSION_NAME}")
            .header("Accept", "text/plain")
            .post(body.toRequestBody(plain))
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string()?.trim().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("$channel HTTP ${response.code}: ${text.take(120)}")
            }
            val link = extractUrl(text)
                ?: throw IllegalStateException("$channel 未返回链接")
            return Result(url = link, channel = channel)
        }
    }

    private fun postForm0x0(report: String): Result {
        val body = okhttp3.MultipartBody.Builder()
            .setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart(
                "file",
                "hongguo-watch-diag.txt",
                report.toRequestBody(plain),
            )
            .build()
        val request = Request.Builder()
            .url("https://0x0.st")
            .header("User-Agent", "HongguoWatch/${BuildConfig.VERSION_NAME}")
            .post(body)
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string()?.trim().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("0x0.st HTTP ${response.code}: ${text.take(120)}")
            }
            val link = extractUrl(text)
                ?: throw IllegalStateException("0x0.st 未返回链接")
            return Result(url = link, channel = "0x0.st")
        }
    }

    private fun extractUrl(text: String): String? {
        val line = text.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("http") }
        return line?.takeWhile { !it.isWhitespace() }
    }
}
