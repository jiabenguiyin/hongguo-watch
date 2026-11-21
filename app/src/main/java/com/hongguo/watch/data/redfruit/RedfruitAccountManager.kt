package com.hongguo.watch.data.redfruit

import android.content.Context
import android.os.SystemClock
import com.hongguo.watch.data.db.FavoriteEntity
import com.hongguo.watch.data.db.WatchHistoryEntity
import com.hongguo.watch.data.sync.EncryptedSessionFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

internal enum class RedfruitLoginMode { CHOICE, QR, PHONE }
internal enum class RedfruitAccountError { NETWORK, SERVICE, QR_EXPIRED, PHONE_NUMBER, PHONE_CODE, PHONE_EXPIRED, PHONE_WAIT, VERIFY_REQUIRED, CLOUD_SESSION, STORAGE }

internal data class RedfruitAccountState(
    val restoring: Boolean = true,
    val mode: RedfruitLoginMode = RedfruitLoginMode.CHOICE,
    val busy: Boolean = false,
    val qrImage: ByteArray? = null,
    val qrScanned: Boolean = false,
    val smsRequested: Boolean = false,
    val smsRetryAt: Long = 0,
    val mfa: RedfruitPhoneResult.Mfa? = null,
    val accountName: String? = null,
    val error: RedfruitAccountError? = null,
    val history: List<WatchHistoryEntity>? = null,
    val favorites: List<FavoriteEntity>? = null,
    val historyLoading: Boolean = false,
    val favoritesLoading: Boolean = false,
    val historyFailed: Boolean = false,
    val favoritesFailed: Boolean = false,
)

internal class RedfruitAccountManager(context: Context) {
    private val transport = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()
    private val device = RedfruitDevice(context, transport)
    private val cloud = RedfruitCloudLibrary(transport, device::identity)
    private val qr = RedfruitQrLogin(transport)
    private val phone = RedfruitPhoneLogin(transport, device::identity)
    private val sessionFile = EncryptedSessionFile(context, "redfruit-session", "hongguo_watch_redfruit_session")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableStatus = MutableStateFlow(RedfruitAccountState())
    val status = mutableStatus.asStateFlow()
    private var session: RedfruitCredentials? = null
    private var profile: RedfruitCloudProfile? = null
    private var request: Job? = null
    private var generation = 0

    init {
        scope.launch {
            val restored = try {
                withContext(Dispatchers.IO) { sessionFile.read()?.let { StoredRedfruitSession.read(it) } }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                showError(RedfruitAccountError.STORAGE)
                null
            }
            session = restored?.credentials
            profile = restored?.profile
            mutableStatus.update { it.copy(restoring = false, accountName = restored?.profile?.name) }
            refresh()
        }
    }

    fun startQr() {
        if (status.value.restoring || session != null || status.value.busy) return
        mutableStatus.update { it.copy(mode = RedfruitLoginMode.QR, qrImage = null, qrScanned = false) }
        perform {
            phone.reset()
            try {
                val ticket = qr.begin()
                mutableStatus.update { it.copy(qrImage = ticket.image) }
                val deadline = SystemClock.elapsedRealtime() + ticket.expiresIn * 1000L
                while (SystemClock.elapsedRealtime() < deadline) {
                    delay(2500L)
                    if (SystemClock.elapsedRealtime() >= deadline) break
                    when (val result = qr.poll()) {
                        is RedfruitQrResult.Pending -> mutableStatus.update { it.copy(qrScanned = result.scanned) }
                        RedfruitQrResult.Expired -> break
                        is RedfruitQrResult.Approved -> {
                            mutableStatus.update { it.copy(qrImage = null) }
                            finishLogin(result.credentials)
                            return@perform
                        }
                    }
                }
                showError(RedfruitAccountError.QR_EXPIRED)
            } finally {
                qr.clear()
                if (status.value.mode == RedfruitLoginMode.QR) {
                    mutableStatus.update { it.copy(qrImage = null, qrScanned = false) }
                }
            }
        }
    }

    fun startPhone() {
        if (status.value.restoring || session != null || status.value.busy) return
        mutableStatus.update { it.copy(mode = RedfruitLoginMode.PHONE, error = null) }
    }

    fun sendSms(number: String) {
        val state = status.value
        if (state.mode != RedfruitLoginMode.PHONE || state.busy || state.smsRetryAt > SystemClock.elapsedRealtime()) return
        perform {
            val sent = phone.send(number)
            mutableStatus.update {
                it.copy(smsRequested = true, smsRetryAt = SystemClock.elapsedRealtime() + sent.retrySeconds * 1000L)
            }
        }
    }

    fun verifySms(code: String) {
        if (!status.value.smsRequested || status.value.busy) return
        perform { handlePhoneResult(phone.verify(code)) }
    }

    fun checkMfa() {
        if (status.value.mfa == null || status.value.busy) return
        perform {
            // The user has sent the message on their phone. This checks the provider's status only.
            var result: RedfruitPhoneResult = RedfruitPhoneResult.Pending
            repeat(12) {
                result = phone.checkMfa()
                if (result != RedfruitPhoneResult.Pending) {
                    handlePhoneResult(result)
                    return@perform
                }
                delay(3000L)
            }
            handlePhoneResult(result)
        }
    }

    private suspend fun handlePhoneResult(result: RedfruitPhoneResult) {
        when (result) {
            is RedfruitPhoneResult.Approved -> finishLogin(result.credentials)
            is RedfruitPhoneResult.Mfa -> mutableStatus.update { it.copy(mfa = result) }
            RedfruitPhoneResult.Pending -> showError(RedfruitAccountError.PHONE_WAIT)
            RedfruitPhoneResult.Expired -> {
                mutableStatus.update { it.copy(smsRequested = false, mfa = null) }
                showError(RedfruitAccountError.PHONE_EXPIRED)
            }
        }
    }

    fun cancelLogin() {
        if (session != null || status.value.mode == RedfruitLoginMode.CHOICE) return
        generation++
        val previous = request
        previous?.cancel()
        mutableStatus.update {
            it.copy(
                mode = RedfruitLoginMode.CHOICE, busy = true, qrImage = null, qrScanned = false,
                smsRequested = false, mfa = null, error = null,
            )
        }
        perform {
            previous?.join()
            phone.reset()
            qr.clear()
        }
    }

    fun refresh() {
        val credentials = session ?: return
        val owner = profile ?: return
        if (status.value.busy) return
        perform { readLibrary(credentials, owner) }
    }

    fun signOut() {
        if (session == null || status.value.busy) return
        perform {
            clearSession()
            phone.reset()
            qr.clear()
        }
    }

    private suspend fun finishLogin(credentials: RedfruitCredentials) {
        val account = try {
            cloud.profile(credentials)
        } catch (error: IOException) {
            showError(RedfruitAccountError.CLOUD_SESSION)
            return
        }
        try {
            persistSession(credentials, account)
        } catch (cancelled: CancellationException) {
            // The file may have been committed before cancellation reached the main thread.
            try {
                withContext(NonCancellable + Dispatchers.IO) { sessionFile.clear() }
            } catch (_: IOException) {
                throw LocalStorageError()
            }
            throw cancelled
        }
        profile = account
        session = credentials
        mutableStatus.update {
            it.copy(
                accountName = account.name, mode = RedfruitLoginMode.CHOICE,
                qrImage = null, qrScanned = false, smsRequested = false, mfa = null,
            )
        }
        phone.reset()
        readLibrary(credentials, account)
    }

    private suspend fun readLibrary(credentials: RedfruitCredentials, owner: RedfruitCloudProfile) {
        mutableStatus.update { it.copy(historyLoading = true, favoritesLoading = true) }
        try {
            coroutineScope {
                launch {
                    val result = readResult { cloud.history(credentials) }
                    mutableStatus.update {
                        it.copy(
                            history = result.getOrNull() ?: it.history,
                            historyLoading = false,
                            historyFailed = result.isFailure,
                        )
                    }
                }
                launch {
                    val result = readResult {
                        val shelfOwner = resolveShelfOwner(credentials, owner)
                        cloud.favorites(credentials, shelfOwner)
                    }
                    mutableStatus.update {
                        it.copy(
                            favorites = result.getOrNull() ?: it.favorites,
                            favoritesLoading = false,
                            favoritesFailed = result.isFailure,
                        )
                    }
                }
            }
        } catch (error: RedfruitCloudException) {
            if (!error.authenticationRejected) throw error
            clearSession()
            showError(RedfruitAccountError.CLOUD_SESSION)
        } finally {
            mutableStatus.update { it.copy(historyLoading = false, favoritesLoading = false) }
        }
    }

    private suspend fun resolveShelfOwner(
        credentials: RedfruitCredentials,
        owner: RedfruitCloudProfile,
    ): RedfruitCloudProfile {
        if (owner.shelfUserId != null) return owner
        // Older session files had only the business ID, which need not be the shelf's user ID.
        val verified = cloud.profile(credentials)
        if (verified.id != owner.id) {
            throw RedfruitCloudException("红果账号身份已变更，请重新登录", authenticationRejected = true)
        }
        try {
            persistSession(credentials, verified)
        } catch (_: LocalStorageError) {
            // The previously stored login remains available if this optional migration cannot be saved.
        }
        profile = verified
        mutableStatus.update { it.copy(accountName = verified.name) }
        return verified
    }

    private suspend fun persistSession(credentials: RedfruitCredentials, owner: RedfruitCloudProfile) {
        withContext(Dispatchers.IO) {
            try {
                sessionFile.write(StoredRedfruitSession(credentials, owner).toJson())
            } catch (_: IOException) {
                throw LocalStorageError()
            }
        }
    }

    private suspend fun <T> readResult(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: RedfruitCloudException) {
        if (error.authenticationRejected) throw error
        Result.failure(error)
    } catch (error: Exception) {
        Result.failure(error)
    }

    private suspend fun clearSession() {
        withContext(Dispatchers.IO) {
            try {
                sessionFile.clear()
            } catch (_: IOException) {
                throw LocalStorageError()
            }
        }
        session = null
        profile = null
        mutableStatus.update {
            it.copy(accountName = null, history = null, favorites = null, historyFailed = false, favoritesFailed = false)
        }
    }

    private fun perform(block: suspend () -> Unit) {
        val version = ++generation
        mutableStatus.update { it.copy(busy = true, error = null) }
        request = scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: RedfruitPhoneException) {
                val kind = when (error.kind) {
                    RedfruitPhoneFailure.INVALID_NUMBER -> RedfruitAccountError.PHONE_NUMBER
                    RedfruitPhoneFailure.INVALID_CODE -> RedfruitAccountError.PHONE_CODE
                    RedfruitPhoneFailure.EXPIRED -> RedfruitAccountError.PHONE_EXPIRED
                    RedfruitPhoneFailure.WAIT -> RedfruitAccountError.PHONE_WAIT
                    RedfruitPhoneFailure.ADDITIONAL_VERIFICATION -> RedfruitAccountError.VERIFY_REQUIRED
                    RedfruitPhoneFailure.REJECTED -> RedfruitAccountError.SERVICE
                }
                error.retrySeconds?.let { seconds ->
                    mutableStatus.update { it.copy(smsRetryAt = SystemClock.elapsedRealtime() + seconds * 1000L) }
                }
                if (kind == RedfruitAccountError.PHONE_EXPIRED) {
                    mutableStatus.update { it.copy(smsRequested = false, mfa = null) }
                }
                showError(kind)
            } catch (_: LocalStorageError) {
                showError(RedfruitAccountError.STORAGE)
            } catch (_: IOException) {
                showError(RedfruitAccountError.NETWORK)
            } catch (_: Exception) {
                showError(RedfruitAccountError.SERVICE)
            } finally {
                if (version == generation) mutableStatus.update { it.copy(busy = false) }
            }
        }
    }

    private fun showError(error: RedfruitAccountError) {
        mutableStatus.update { it.copy(error = error) }
    }

    private class LocalStorageError : IOException()
}

internal data class StoredRedfruitSession(
    val credentials: RedfruitCredentials,
    val profile: RedfruitCloudProfile,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("cookie", credentials.cookie)
        .put("token", credentials.token)
        .put("accountId", profile.id)
        .put("accountName", profile.name)
        .put("shelfUserId", profile.shelfUserId)

    companion object {
        fun read(json: JSONObject): StoredRedfruitSession = StoredRedfruitSession(
            credentials = RedfruitCredentials(json.getString("cookie"), json.optString("token")),
            profile = RedfruitCloudProfile(
                id = json.getString("accountId"),
                name = json.getString("accountName"),
                shelfUserId = (json.opt("shelfUserId") as? String)?.takeIf(String::isNotBlank),
            ),
        )
    }
}
