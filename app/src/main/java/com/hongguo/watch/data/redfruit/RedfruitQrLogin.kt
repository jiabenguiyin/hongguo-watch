package com.hongguo.watch.data.redfruit

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.ByteString.Companion.decodeBase64
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal class RedfruitQrTicket(val image: ByteArray, val expiresIn: Int)

internal sealed interface RedfruitQrResult {
    data class Pending(val scanned: Boolean) : RedfruitQrResult
    data object Expired : RedfruitQrResult
    data class Approved(val credentials: RedfruitCredentials) : RedfruitQrResult
}

/**
 * An isolated Redfruit website QR session. The caller owns its polling coroutine and must
 * cancel it and clear the session when changing login methods.
 *
 * Protocol reference: aston314/hongguo-tv v0.2.177, account_scan.go.
 */
internal class RedfruitQrLogin(client: OkHttpClient) {
    private val transport = client.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .cache(null)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()
    private val attempt = AtomicReference<Attempt?>()

    suspend fun begin(): RedfruitQrTicket {
        val current = Attempt()
        attempt.set(current)
        try {
            val payload = passport(current, "/passport/web/get_qrcode/", "next" to SITE.toString())
            val data = successData(payload)
            val token = data.optString("token")
            if (!validCredential(token, MAX_TOKEN_BYTES)) throw IOException("Invalid Redfruit QR token")
            val image = qrImage(data.optString("qrcode"))
            val seconds = expiresIn(data)
            current.deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds.toLong())
            current.token = token
            requireCurrent(current)
            return RedfruitQrTicket(image, seconds)
        } catch (error: Exception) {
            attempt.compareAndSet(current, null)
            throw error
        }
    }

    suspend fun poll(): RedfruitQrResult {
        val current = attempt.get() ?: return RedfruitQrResult.Expired
        if (current.token.isEmpty()) throw IOException("Redfruit QR is not ready")
        if (System.nanoTime() - current.deadline >= 0) return expire(current)
        val payload = passport(
            current, "/passport/web/check_qrconnect/", "next" to SITE.toString(), "token" to current.token,
        )
        if (serviceCode(payload) == "2035") return expire(current)
        val data = successData(payload)
        return when (data.optString("status")) {
            "", "1", "new", "pending" -> RedfruitQrResult.Pending(scanned = false)
            "2", "scanned" -> RedfruitQrResult.Pending(scanned = true)
            "4", "5", "cancelled", "expired" -> expire(current)
            "3", "confirmed" -> confirm(current, data.optString("redirect_url"))
            else -> throw IOException("Unrecognized Redfruit QR status")
        }
    }

    fun clear() {
        attempt.set(null)
    }

    private fun expire(current: Attempt): RedfruitQrResult.Expired {
        attempt.compareAndSet(current, null)
        return RedfruitQrResult.Expired
    }

    private suspend fun confirm(current: Attempt, redirect: String): RedfruitQrResult.Approved {
        try {
            if (redirect.isNotBlank()) followConfirmation(current, resolve(SITE, redirect))
            if (!current.hasSession()) throw IOException("Redfruit did not issue a login session")
            val profile = successData(passport(current, "/passport/account/info/v2/"))
            val textId = profile.optString("user_id_str")
            val numericId = profile.optString("user_id")
            val id = textId.ifEmpty { numericId }
            if (!id.matches(UID) || id.all { it == '0' } ||
                (numericId.isNotEmpty() && numericId != id)
            ) {
                throw IOException("Redfruit did not verify the account")
            }
            val cookie = current.sessionCookie()
            if (cookie.length > MAX_COOKIE_HEADER) throw IOException("Redfruit session is too large")
            requireCurrent(current)
            return RedfruitQrResult.Approved(RedfruitCredentials(cookie = cookie, token = current.accountToken))
        } finally {
            attempt.compareAndSet(current, null)
        }
    }

    private suspend fun followConfirmation(current: Attempt, initial: HttpUrl) {
        var url = initial
        repeat(5) {
            val next = get(current, url) { response ->
                when (response.code) {
                    200, 204 -> null
                    301, 302, 303, 307, 308 -> resolve(url, response.header("Location").orEmpty())
                    else -> throw IOException("Redfruit QR confirmation failed")
                }
            }
            if (next == null) return
            url = next
        }
        throw IOException("Too many Redfruit login redirects")
    }

    private suspend fun passport(current: Attempt, path: String, vararg params: Pair<String, String>): JSONObject {
        val url = requireNotNull(SITE.resolve(path)).newBuilder().apply {
            addQueryParameter("aid", "513755")
            addQueryParameter("passport_jssdk_version", "3.4.10")
            addQueryParameter("passport_jssdk_type", "normal")
            addQueryParameter("account_sdk_source", "web")
            addQueryParameter("is_from_ttaccountsdk", "1")
            addQueryParameter("language", "zh")
            for ((name, value) in params) addQueryParameter(name, value)
        }.build()
        return get(current, url) { response ->
            if (!response.isSuccessful) throw IOException("Redfruit QR request failed (HTTP ${response.code})")
            val source = response.body?.source() ?: throw IOException("Empty Redfruit QR response")
            if (source.request(MAX_RESPONSE_BYTES + 1L)) throw IOException("Redfruit QR response is too large")
            try {
                JSONObject(source.readUtf8())
            } catch (_: JSONException) {
                throw IOException("Invalid Redfruit QR response")
            }
        }
    }

    private suspend fun <T> get(current: Attempt, url: HttpUrl, consume: (Response) -> T): T {
        requireCurrent(current)
        if (!isOfficial(url)) throw IOException("Invalid Redfruit login origin")
        val request = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", SITE.toString())
            .header("Origin", "https://${SITE.host}")
            .header("Accept", "application/json, text/javascript")
            .apply {
                val cookies = current.cookiesFor(url)
                if (cookies.isNotEmpty()) header("Cookie", cookies.toCookieHeader())
                cookies.firstOrNull { it.name == "passport_csrf_token" }
                    ?.let { header("x-tt-passport-csrf-token", it.value) }
            }.build()
        return suspendCancellableCoroutine { continuation ->
            val call = transport.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWith(Result.failure(IOException("Redfruit QR connection failed")))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            requireCurrent(current)
                            if (it.request.url != url) throw IOException("Invalid Redfruit response origin")
                            current.acceptCookies(it)
                            consume(it)
                        }
                    }
                    continuation.resumeWith(result)
                }
            })
        }
    }

    private fun requireCurrent(current: Attempt) {
        if (attempt.get() !== current) throw IOException("Redfruit QR login was cancelled")
    }

    private fun resolve(from: HttpUrl, location: String): HttpUrl {
        if (location.isBlank() || location.length > MAX_TOKEN_BYTES) throw IOException("Invalid Redfruit login redirect")
        return from.resolve(location)?.takeIf(::isOfficial) ?: throw IOException("Invalid Redfruit login redirect")
    }

    private fun isOfficial(url: HttpUrl): Boolean = url.isHttps && url.host == SITE.host &&
        url.port == SITE.port && url.encodedUsername.isEmpty() && url.encodedPassword.isEmpty() &&
        url.fragment == null

    private fun serviceCode(payload: JSONObject): String =
        payload.optJSONObject("data")?.optString("error_code")?.takeIf { it.isNotEmpty() && it != "0" }
            ?: payload.optString("code").ifBlank { payload.optString("error_code") }

    private fun successData(payload: JSONObject): JSONObject {
        val code = serviceCode(payload)
        if (payload.optString("message") != "success" || (code.isNotEmpty() && code != "0")) {
            throw IOException("Redfruit QR request was rejected")
        }
        return payload.optJSONObject("data") ?: throw IOException("Invalid Redfruit QR data")
    }

    private fun expiresIn(data: JSONObject): Int {
        val raw = data.optString("expire_time")
        val value = raw.toLongOrNull() ?: if (raw.isBlank()) 180L else throw IOException("Invalid Redfruit QR expiry")
        val now = System.currentTimeMillis() / 1_000L
        val remaining = when {
            value > 1_000_000_000_000L -> value / 1_000L - now
            value > 1_000_000_000L -> value - now
            else -> value
        }
        return remaining.coerceIn(0, 600).toInt()
    }

    private fun qrImage(value: String): ByteArray {
        val raw = value.trim()
        val encoded = when {
            raw.startsWith("[image omitted]") -> raw.removePrefix("[image omitted]")
            raw.contains("base64,", ignoreCase = true) -> raw.substringAfter("base64,", "")
            else -> raw
        }
        if (encoded.length > MAX_IMAGE_BASE64) throw IOException("Redfruit QR image is too large")
        val bytes = encoded.decodeBase64()?.toByteArray() ?: throw IOException("Invalid Redfruit QR image")
        if (bytes.size < 33 || !bytes.copyOfRange(0, PNG.size).contentEquals(PNG)) {
            throw IOException("Invalid Redfruit QR image format")
        }
        val header = ByteBuffer.wrap(bytes, 8, 16)
        if (header.int != 13 || header.int != 0x49484452 || header.int !in 32..2048 || header.int !in 32..2048) {
            throw IOException("Invalid Redfruit QR image dimensions")
        }
        return bytes
    }

    private class Attempt {
        @Volatile var token = ""
        var deadline = 0L
        var accountToken = ""
            private set
        private val cookies = mutableMapOf<Pair<String, String>, Cookie>()

        @Synchronized
        fun acceptCookies(response: Response) {
            val now = System.currentTimeMillis()
            cookies.entries.removeAll { it.value.expiresAt <= now }
            for (cookie in Cookie.parseAll(response.request.url, response.headers)) {
                if (cookie.domain.trimStart('.') != SITE.host) continue
                val key = cookie.name to cookie.path
                cookies.remove(key)
                if (validCredential(cookie.value, MAX_TOKEN_BYTES) && cookie.expiresAt > now && cookies.size < 64) {
                    cookies[key] = cookie
                }
            }
            val token = response.header("x-tt-token")
            if (token != null && validCredential(token, MAX_TOKEN_BYTES) && hasSession()) accountToken = token
        }

        @Synchronized
        fun cookiesFor(url: HttpUrl): List<Cookie> = cookies.values
            .filter { it.matches(url) && it.expiresAt > System.currentTimeMillis() }
            .sortedByDescending { it.path.length }

        fun hasSession(): Boolean = cookiesFor(SITE).any { it.name == "sessionid" || it.name == "sessionid_ss" }

        fun sessionCookie(): String = cookiesFor(SITE).filter { it.name in AUTH_COOKIES }.toCookieHeader()
    }

    private companion object {
        val SITE = "https://hongguoduanju.com/".toHttpUrl()
        val UID = Regex("[0-9]{1,30}")
        val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        val AUTH_COOKIES = setOf(
            "sessionid", "sessionid_ss", "sid_tt", "sid_guard", "uid_tt", "uid_tt_ss",
            "tt_sessionid", "sid_ucp_v1", "ssid_ucp_v1", "passport_auth_status", "passport_auth_status_ss",
        )
        const val MAX_IMAGE_BASE64 = 1024 * 1024
        const val MAX_RESPONSE_BYTES = 2L * 1024 * 1024
        const val MAX_TOKEN_BYTES = 8192
        const val MAX_COOKIE_HEADER = 16 * 1024
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        fun validCredential(value: String, limit: Int): Boolean =
            value.length in 1..limit && value.all { it in '!'..'~' && it != ';' }

        fun List<Cookie>.toCookieHeader(): String = joinToString("; ") { "${it.name}=${it.value}" }
    }
}
