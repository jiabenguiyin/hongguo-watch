package com.hongguo.watch.data.redfruit

import android.content.Context
import android.os.SystemClock
import com.hongguo.watch.data.db.AppDatabase
import com.hongguo.watch.data.LocalLibrary
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.IOException
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal enum class RedfruitLoginMode { CHOICE, QR, PHONE }
internal enum class RedfruitAccountError { NETWORK, SERVICE, QR_EXPIRED, PHONE_NUMBER, PHONE_CODE, PHONE_EXPIRED, PHONE_WAIT, VERIFY_REQUIRED, CLOUD_SESSION, STORAGE }
internal enum class RedfruitWriteSyncStatus { DISABLED, IDLE, RUNNING, SUCCESS, FAILED }
internal enum class RedfruitInitialMergeChoice { MERGE, CLOUD_ONLY }

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
    val needsInitialMergeChoice: Boolean = false,
    val localHistoryCount: Int = 0,
    val localFavoriteCount: Int = 0,
    val autoSyncEnabled: Boolean = false,
    val writeSyncStatus: RedfruitWriteSyncStatus = RedfruitWriteSyncStatus.DISABLED,
    val uploadedHistoryCount: Int = 0,
    val uploadedFavoriteCount: Int = 0,
)

internal class RedfruitAccountManager(
    context: Context,
    private val guestLibrary: suspend () -> LocalLibrary,
) {
    private val appContext = context.applicationContext
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
    private val sessionWriteMutex = Mutex()
    private var session: RedfruitCredentials? = null
    private var profile: RedfruitCloudProfile? = null
    private var request: Job? = null
    private var writeSyncJob: Job? = null
    private var autoSyncEnabled = false
    private var lastCloudRefreshAt = 0L
    private var initialMergeCounts: Pair<Int, Int>? = null
    private var initialMergeInFlight = false
    private var accountLibrary: LocalLibrary? = null
    private val accountLibraries = linkedMapOf<String, LocalLibrary>()
    private val pendingProgress = linkedMapOf<String, PendingProgress>()
    private val pendingFavorites = linkedMapOf<String, Boolean>()
    private var generation = 0

    private data class PendingProgress(val record: WatchHistoryEntity, val episodeCount: Int)

    fun isLoggedIn(): Boolean = session != null && accountLibrary != null

    suspend fun library(): LocalLibrary = accountLibrary ?: guestLibrary()

    private suspend fun activeLibrary(): LocalLibrary = accountLibrary ?: guestLibrary()

    suspend fun libraryFavorites(): List<FavoriteEntity> {
        val local = activeLibrary().favorites.list()
        val state = mutableStatus.value
        // Keep the pre-existing local snapshot visible until the first-login
        // merge choice has been confirmed.
        if (state.needsInitialMergeChoice) return local
        return RedfruitSyncPolicy.favoritesForLibrary(
            local = local,
            remote = state.favorites.takeUnless { state.favoritesFailed },
            pending = pendingFavorites,
        )
    }

    /**
     * 官方追更列表。未登录或请求失败返回 null，由 UI 降级到本地 `last_serial_count` 游标。
     */
    suspend fun libraryChaseBooks(): List<RedfruitChaseBook>? {
        val credentials = session ?: return null
        if (mutableStatus.value.needsInitialMergeChoice) return null
        return runCatching {
            withContext(Dispatchers.IO) { cloud.chaseBooks(credentials) }
        }.getOrNull()
    }

    private fun libraryForAccount(accountId: String): LocalLibrary {
        val key = MessageDigest.getInstance("SHA-256")
            .digest(accountId.encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
        return accountLibraries.getOrPut(key) {
            LocalLibrary(
                AppDatabase.create(
                    appContext,
                    File(appContext.noBackupFilesDir, "redfruit-library-$key.db").absolutePath,
                ),
            )
        }
    }

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
            initialMergeCounts = restored?.initialMergeCounts
            val legacyGuest = if (restored != null) guestLibrary().snapshot() else null
            if (restored != null) {
                accountLibrary = libraryForAccount(restored.profile.id)
            }
            if (restored != null && !restored.accountLibraryConfigured && initialMergeCounts == null) {
                initialMergeCounts = legacyGuest?.officialRedfruitCounts()
                    ?.takeIf { it.first > 0 || it.second > 0 }
            }
            if (restored != null && legacyGuest != null &&
                legacyGuest.officialRedfruitCounts().let { it.first > 0 || it.second > 0 }
            ) {
                accountLibrary?.mergeOfficialCloud(
                    remoteHistory = legacyGuest.history,
                    remoteFavorites = legacyGuest.favorites,
                )
                guestLibrary().removeOfficialRedfruitRows()
            }
            autoSyncEnabled = restored?.let {
                // Sessions written before the preference existed should behave
                // as logged-in accounts and start syncing after migration.
                if (!it.autoSyncConfigured) true else it.autoSyncEnabled
            } == true
            pendingFavorites.putAll(restored?.pendingFavoriteChanges.orEmpty())
            mutableStatus.update {
                it.copy(
                    restoring = false,
                    accountName = restored?.profile?.name,
                    autoSyncEnabled = autoSyncEnabled,
                    writeSyncStatus = if (autoSyncEnabled) RedfruitWriteSyncStatus.IDLE
                    else RedfruitWriteSyncStatus.DISABLED,
                )
            }
            if (restored != null && !restored.autoSyncConfigured) {
                try {
                    persistCurrentSession()
                } catch (_: Exception) {
                    // Keep the restored session usable if this migration write fails.
                }
            }
            val credentials = session
            val owner = profile
            val savedMergeCounts = initialMergeCounts
            if (credentials != null && owner != null && savedMergeCounts != null) {
                val local = accountLibrary?.snapshot() ?: guestLibrary().snapshot()
                perform {
                    readLibrary(
                        credentials,
                        owner,
                        localBeforeLogin = local,
                        savedMergeCounts = savedMergeCounts,
                    )
                }
            } else {
                refresh()
            }
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
        if (status.value.busy || status.value.writeSyncStatus == RedfruitWriteSyncStatus.RUNNING) return
        perform { readLibrary(credentials, owner) }
    }

    /** Refresh the official account when any foreground screen becomes visible. */
    fun onForeground() {
        if (session == null || status.value.busy ||
            status.value.writeSyncStatus == RedfruitWriteSyncStatus.RUNNING
        ) return
        if (System.currentTimeMillis() - lastCloudRefreshAt >= CLOUD_REFRESH_INTERVAL) {
            refresh()
        }
    }

    /**
     * Resolves the one-time choice shown after a login finds pre-existing
     * unauthenticated watch records.
     */
    fun resolveInitialMerge(choice: RedfruitInitialMergeChoice) {
        val credentials = session ?: return
        val owner = profile ?: return
        if (!status.value.needsInitialMergeChoice || status.value.busy) return
        mutableStatus.update { it.copy(error = null) }
        initialMergeInFlight = true
        if (choice == RedfruitInitialMergeChoice.MERGE) {
            autoSyncEnabled = true
            mutableStatus.update {
                it.copy(
                    autoSyncEnabled = true,
                    writeSyncStatus = RedfruitWriteSyncStatus.IDLE,
                )
            }
            scope.launch {
                try {
                    persistCurrentSession()
                } catch (_: IOException) {
                    mutableStatus.update { it.copy(writeSyncStatus = RedfruitWriteSyncStatus.FAILED) }
                }
            }
            syncLocalNow()
            return
        }
        writeSyncJob = scope.launch {
            mutableStatus.update {
                it.copy(
                    writeSyncStatus = RedfruitWriteSyncStatus.RUNNING,
                    uploadedHistoryCount = 0,
                    uploadedFavoriteCount = 0,
                )
            }
            try {
                val remoteHistory = status.value.history ?: cloud.history(credentials)
                val remoteFavorites = status.value.favorites
                    ?: cloud.favorites(credentials, resolveShelfOwner(credentials, owner))
                activeLibrary().mergeOfficialCloud(
                    remoteHistory = remoteHistory,
                    remoteFavorites = remoteFavorites,
                    replaceLocal = true,
                )
                autoSyncEnabled = true
                persistCurrentSession()
                completeInitialMergeChoice()
                mutableStatus.update {
                    it.copy(
                        autoSyncEnabled = true,
                        writeSyncStatus = RedfruitWriteSyncStatus.SUCCESS,
                        history = remoteHistory,
                        favorites = remoteFavorites,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: RedfruitCloudException) {
                if (error.authenticationRejected) showError(RedfruitAccountError.CLOUD_SESSION)
                mutableStatus.update { it.copy(writeSyncStatus = RedfruitWriteSyncStatus.FAILED) }
            } catch (_: IOException) {
                mutableStatus.update { it.copy(writeSyncStatus = RedfruitWriteSyncStatus.FAILED) }
            } catch (_: Exception) {
                mutableStatus.update { it.copy(writeSyncStatus = RedfruitWriteSyncStatus.FAILED) }
            } finally {
                writeSyncJob = null
            }
        }
    }

    fun setAutoSyncEnabled(enabled: Boolean) {
        if (session == null || autoSyncEnabled == enabled || status.value.writeSyncStatus == RedfruitWriteSyncStatus.RUNNING) return
        autoSyncEnabled = enabled
        mutableStatus.update {
            it.copy(
                autoSyncEnabled = enabled,
                writeSyncStatus = if (enabled) RedfruitWriteSyncStatus.IDLE else RedfruitWriteSyncStatus.DISABLED,
            )
        }
        scope.launch {
            try {
                persistCurrentSession()
                if (enabled) syncLocalNow()
            } catch (_: IOException) {
                autoSyncEnabled = !enabled
                mutableStatus.update {
                    it.copy(
                        autoSyncEnabled = autoSyncEnabled,
                        writeSyncStatus = if (autoSyncEnabled) RedfruitWriteSyncStatus.FAILED
                        else RedfruitWriteSyncStatus.DISABLED,
                    )
                }
            }
        }
    }

    fun syncLocalNow() {
        val credentials = session ?: return
        val owner = profile ?: return
        if (writeSyncJob?.isActive == true) return
        mutableStatus.update {
            it.copy(
                writeSyncStatus = RedfruitWriteSyncStatus.RUNNING,
                uploadedHistoryCount = 0,
                uploadedFavoriteCount = 0,
            )
        }
        writeSyncJob = scope.launch {
            try {
                val result = mergeLocalLibrary(credentials, owner)
                if (autoSyncEnabled) processPendingChanges(credentials, owner)
                if (initialMergeInFlight) completeInitialMergeChoice()
                mutableStatus.update {
                    it.copy(
                        writeSyncStatus = RedfruitWriteSyncStatus.SUCCESS,
                        uploadedHistoryCount = result.first,
                        uploadedFavoriteCount = result.second,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: RedfruitCloudException) {
                if (error.authenticationRejected) showError(RedfruitAccountError.CLOUD_SESSION)
                mutableStatus.update { it.copy(writeSyncStatus = RedfruitWriteSyncStatus.FAILED) }
            } catch (_: IOException) {
                mutableStatus.update { it.copy(writeSyncStatus = RedfruitWriteSyncStatus.FAILED) }
            } catch (_: Exception) {
                mutableStatus.update { it.copy(writeSyncStatus = RedfruitWriteSyncStatus.FAILED) }
            } finally {
                writeSyncJob = null
            }
        }
    }

    private suspend fun completeInitialMergeChoice() {
        val previous = initialMergeCounts
        initialMergeCounts = null
        try {
            persistCurrentSession()
        } catch (error: Exception) {
            initialMergeCounts = previous
            throw error
        }
        initialMergeInFlight = false
        mutableStatus.update {
            it.copy(
                needsInitialMergeChoice = false,
                localHistoryCount = 0,
                localFavoriteCount = 0,
            )
        }
    }

    fun recordProgress(record: WatchHistoryEntity, episodeCount: Int, force: Boolean) {
        if (!autoSyncEnabled || session == null || record.source != "APP") return
        if (!record.seriesId.isNumericId() || !record.episodeId.isNumericId()) return
        val pending = PendingProgress(record, episodeCount.coerceAtLeast(0))
        val previous = pendingProgress[record.seriesId]
        if (previous == null || previous.record.updatedAt <= record.updatedAt) {
            pendingProgress[record.seriesId] = pending
        }
        schedulePendingChanges(if (force) 0 else PROGRESS_UPLOAD_DELAY)
    }

    fun recordFavoriteChange(record: FavoriteEntity, added: Boolean) {
        if (!autoSyncEnabled || session == null || record.source != "APP" || !record.seriesId.isNumericId()) return
        pendingFavorites[record.seriesId] = added
        scope.launch {
            try {
                persistCurrentSession()
                schedulePendingChanges(FAVORITE_UPLOAD_DELAY)
            } catch (_: IOException) {
                mutableStatus.update { it.copy(writeSyncStatus = RedfruitWriteSyncStatus.FAILED) }
            }
        }
    }

    fun signOut() {
        if (session == null || status.value.busy || status.value.writeSyncStatus == RedfruitWriteSyncStatus.RUNNING) return
        perform {
            clearSession()
            phone.reset()
            qr.clear()
        }
    }

    private suspend fun finishLogin(credentials: RedfruitCredentials) {
        val guest = guestLibrary()
        val localBeforeLogin = guest.snapshot()
        val mergeCounts = localBeforeLogin.officialRedfruitCounts()
            .takeIf { it.first > 0 || it.second > 0 }
        val account = try {
            cloud.profile(credentials)
        } catch (error: IOException) {
            showError(RedfruitAccountError.CLOUD_SESSION)
            return
        }
        val targetLibrary = libraryForAccount(account.id)
        targetLibrary.mergeOfficialCloud(
            remoteHistory = localBeforeLogin.history,
            remoteFavorites = localBeforeLogin.favorites,
        )
        try {
            persistSession(credentials, account, mergeCounts)
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
        accountLibrary = targetLibrary
        guest.removeOfficialRedfruitRows()
        initialMergeCounts = mergeCounts
        mutableStatus.update {
            it.copy(
                accountName = account.name, mode = RedfruitLoginMode.CHOICE,
                qrImage = null, qrScanned = false, smsRequested = false, mfa = null,
            )
        }
        phone.reset()
        readLibrary(
            credentials,
            account,
            localBeforeLogin = localBeforeLogin,
            savedMergeCounts = mergeCounts,
        )
    }

    private suspend fun readLibrary(
        credentials: RedfruitCredentials,
        owner: RedfruitCloudProfile,
        localBeforeLogin: LocalLibrary.Snapshot? = null,
        savedMergeCounts: Pair<Int, Int>? = null,
    ) {
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
            lastCloudRefreshAt = System.currentTimeMillis()
            val current = status.value
            val firstLoginRows = savedMergeCounts
                ?: initialMergeCounts
                ?: localBeforeLogin?.officialRedfruitCounts()
            val needsInitialChoice = firstLoginRows != null &&
                (firstLoginRows.first > 0 || firstLoginRows.second > 0)
            if (!current.historyFailed && !current.favoritesFailed) {
                activeLibrary().mergeOfficialCloud(
                    remoteHistory = current.history.orEmpty(),
                    remoteFavorites = current.favorites.orEmpty().filter { pendingFavorites[it.seriesId] != false },
                    replaceLocalFavorites = !needsInitialChoice,
                    preserveFavoriteIds = pendingFavorites.filterValues { it }.keys,
                )
            }
            if (!status.value.historyFailed &&
                !status.value.favoritesFailed &&
                firstLoginRows != null &&
                (firstLoginRows.first > 0 || firstLoginRows.second > 0)
            ) {
                mutableStatus.update {
                    it.copy(
                        needsInitialMergeChoice = true,
                        localHistoryCount = firstLoginRows.first,
                        localFavoriteCount = firstLoginRows.second,
                    )
                }
            } else if (autoSyncEnabled && !status.value.historyFailed && !status.value.favoritesFailed) {
                syncLocalNow()
            } else if (localBeforeLogin != null &&
                !status.value.historyFailed &&
                !status.value.favoritesFailed
            ) {
                autoSyncEnabled = true
                mutableStatus.update {
                    it.copy(
                        autoSyncEnabled = true,
                        writeSyncStatus = RedfruitWriteSyncStatus.IDLE,
                    )
                }
                persistCurrentSession()
                syncLocalNow()
            }
        } catch (error: RedfruitCloudException) {
            if (!error.authenticationRejected) throw error
            clearSession()
            showError(RedfruitAccountError.CLOUD_SESSION)
        } finally {
            mutableStatus.update { it.copy(historyLoading = false, favoritesLoading = false) }
        }
    }

    private suspend fun mergeLocalLibrary(
        credentials: RedfruitCredentials,
        owner: RedfruitCloudProfile,
    ): Pair<Int, Int> {
        val local = activeLibrary().snapshot()
        val localHistory = local.history
            .filter { it.source == "APP" && it.seriesId.isNumericId() && it.episodeId.isNumericId() }
        val localFavorites = local.favorites
            .filter { it.source == "APP" && it.seriesId.isNumericId() }
        val remoteHistory = cloud.history(credentials)
        val progressToUpload = RedfruitSyncPolicy.progressToUpload(localHistory, remoteHistory)
        val shelfOwner = resolveShelfOwner(credentials, owner)
        val remoteFavorites = cloud.favorites(credentials, shelfOwner)
        val favoritesToAdd = RedfruitSyncPolicy.favoritesToAdd(localFavorites, remoteFavorites)

        for (row in progressToUpload) {
            val total = maxOf(
                localFavorites.firstOrNull { it.seriesId == row.seriesId }?.episodeCount ?: 0,
                row.episodeNumber,
            )
            cloud.uploadProgress(credentials, row, total)
        }
        for (favorite in favoritesToAdd) {
            cloud.setFavorite(credentials, favorite.seriesId, add = true)
        }

        val confirmedHistory = cloud.history(credentials)
        val confirmedFavorites = cloud.favorites(credentials, shelfOwner)
        val confirmedHistoryBySeries = RedfruitSyncPolicy.latestBySeries(confirmedHistory)
            .associateBy { it.seriesId }
        val unconfirmedHistory = progressToUpload.filterNot { row ->
            RedfruitSyncPolicy.confirmsAtLeastAsNew(row, confirmedHistoryBySeries[row.seriesId])
        }
        val confirmedFavoriteIds = confirmedFavorites.mapTo(mutableSetOf()) { it.seriesId }
        val unconfirmedFavorites = favoritesToAdd.filter { it.seriesId !in confirmedFavoriteIds }
        mutableStatus.update {
            it.copy(
                history = confirmedHistory,
                favorites = confirmedFavorites,
                historyFailed = false,
                favoritesFailed = false,
                historyLoading = false,
                favoritesLoading = false,
            )
        }
        activeLibrary().mergeOfficialCloud(
            remoteHistory = confirmedHistory,
            remoteFavorites = confirmedFavorites.filter { pendingFavorites[it.seriesId] != false },
            replaceLocalFavorites = true,
            preserveFavoriteIds = pendingFavorites.filterValues { it }.keys,
        )
        lastCloudRefreshAt = System.currentTimeMillis()
        if (unconfirmedHistory.isNotEmpty() || unconfirmedFavorites.isNotEmpty()) {
            throw RedfruitCloudException("红果云端尚未确认全部本机记录，请稍后重试")
        }
        return progressToUpload.size to favoritesToAdd.size
    }

    private suspend fun processPendingChanges(
        credentials: RedfruitCredentials,
        owner: RedfruitCloudProfile,
    ) {
        while (pendingProgress.isNotEmpty() || pendingFavorites.isNotEmpty()) {
            val progressBatch = pendingProgress.values.toList()
            val favoriteBatch = pendingFavorites.toMap()
            val remoteProgress = if (progressBatch.isEmpty()) {
                emptyList()
            } else {
                cloud.history(credentials)
            }
            val remoteProgressBySeries = RedfruitSyncPolicy.latestBySeries(remoteProgress)
                .associateBy { it.seriesId }
            val remoteFavoriteRows = if (favoriteBatch.isEmpty()) {
                emptyList()
            } else {
                cloud.favorites(credentials, resolveShelfOwner(credentials, owner))
            }
            val remoteFavoriteIds = remoteFavoriteRows.mapTo(mutableSetOf()) { it.seriesId }

            for (pending in progressBatch) {
                val row = pending.record
                val remote = remoteProgressBySeries[row.seriesId]
                if (remote == null || RedfruitSyncPolicy.compareProgress(row, remote) > 0) {
                    cloud.uploadProgress(credentials, row, pending.episodeCount)
                }
            }
            for ((seriesId, add) in favoriteBatch) {
                if ((seriesId in remoteFavoriteIds) != add) {
                    cloud.setFavorite(credentials, seriesId, add)
                }
            }

            val confirmedProgress = if (progressBatch.isEmpty()) {
                emptyList()
            } else {
                cloud.history(credentials)
            }
            val confirmedProgressBySeries = RedfruitSyncPolicy.latestBySeries(confirmedProgress)
                .associateBy { it.seriesId }
            val confirmedFavoriteRows = if (favoriteBatch.isEmpty()) {
                emptyList()
            } else {
                cloud.favorites(credentials, resolveShelfOwner(credentials, owner))
            }
            val confirmedFavoriteIds = confirmedFavoriteRows.mapTo(mutableSetOf()) { it.seriesId }

            for (pending in progressBatch) {
                val row = pending.record
                if (pendingProgress[row.seriesId] == pending &&
                    RedfruitSyncPolicy.confirmsAtLeastAsNew(row, confirmedProgressBySeries[row.seriesId])
                ) {
                    pendingProgress.remove(row.seriesId)
                }
            }
            for ((seriesId, add) in favoriteBatch) {
                if (pendingFavorites[seriesId] == add && ((seriesId in confirmedFavoriteIds) == add)) {
                    pendingFavorites.remove(seriesId)
                }
            }
            if (favoriteBatch.isNotEmpty()) persistCurrentSession()

            val progressConfirmed = progressBatch.all { pending ->
                RedfruitSyncPolicy.confirmsAtLeastAsNew(
                    pending.record,
                    confirmedProgressBySeries[pending.record.seriesId],
                )
            }
            val favoritesConfirmed = favoriteBatch.all { (seriesId, add) ->
                (seriesId in confirmedFavoriteIds) == add
            }
            if (!progressConfirmed || !favoritesConfirmed) {
                throw RedfruitCloudException("红果云端尚未确认本次进度或收藏变更")
            }
            activeLibrary().mergeOfficialCloud(
                remoteHistory = confirmedProgress,
                remoteFavorites = confirmedFavoriteRows,
                replaceLocalFavorites = favoriteBatch.isNotEmpty(),
                preserveFavoriteIds = pendingFavorites.filterValues { it }.keys,
            )
            mutableStatus.update {
                it.copy(
                    uploadedHistoryCount = it.uploadedHistoryCount + progressBatch.size,
                    uploadedFavoriteCount = it.uploadedFavoriteCount + favoriteBatch.size,
                )
            }
            if (confirmedFavoriteRows.isNotEmpty() || favoriteBatch.isNotEmpty()) {
                mutableStatus.update { it.copy(favorites = confirmedFavoriteRows, favoritesFailed = false) }
            }
        }
    }

    private fun schedulePendingChanges(waitMs: Long) {
        if (!autoSyncEnabled || session == null || writeSyncJob?.isActive == true) return
        val credentials = session ?: return
        val owner = profile ?: return
        writeSyncJob = scope.launch {
            try {
                if (waitMs > 0) delay(waitMs)
                mutableStatus.update {
                    it.copy(
                        writeSyncStatus = RedfruitWriteSyncStatus.RUNNING,
                        uploadedHistoryCount = 0,
                        uploadedFavoriteCount = 0,
                    )
                }
                processPendingChanges(credentials, owner)
                mutableStatus.update { it.copy(writeSyncStatus = RedfruitWriteSyncStatus.SUCCESS) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: RedfruitCloudException) {
                if (error.authenticationRejected) showError(RedfruitAccountError.CLOUD_SESSION)
                mutableStatus.update { it.copy(writeSyncStatus = RedfruitWriteSyncStatus.FAILED) }
            } catch (_: Exception) {
                mutableStatus.update { it.copy(writeSyncStatus = RedfruitWriteSyncStatus.FAILED) }
            } finally {
                writeSyncJob = null
                if (pendingProgress.isNotEmpty() || pendingFavorites.isNotEmpty()) {
                    if (status.value.writeSyncStatus == RedfruitWriteSyncStatus.SUCCESS) schedulePendingChanges(0)
                }
            }
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
        profile = verified
        try {
            persistCurrentSession()
        } catch (_: LocalStorageError) {
            // The previously stored login remains available if this optional migration cannot be saved.
        }
        mutableStatus.update { it.copy(accountName = verified.name) }
        return verified
    }

    private suspend fun persistSession(
        credentials: RedfruitCredentials,
        owner: RedfruitCloudProfile,
        mergeCounts: Pair<Int, Int>?,
    ) {
        withContext(Dispatchers.IO) {
            try {
                sessionFile.write(
                    StoredRedfruitSession(
                        credentials,
                        owner,
                        autoSyncEnabled,
                        pendingFavorites.toMap(),
                        autoSyncConfigured = true,
                        accountLibraryConfigured = true,
                        initialMergeHistoryCount = mergeCounts?.first ?: 0,
                        initialMergeFavoriteCount = mergeCounts?.second ?: 0,
                    ).toJson(),
                )
            } catch (_: IOException) {
                throw LocalStorageError()
            }
        }
    }

    private suspend fun persistCurrentSession() = sessionWriteMutex.withLock {
        val credentials = session ?: return@withLock
        val owner = profile ?: return@withLock
        withContext(Dispatchers.IO) {
            try {
                sessionFile.write(
                    StoredRedfruitSession(
                        credentials,
                        owner,
                        autoSyncEnabled,
                        pendingFavorites.toMap(),
                        autoSyncConfigured = true,
                        accountLibraryConfigured = true,
                        initialMergeHistoryCount = initialMergeCounts?.first ?: 0,
                        initialMergeFavoriteCount = initialMergeCounts?.second ?: 0,
                    ).toJson(),
                )
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
        writeSyncJob?.cancel()
        writeSyncJob = null
        withContext(Dispatchers.IO) {
            try {
                sessionFile.clear()
            } catch (_: IOException) {
                throw LocalStorageError()
            }
        }
        session = null
        profile = null
        accountLibrary = null
        autoSyncEnabled = false
        lastCloudRefreshAt = 0L
        initialMergeCounts = null
        initialMergeInFlight = false
        pendingProgress.clear()
        pendingFavorites.clear()
        mutableStatus.update {
            it.copy(
                accountName = null,
                history = null,
                favorites = null,
                historyFailed = false,
                favoritesFailed = false,
                needsInitialMergeChoice = false,
                localHistoryCount = 0,
                localFavoriteCount = 0,
                autoSyncEnabled = false,
                writeSyncStatus = RedfruitWriteSyncStatus.DISABLED,
                uploadedHistoryCount = 0,
                uploadedFavoriteCount = 0,
            )
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

    private companion object {
        const val PROGRESS_UPLOAD_DELAY = 15_000L
        const val FAVORITE_UPLOAD_DELAY = 800L
        const val CLOUD_REFRESH_INTERVAL = 45_000L
    }

    private class LocalStorageError : IOException()
}

internal data class StoredRedfruitSession(
    val credentials: RedfruitCredentials,
    val profile: RedfruitCloudProfile,
    val autoSyncEnabled: Boolean = false,
    val pendingFavoriteChanges: Map<String, Boolean> = emptyMap(),
    val autoSyncConfigured: Boolean = false,
    val accountLibraryConfigured: Boolean = false,
    val initialMergeHistoryCount: Int = 0,
    val initialMergeFavoriteCount: Int = 0,
) {
    val initialMergeCounts: Pair<Int, Int>?
        get() = (initialMergeHistoryCount to initialMergeFavoriteCount)
            .takeIf { it.first > 0 || it.second > 0 }

    fun toJson(): JSONObject = JSONObject()
        .put("cookie", credentials.cookie)
        .put("token", credentials.token)
        .put("accountId", profile.id)
        .put("accountName", profile.name)
        .put("shelfUserId", profile.shelfUserId)
        .put("autoSyncEnabled", autoSyncEnabled)
        .put("autoSyncConfigured", autoSyncConfigured)
        .put("accountLibraryConfigured", accountLibraryConfigured)
        .put("initialMergeHistoryCount", initialMergeHistoryCount.coerceAtLeast(0))
        .put("initialMergeFavoriteCount", initialMergeFavoriteCount.coerceAtLeast(0))
        .put(
            "pendingFavoriteChanges",
            JSONObject().apply {
                pendingFavoriteChanges.forEach { (seriesId, add) -> put(seriesId, add) }
            },
        )

    companion object {
        fun read(json: JSONObject): StoredRedfruitSession {
            val pendingFavorites = linkedMapOf<String, Boolean>()
            json.optJSONObject("pendingFavoriteChanges")?.let { values ->
                val keys = values.keys()
                while (keys.hasNext()) {
                    val seriesId = keys.next()
                    val desired = values.opt(seriesId)
                    if (seriesId.matches(Regex("[0-9]{1,30}")) && desired is Boolean) {
                        pendingFavorites[seriesId] = desired
                    }
                }
            }
            return StoredRedfruitSession(
                credentials = RedfruitCredentials(json.getString("cookie"), json.optString("token")),
                profile = RedfruitCloudProfile(
                    id = json.getString("accountId"),
                    name = json.getString("accountName"),
                    shelfUserId = (json.opt("shelfUserId") as? String)?.takeIf(String::isNotBlank),
                ),
                autoSyncEnabled = json.optBoolean("autoSyncEnabled", false),
                pendingFavoriteChanges = pendingFavorites,
                autoSyncConfigured = json.has("autoSyncConfigured") &&
                    json.optBoolean("autoSyncConfigured", false),
                accountLibraryConfigured = json.has("accountLibraryConfigured") &&
                    json.optBoolean("accountLibraryConfigured", false),
                initialMergeHistoryCount = json.optInt("initialMergeHistoryCount", 0).coerceIn(0, 100_000),
                initialMergeFavoriteCount = json.optInt("initialMergeFavoriteCount", 0).coerceIn(0, 100_000),
            )
        }
    }
}

private fun LocalLibrary.Snapshot.officialRedfruitCounts(): Pair<Int, Int> =
    history.count { it.source == "APP" && it.seriesId.isNumericId() } to
        favorites.count { it.source == "APP" && it.seriesId.isNumericId() }

private fun String.isNumericId(): Boolean =
    length in 1..30 && all { it in '0'..'9' } && any { it != '0' }
