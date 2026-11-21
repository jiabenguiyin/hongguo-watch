package com.hongguo.watch.data.sync

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

internal data class PairingTicket(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val verificationUriBase: String,
    val expiresIn: Int,
    val interval: Int,
) {
    override fun toString(): String = "PairingTicket(redacted)"
}

internal sealed interface PairingResult {
    data object Pending : PairingResult
    data object Expired : PairingResult
    data class Approved(val session: SyncSession) : PairingResult
}

internal data class SyncSession(
    val origin: String,
    val token: String,
    val accountId: String,
    val accountName: String,
) {
    override fun toString(): String = "SyncSession(redacted)"
}

internal class SyncUnauthorizedException : IOException("Account session expired")

internal class SyncServiceException(message: String = "Invalid account sync response") : IOException(message)

internal class SyncTransport(
    configuredUrl: String,
    client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(22, TimeUnit.SECONDS)
        .build(),
) {
    private val baseUrl = parseOrigin(configuredUrl)
    val origin: String? = baseUrl?.toString()
    val configured: Boolean = baseUrl != null

    private val client = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    suspend fun beginPairing(): PairingTicket {
        val base = requireOrigin()
        val request = post(base, "/v1/pair/start", JSONObject())
        return send(request) { response ->
            val json = response.requireJson()
            val userCode = json.requireString("userCode", USER_CODE)
            val deviceCode = json.requireString("deviceCode", SECRET)
            val verificationUri = verificationUrl(base, json.optString("verificationUri"), userCode)
            val verificationUriBase = verificationUrl(base, json.optString("verificationUriBase"))
            PairingTicket(
                deviceCode,
                userCode,
                verificationUri.toString(),
                verificationUriBase.toString(),
                json.requireSeconds("expiresIn", 1..900),
                json.requireSeconds("interval", 3..60),
            )
        }
    }

    suspend fun poll(ticket: PairingTicket): PairingResult {
        val base = requireOrigin()
        verificationUrl(base, ticket.verificationUri, ticket.userCode)
        if (!SECRET.matches(ticket.deviceCode)) throw SyncServiceException()
        val request = post(base, "/v1/pair/poll", JSONObject().put("deviceCode", ticket.deviceCode))
        return send(request) { response ->
            if (response.code == 429) return@send PairingResult.Pending
            val json = response.requireJson()
            when (json.optString("status")) {
                "pending" -> PairingResult.Pending
                "expired" -> PairingResult.Expired
                "approved" -> PairingResult.Approved(
                    SyncSession(
                        base.toString(),
                        json.requireString("token", SECRET),
                        json.requireString("accountId", ACCOUNT_ID),
                        json.requireString("accountName", ACCOUNT_NAME),
                    ),
                )
                else -> throw SyncServiceException()
            }
        }
    }

    suspend fun revoke(session: SyncSession) {
        val request = post(requireOrigin(), "/v1/session/revoke", JSONObject(), session)
        send(request) { response -> response.requireSuccess(authenticated = true) }
    }

    suspend fun exchange(session: SyncSession, payload: JSONObject): JSONObject {
        val request = post(requireOrigin(), "/v1/library/sync", payload, session)
        return send(request) { response -> response.requireJson(authenticated = true) }
    }

    private fun requireOrigin(): HttpUrl = baseUrl ?: throw SyncServiceException("Account sync is unavailable")

    private fun post(base: HttpUrl, path: String, payload: JSONObject, session: SyncSession? = null): Request {
        val body = payload.toString().encodeToByteArray()
        if (body.size > MAX_REQUEST_SIZE) throw SyncServiceException("Account sync request exceeds size limit")
        val request = Request.Builder()
            .url(checkNotNull(base.resolve(path)))
            .header("Accept", "application/json")
            .post(body.toRequestBody(JSON_TYPE))
        if (session != null) {
            if (session.origin != base.toString() || !SECRET.matches(session.token)) {
                throw SyncUnauthorizedException()
            }
            request.header("Authorization", "Bearer ${session.token}")
        }
        return request.build()
    }

    private suspend fun <T> send(request: Request, read: (Response) -> T): T =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWith(Result.failure(IOException("Account sync connection failed")))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            if (it.request.url != request.url) throw SyncServiceException()
                            read(it)
                        }
                    }
                    continuation.resumeWith(result)
                }
            })
        }

    private fun Response.requireSuccess(authenticated: Boolean) {
        if (authenticated && code == 401) throw SyncUnauthorizedException()
        if (!isSuccessful) throw SyncServiceException("Account sync request failed (HTTP $code)")
    }

    private fun Response.requireJson(authenticated: Boolean = false): JSONObject {
        requireSuccess(authenticated)
        val responseBody = body ?: throw SyncServiceException()
        val source = responseBody.source()
        if (responseBody.contentLength() > MAX_RESPONSE_SIZE || source.request(MAX_RESPONSE_SIZE + 1)) {
            throw SyncServiceException("Account sync response exceeds size limit")
        }
        val raw = source.readUtf8()
        return try {
            JSONObject(raw)
        } catch (_: JSONException) {
            throw SyncServiceException()
        }
    }

    private fun verificationUrl(base: HttpUrl, value: String, userCode: String? = null): HttpUrl {
        val url = value.toHttpUrlOrNull() ?: throw SyncServiceException()
        if (value.length > 512 || !value.startsWith("https://", ignoreCase = true) ||
            value.substringAfter("://").substringBefore('/').contains('@') ||
            url.scheme != base.scheme || url.host != base.host || url.port != base.port ||
            url.encodedPath != "/pair" || url.fragment != null
        ) {
            throw SyncServiceException()
        }
        if (userCode == null) {
            if (url.encodedQuery != null) throw SyncServiceException()
        } else if (url.querySize != 1 || url.queryParameter("user_code") != userCode) {
            throw SyncServiceException()
        }
        return url
    }

    private fun JSONObject.requireString(name: String, pattern: Regex): String {
        val value = opt(name) as? String ?: throw SyncServiceException()
        if (!pattern.matches(value)) throw SyncServiceException()
        return value
    }

    private fun JSONObject.requireSeconds(name: String, allowed: IntRange): Int {
        val seconds = opt(name) as? Int ?: throw SyncServiceException()
        if (seconds !in allowed) throw SyncServiceException()
        return seconds
    }

    private companion object {
        const val MAX_REQUEST_SIZE = 512 * 1024
        const val MAX_RESPONSE_SIZE = 8L * 1024 * 1024
        val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
        val ORIGIN = Regex("""(?i)https://[^/@?#\\\s]+/?""")
        val USER_CODE = Regex("[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{4}-[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{4}")
        val SECRET = Regex("[A-Za-z0-9_-]{43}")
        val ACCOUNT_ID = Regex("[A-Za-z0-9_-]{22}")
        val ACCOUNT_NAME = Regex("[a-z0-9_.-]{3,32}")

        fun parseOrigin(value: String): HttpUrl? {
            val candidate = value.trim()
            if (!ORIGIN.matches(candidate)) return null
            return candidate.toHttpUrlOrNull()?.takeIf {
                it.isHttps && it.encodedPath == "/" && it.encodedQuery == null && it.fragment == null &&
                    it.encodedUsername.isEmpty() && it.encodedPassword.isEmpty()
            }
        }
    }
}
