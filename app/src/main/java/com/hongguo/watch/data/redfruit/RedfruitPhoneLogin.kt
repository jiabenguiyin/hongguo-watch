package com.hongguo.watch.data.redfruit

import com.hongguo.watch.upstream.Signer
import com.hongguo.watch.upstream.VendorConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

internal data class RedfruitSmsSent(val retrySeconds: Int)

internal sealed interface RedfruitPhoneResult {
    data class Approved(val credentials: RedfruitCredentials) : RedfruitPhoneResult

    data class Mfa(val number: String, val content: String) : RedfruitPhoneResult {
        override fun toString(): String = "Mfa([redacted])"
    }

    data object Pending : RedfruitPhoneResult
    data object Expired : RedfruitPhoneResult
}

internal enum class RedfruitPhoneFailure {
    INVALID_NUMBER, INVALID_CODE, EXPIRED, WAIT, ADDITIONAL_VERIFICATION, REJECTED,
}

internal class RedfruitPhoneException(
    val kind: RedfruitPhoneFailure,
    val retrySeconds: Int? = null,
) : IOException("Hongguo phone login: ${kind.name}")

/**
 * One user-initiated Passport flow. Device identity is supplied by registration and is pinned
 * for the entire attempt. The SMS and any additional verification are completed by the user.
 */
internal class RedfruitPhoneLogin(
    client: OkHttpClient,
    private val device: suspend () -> Map<String, String>,
    private val nowMillis: () -> Long = { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) },
) {
    private val http = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .cookieJar(CookieJar.NO_COOKIES)
        .authenticator(Authenticator.NONE)
        .cache(null)
        .build()
    private val mutex = Mutex()
    private var attempt: Attempt? = null
    private var sendAfter = Long.MIN_VALUE

    suspend fun reset() = mutex.withLock { attempt = null }

    suspend fun send(number: String): RedfruitSmsSent = mutex.withLock {
        val phone = mainlandPhone(number)
        remainingSeconds(sendAfter)?.let { throw RedfruitPhoneException(RedfruitPhoneFailure.WAIT, it) }
        val registered = device()
        val identity = registeredIdentity(registered)
        val next = Attempt(phone, identity, registeredCookies(registered))
        attempt = null
        // A timed-out request can still have delivered an SMS.
        sendAfter = nowMillis() + DEFAULT_COOLDOWN_SECONDS * MILLIS_PER_SECOND
        val reply = try {
            request(
                next,
                SEND_CODE_PATH,
                phoneForm(phone).add("auto_read", "0").add("unbind_exist", "34").add("type", mix("24")).build(),
            )
        } catch (error: RedfruitPhoneException) {
            error.retrySeconds?.let { sendAfter = maxOf(sendAfter, nowMillis() + it * MILLIS_PER_SECOND) }
            throw error
        }
        if (!reply.success) {
            if (reply.retrySeconds > 0) sendAfter = maxOf(sendAfter, nowMillis() + reply.retrySeconds * MILLIS_PER_SECOND)
            throw reply.failure()
        }
        val retry = reply.retrySeconds.takeIf { it > 0 } ?: DEFAULT_COOLDOWN_SECONDS
        sendAfter = nowMillis() + retry * MILLIS_PER_SECOND
        next.expiresAt = nowMillis() + LOGIN_LIFETIME_MILLIS
        attempt = next
        RedfruitSmsSent(retry)
    }

    suspend fun verify(code: String): RedfruitPhoneResult = mutex.withLock {
        val current = activeAttempt() ?: return@withLock RedfruitPhoneResult.Expired
        val digits = code.trim()
        if (!digits.matches(CODE)) throw RedfruitPhoneException(RedfruitPhoneFailure.INVALID_CODE)
        if (current.mfa != null) throw RedfruitPhoneException(RedfruitPhoneFailure.ADDITIONAL_VERIFICATION)
        remainingSeconds(current.pauseUntil)?.let { throw RedfruitPhoneException(RedfruitPhoneFailure.WAIT, it) }
        if (current.submittedCodes.size >= MAX_CODE_SUBMISSIONS) {
            attempt = null
            return@withLock RedfruitPhoneResult.Expired
        }
        if (!current.submittedCodes.add(digits)) throw RedfruitPhoneException(RedfruitPhoneFailure.INVALID_CODE)

        val reply = smsLogin(current, digits)
        if (reply.success) return@withLock approve(current, reply)

        val mfa = reply.challenge(digits)
        if (mfa != null) {
            current.mfa = mfa
            current.expiresAt = nowMillis() + LOGIN_LIFETIME_MILLIS
            return@withLock RedfruitPhoneResult.Mfa(mfa.number, mfa.content)
        }
        if (reply.expired()) {
            attempt = null
            return@withLock RedfruitPhoneResult.Expired
        }
        throw reply.failure()
    }

    /** Called after the user sends the additional SMS requested by the provider. */
    suspend fun checkMfa(): RedfruitPhoneResult = mutex.withLock {
        val current = activeAttempt() ?: return@withLock RedfruitPhoneResult.Expired
        val mfa = current.mfa ?: throw RedfruitPhoneException(RedfruitPhoneFailure.ADDITIONAL_VERIFICATION)
        if (remainingSeconds(current.nextCheck) != null) return@withLock RedfruitPhoneResult.Pending
        current.nextCheck = nowMillis() + MIN_MFA_POLL_MILLIS
        val step = JSONObject().put("passport_mfa_retry_tag", "1").put("sms_code_key", mfa.codeKey)
        val post = FormBody.Builder()
            .add("encrypt_uid", mfa.encryptedUid)
            .add("verify_ticket", "")
            .add("verify_scene", "sms_login")
            .add("verify_reason", "ato")
            .add("copywriting_key", "sms_login")
            .add("ies_safety_diversion_tag", "mfa")
            .add("new_verify_flow", "")
            .add("new_authn_sdk_version", "1.1.31")
            .add("new_authn_sdk_verify_reason", "ato")
            .add("new_authn_sdk_verify_scene", "sms_login")
            .add("request_tag_from", "h5")
            .add("biz_params", step.toString())
            .add("new_authn_sdk_log_id", mfa.logId)
            .build()
        val reply = request(current, VERIFY_MFA_PATH, post)
        if (reply.errorCode == "1045") return@withLock RedfruitPhoneResult.Pending
        if (reply.expired()) {
            attempt = null
            return@withLock RedfruitPhoneResult.Expired
        }
        if (!reply.success) throw reply.failure()
        val data = requireNotNull(reply.data)
        val ticket = validOpaque(data.optString("ticket")) ?: validOpaque(data.optString("verify_ticket"))
            ?: throw RedfruitPhoneException(RedfruitPhoneFailure.REJECTED)

        // Pass the provider's confirmation ticket once. An uncertain result must not replay it.
        attempt = null
        val login = smsLogin(current, mfa.code, ticket)
        if (login.expired()) return@withLock RedfruitPhoneResult.Expired
        if (!login.success) throw login.failure()
        approve(current, login)
    }

    private fun activeAttempt(): Attempt? {
        val current = attempt ?: return null
        if (nowMillis() < current.expiresAt) return current
        attempt = null
        return null
    }

    private fun approve(current: Attempt, reply: Reply): RedfruitPhoneResult.Approved {
        attempt = null
        val data = reply.data
        val id = data?.optString("user_id_str").orEmpty()
        val numericId = data?.optString("user_id").orEmpty()
        val resolvedId = id.ifBlank { numericId }
        if (!reply.success || !resolvedId.matches(USER_ID) || resolvedId.all { it == '0' } ||
            (numericId.isNotEmpty() && numericId != resolvedId)
        ) {
            throw RedfruitPhoneException(RedfruitPhoneFailure.REJECTED)
        }
        val liveCookies = current.cookies.values.filter { it.expiresAt > System.currentTimeMillis() }
        val session = liveCookies.filter { it.name in ACCOUNT_COOKIES }
        if (!session.any { it.name in SESSION_COOKIES } && current.token.isEmpty()) {
            throw RedfruitPhoneException(RedfruitPhoneFailure.REJECTED)
        }
        return RedfruitPhoneResult.Approved(
            RedfruitCredentials(session.joinToString("; ") { "${it.name}=${it.value}" }, current.token),
        )
    }

    private suspend fun smsLogin(current: Attempt, code: String, ticket: String? = null): Reply {
        val body = phoneForm(current.phone).add("code", mix(code))
        if (ticket != null) {
            body.add("passport_mfa_retry_tag", "1").add("sms_code_key", mix(ticket))
        }
        return request(current, LOGIN_PATH, body.build())
    }

    private suspend fun request(current: Attempt, path: String, payload: FormBody): Reply {
        remainingSeconds(current.pauseUntil)?.let { throw RedfruitPhoneException(RedfruitPhoneFailure.WAIT, it) }
        val bytes = Buffer().apply { payload.writeTo(this) }.readByteArray()
        val signed = withContext(Dispatchers.Default) {
            current.signer.sign(PASSPORT_ORIGIN + path, current.identity, bytes, identity = current.identity)
        }
        val builder = Request.Builder().url(signed.url).post(payload)
        signed.headers.forEach { (name, value) -> builder.header(name, value) }
        builder.header("User-Agent", VendorConstants.VIDEO_UA)
            .header("Accept", "application/json")
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .header("Passport-Sdk-Version", "5051452")
            .header("X-Vc-Bdturing-Sdk-Version", "4.0.3.cn")
            .header("X-SS-DP", "8662")
            .header("Lc", "101")
        val requestUrl = builder.build().url
        val cookies = current.cookies.values.filter { it.matches(requestUrl) && it.expiresAt > System.currentTimeMillis() }
            .associate { it.name to it.value }.toMutableMap()
        cookies["install_id"] = current.identity.getValue("iid")
        builder.header("Cookie", cookies.entries.joinToString("; ") { (key, value) -> "$key=$value" })
        if (current.deviceCookieHeader.isNotBlank()) {
            builder.header(
                "Cookie",
                listOf(current.deviceCookieHeader, cookies.entries.joinToString("; ") { (key, value) -> "$key=$value" })
                    .filter(String::isNotBlank)
                    .joinToString("; "),
            )
        }
        if (current.token.isNotEmpty()) builder.header("X-Tt-Token", current.token)
        val result = fetch(builder.build())
        if (result.code == 429) {
            val wait = result.retryAfter.toRetrySeconds().takeIf { it > 0 } ?: DEFAULT_COOLDOWN_SECONDS
            current.pauseUntil = nowMillis() + wait * MILLIS_PER_SECOND
            throw RedfruitPhoneException(RedfruitPhoneFailure.WAIT, wait)
        }
        if (result.code != 200) throw RedfruitPhoneException(RedfruitPhoneFailure.REJECTED)
        for (cookie in result.cookies) {
            val key = Triple(cookie.name, cookie.domain, cookie.path)
            if (cookie.expiresAt <= System.currentTimeMillis()) {
                current.cookies.remove(key)
            } else if (cookie.value.length <= MAX_OPAQUE_LENGTH && (key in current.cookies || current.cookies.size < MAX_COOKIES)) {
                current.cookies[key] = cookie
            }
        }
        validOpaque(result.token)?.let { current.token = it }
        val payloadJson = try {
            JSONObject(result.body)
        } catch (_: JSONException) {
            throw RedfruitPhoneException(RedfruitPhoneFailure.REJECTED)
        }
        val data = payloadJson.optJSONObject("data")
        val code = data?.optString("error_code").orEmpty().ifEmpty { payloadJson.optString("error_code") }
        val retry = data?.optString("retry_time").orEmpty().toRetrySeconds()
        if (code == "1206") {
            val wait = retry.takeIf { it > 0 } ?: DEFAULT_COOLDOWN_SECONDS
            current.pauseUntil = nowMillis() + wait * MILLIS_PER_SECOND
            throw RedfruitPhoneException(RedfruitPhoneFailure.WAIT, wait)
        }
        return Reply(payloadJson.optString("message") == "success" && data != null && code in setOf("", "0"), code, data, retry)
    }

    private suspend fun fetch(request: Request): ResponseData = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWith(Result.failure(IOException("Hongguo phone login connection failed")))
            }

            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        if (it.request.url != request.url) throw RedfruitPhoneException(RedfruitPhoneFailure.REJECTED)
                        val data = if (it.code == 200) {
                            val source = it.body?.source() ?: throw RedfruitPhoneException(RedfruitPhoneFailure.REJECTED)
                            if (source.request(MAX_RESPONSE_BYTES + 1)) throw RedfruitPhoneException(RedfruitPhoneFailure.REJECTED)
                            source.readUtf8()
                        } else {
                            ""
                        }
                        ResponseData(
                            it.code, it.header("Retry-After").orEmpty(), data,
                            Cookie.parseAll(request.url, it.headers), it.header("X-Tt-Token").orEmpty(),
                        )
                    }
                }
                continuation.resumeWith(result)
            }
        })
    }

    private fun remainingSeconds(deadline: Long): Int? {
        val now = nowMillis()
        if (deadline <= now) return null
        return ((deadline - now + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND).toInt()
    }

    private data class ResponseData(
        val code: Int,
        val retryAfter: String,
        val body: String,
        val cookies: List<Cookie>,
        val token: String,
    ) {
        override fun toString(): String = "ResponseData([redacted])"
    }

    private class Attempt(
        val phone: String,
        val identity: Map<String, String>,
        val deviceCookieHeader: String,
    ) {
        val signer = Signer(device = identity)
        val cookies = linkedMapOf<Triple<String, String, String>, Cookie>()
        val submittedCodes = mutableSetOf<String>()
        var token = ""
        var expiresAt = Long.MIN_VALUE
        var nextCheck = Long.MIN_VALUE
        var pauseUntil = Long.MIN_VALUE
        var mfa: Challenge? = null
    }

    private class Challenge(
        val encryptedUid: String,
        val codeKey: String,
        val logId: String,
        val number: String,
        val content: String,
        val code: String,
    )

    private class Reply(val success: Boolean, val errorCode: String, val data: JSONObject?, val retrySeconds: Int) {
        fun failure(): RedfruitPhoneException = RedfruitPhoneException(
            when {
                expired() -> RedfruitPhoneFailure.EXPIRED
                errorCode == "1102" -> RedfruitPhoneFailure.INVALID_CODE
                errorCode in setOf("1105", "2046") || data?.has("verify_center_decision_conf") == true ||
                    data?.has("captcha") == true -> RedfruitPhoneFailure.ADDITIONAL_VERIFICATION
                else -> RedfruitPhoneFailure.REJECTED
            },
        )

        fun expired(): Boolean {
            val description = data?.optString("description").orEmpty()
            return errorCode == "1203" ||
                listOf("验证已过期", "验证过期", "验证已失效", "长时间未操作").any(description::contains)
        }

        fun challenge(code: String): Challenge? {
            if (data == null) return null
            val uid = validOpaque(data.optString("encrypt_uid")) ?: return null
            val key = validOpaque(data.optString("sms_code_key")) ?: return null
            val log = validOpaque(data.optJSONObject("event_params")?.optString("log_id").orEmpty()).orEmpty()
            val ways = data.optJSONArray("verify_ways") ?: return null
            for (i in 0 until minOf(ways.length(), 8)) {
                val way = ways.optJSONObject(i) ?: continue
                if (way.optString("verify_way") != "upsms") continue
                val number = way.optString("channel_mobile")
                val content = way.optString("sms_content")
                if (number.matches(SMS_RECIPIENT) && content.length in 1..MAX_SMS_CHALLENGE_LENGTH &&
                    content.none { it.isISOControl() }
                ) {
                    return Challenge(uid, key, log, number, content, code)
                }
            }
            return null
        }
    }

    private companion object {
        const val PASSPORT_ORIGIN = "https://api5-normal-lq.fqnovel.com"
        const val SEND_CODE_PATH = "/passport/mobile/send_code/v1/"
        const val LOGIN_PATH = "/passport/mobile/sms_login/"
        const val VERIFY_MFA_PATH = "/passport/upsms/verify/"
        const val MAX_RESPONSE_BYTES = 256L * 1024L
        const val MAX_COOKIES = 32
        const val MAX_OPAQUE_LENGTH = 8192
        const val MAX_SMS_CHALLENGE_LENGTH = 512
        const val MAX_CODE_SUBMISSIONS = 5
        const val DEFAULT_COOLDOWN_SECONDS = 60
        const val MILLIS_PER_SECOND = 1000L
        const val LOGIN_LIFETIME_MILLIS = 10 * 60 * MILLIS_PER_SECOND
        const val MIN_MFA_POLL_MILLIS = 3000L
        val PHONE = Regex("1[3-9][0-9]{9}")
        val CODE = Regex("[0-9]{4,8}")
        val USER_ID = Regex("[0-9]{1,30}")
        val DEVICE_ID = Regex("[1-9][0-9]{8,24}")
        val DEVICE_EXTRA = Regex("[a-fA-F0-9-]{8,64}")
        val SMS_RECIPIENT = Regex("\\+?[0-9]{3,20}")
        val SESSION_COOKIES = setOf("sessionid", "sessionid_ss", "tt_sessionid")
        val ACCOUNT_COOKIES = SESSION_COOKIES + setOf(
            "sid_tt", "uid_tt", "uid_tt_ss", "sid_guard", "sid_ucp_v1", "ssid_ucp_v1",
            "passport_auth_status", "passport_auth_status_ss",
        )
        val HEX = "0123456789abcdef"

        fun mainlandPhone(number: String): String {
            val local = number.trim().removePrefix("+86").trim()
            if (!local.matches(PHONE)) throw RedfruitPhoneException(RedfruitPhoneFailure.INVALID_NUMBER)
            return "+86$local"
        }

        fun registeredIdentity(source: Map<String, String>): Map<String, String> {
            val deviceId = source["device_id"].orEmpty()
            val installId = source["iid"].orEmpty()
            if (!deviceId.matches(DEVICE_ID) || !installId.matches(DEVICE_ID) ||
                deviceId == VendorConstants.device["device_id"] || installId == VendorConstants.device["iid"]
            ) {
                throw RedfruitPhoneException(RedfruitPhoneFailure.REJECTED)
            }
            return VendorConstants.device + mapOf(
                "device_id" to deviceId,
                "iid" to installId,
                "version_code" to VendorConstants.OFFICIAL_VERSION_CODE,
                "manifest_version_code" to VendorConstants.OFFICIAL_VERSION_CODE,
                "update_version_code" to VendorConstants.OFFICIAL_VERSION_CODE,
                "version_name" to VendorConstants.OFFICIAL_VERSION_NAME,
            ) + source.filter { (key, value) -> key in setOf("cdid", "openudid") && value.matches(DEVICE_EXTRA) }
        }

        fun registeredCookies(source: Map<String, String>): String = source.entries
            .filter { it.key.startsWith("cookie_") }
            .filter { it.value.length in 1..4_096 && it.value.all { char -> char in ' '..'~' } }
            .joinToString("; ") { (key, value) -> "${key.removePrefix("cookie_")}=$value" }

        fun phoneForm(phone: String): FormBody.Builder = FormBody.Builder()
            .add("mobile", mix(phone))
            .add("account_sdk_source", "app")
            .add("passport_support_flow", "captcha,verify")
            .add("mix_mode", "1")

        fun mix(value: String): String = buildString {
            for (byte in value.encodeToByteArray()) {
                val n = (byte.toInt() and 0xff) xor 5
                append(HEX[n ushr 4])
                append(HEX[n and 15])
            }
        }

        fun validOpaque(value: String): String? = value.takeIf {
            it.length in 1..MAX_OPAQUE_LENGTH && it.all { character -> character in '!'..'~' }
        }

        fun String.toRetrySeconds(): Int = (toLongOrNull() ?: 0L).coerceIn(0, 86_400L).toInt()
    }
}
