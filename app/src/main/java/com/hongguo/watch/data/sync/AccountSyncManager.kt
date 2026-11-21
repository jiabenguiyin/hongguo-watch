package com.hongguo.watch.data.sync

import android.content.Context
import android.database.sqlite.SQLiteException
import android.os.SystemClock
import android.util.AtomicFile
import com.hongguo.watch.data.LocalLibrary
import com.hongguo.watch.data.db.AppDatabase
import com.hongguo.watch.data.db.LibrarySyncState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.max

internal enum class SyncError { NETWORK, SESSION_EXPIRED, PAIRING_EXPIRED, SERVICE, LOCAL_STORAGE }

internal data class SyncPairing(val userCode: String, val verificationUri: String, val verificationUriBase: String)

internal data class AccountSyncState(
    val configured: Boolean,
    val accountName: String? = null,
    val pairing: SyncPairing? = null,
    val busy: Boolean = false,
    val lastSyncAt: Long? = null,
    val needsInitialImport: Boolean = false,
    val lastError: SyncError? = null,
)

/** Owns the active library and serializes pairing, logout, and sync on the main dispatcher. */
internal class AccountSyncManager(
    context: Context,
    private val guest: LocalLibrary,
    private val transport: SyncTransport,
) {
    private data class Account(val session: SyncSession, val library: LocalLibrary)
    private val appContext = context.applicationContext
    private val store = SyncSessionStore(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val restored = CompletableDeferred<Unit>()
    private val mutableStatus = MutableStateFlow(AccountSyncState(transport.configured))
    val status: StateFlow<AccountSyncState> = mutableStatus.asStateFlow()

    private val accountLibraries = mutableMapOf<String, LocalLibrary>()
    @Volatile private var active: Account? = null
    private var pairingJob: Job? = null
    private var pendingWatcher: Job? = null
    private var scheduledSync: Job? = null
    private var nextAutomaticAt = 0L
    private var automaticFailures = 0

    init {
        if (transport.configured) {
            runRequest {
                try {
                    val session = localDisk { store.load(transport.origin) }
                    if (session != null) openAccount(session, persist = false)
                } finally {
                    restored.complete(Unit)
                }
            }
        } else {
            restored.complete(Unit)
        }
    }

    suspend fun library(): LocalLibrary {
        restored.await()
        return active?.library ?: guest
    }

    fun beginPairing() {
        if (!transport.configured || status.value.busy || active != null) return
        pairingJob = runRequest {
            try {
                val ticket = transport.beginPairing()
                mutableStatus.update {
                    it.copy(pairing = SyncPairing(ticket.userCode, ticket.verificationUri, ticket.verificationUriBase))
                }
                val deadline = SystemClock.elapsedRealtime() + ticket.expiresIn * 1_000L
                while (true) {
                    val remaining = deadline - SystemClock.elapsedRealtime()
                    if (remaining <= 0) break
                    delay(minOf(remaining, ticket.interval * 1_000L))
                    if (SystemClock.elapsedRealtime() >= deadline) break
                    when (val result = poll(ticket)) {
                        PairingResult.Pending -> Unit
                        PairingResult.Expired -> break
                        is PairingResult.Approved -> {
                            mutableStatus.update { it.copy(pairing = null, lastError = null) }
                            openAccount(result.session, persist = true)
                            return@runRequest
                        }
                    }
                }
                setError(SyncError.PAIRING_EXPIRED)
            } finally {
                mutableStatus.update { it.copy(pairing = null) }
            }
        }
    }

    fun cancelPairing() {
        if (status.value.pairing == null) return
        pairingJob?.cancel()
        setError(null)
    }

    private suspend fun poll(ticket: PairingTicket): PairingResult = try {
        transport.poll(ticket).also { setError(null) }
    } catch (error: SyncServiceException) {
        throw error
    } catch (_: IOException) {
        setError(SyncError.NETWORK)
        PairingResult.Pending
    }

    private suspend fun openAccount(session: SyncSession, persist: Boolean) {
        val key = MessageDigest.getInstance("SHA-256")
            .digest("${session.origin}\n${session.accountId}".encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
        val library = accountLibraries.getOrPut(key) {
            val path = File(appContext.noBackupFilesDir, "account-library-$key.db")
            LocalLibrary(AppDatabase.create(appContext, path.absolutePath))
        }
        val state = library.syncState()
        if (persist) localDisk { store.save(session) }
        active = Account(session, library)
        automaticFailures = 0
        nextAutomaticAt = 0L
        mutableStatus.update {
            it.copy(
                accountName = session.accountName,
                needsInitialImport = !state.initialized,
                lastSyncAt = state.lastSyncAt.takeIf { time -> time > 0 },
            )
        }
        pendingWatcher?.cancel()
        pendingWatcher = scope.launch {
            library.observePending().collect { sequence ->
                if (sequence > 0) scheduleSync(LOCAL_CHANGE_DELAY)
            }
        }
        if (state.initialized) scheduleSync(0)
    }

    fun signOut() {
        val account = active ?: return
        if (status.value.busy) return
        runRequest {
            localDisk { store.clear() }
            switchToGuest()
            scope.launch {
                try {
                    transport.revoke(account.session)
                } catch (_: IOException) {
                    // Local sign-out remains available when the service cannot be reached.
                }
            }
        }
    }

    private fun switchToGuest() {
        pendingWatcher?.cancel()
        scheduledSync?.cancel()
        scheduledSync = null
        active = null
        mutableStatus.update { it.copy(accountName = null, lastSyncAt = null, needsInitialImport = false) }
    }

    fun onForeground() {
        val lastSync = status.value.lastSyncAt ?: 0L
        if (System.currentTimeMillis() - lastSync >= FOREGROUND_REFRESH_INTERVAL) scheduleSync(0)
    }

    fun syncNow(importLocal: Boolean? = null) {
        if (status.value.busy) return
        automaticFailures = 0
        synchronize(importLocal)
    }

    private fun scheduleSync(waitMs: Long) {
        if (active == null || status.value.needsInitialImport || scheduledSync?.isActive == true) return
        scheduledSync = scope.launch {
            // Yield even on an immediate refresh so the current request can release its busy state.
            delay(max(1, max(waitMs, nextAutomaticAt - SystemClock.elapsedRealtime())))
            scheduledSync = null
            if (status.value.busy) scheduleSync(LOCAL_CHANGE_DELAY) else synchronize(null)
        }
    }

    private fun synchronize(importLocal: Boolean?) {
        val account = active ?: return
        if (status.value.needsInitialImport != (importLocal != null)) return
        scheduledSync?.cancel()
        scheduledSync = null
        nextAutomaticAt = SystemClock.elapsedRealtime() + AUTO_INTERVAL
        runRequest {
            try {
                val state = withContext(Dispatchers.IO) { exchangeLibrary(account, importLocal) }
                automaticFailures = 0
                mutableStatus.update { it.copy(needsInitialImport = false, lastSyncAt = state.lastSyncAt) }
                if (account.library.batch(1).changes.isNotEmpty()) scheduleSync(LOCAL_CHANGE_DELAY)
            } catch (error: SyncUnauthorizedException) {
                switchToGuest()
                localDisk { store.clear() }
                setError(SyncError.SESSION_EXPIRED)
            } catch (error: IOException) {
                if (++automaticFailures <= MAX_AUTOMATIC_RETRIES) {
                    scheduleSync(AUTO_INTERVAL * automaticFailures)
                }
                throw error
            }
        }
    }

    private suspend fun exchangeLibrary(account: Account, importLocal: Boolean?): LibrarySyncState {
        val installation = localDisk { installationId() }
        // Send pending explicit edits before the optional, fill-only guest import.
        repeat(MAX_CHANGE_BATCHES) {
            val batch = account.library.batch(BATCH_SIZE)
            val payload = LibrarySyncWire.request(batch.cursor, installation, batch.changes, batch.history, batch.favorites)
            val reply = LibrarySyncWire.parse(
                transport.exchange(account.session, payload), batch.cursor, installation, batch.changes,
            )
            if (reply.imported) throw SyncServiceException()
            val finished = batch.changes.size < BATCH_SIZE
            val initialized = importLocal == null || (finished && !importLocal)
            account.library.applySync(
                reply.cursor, reply.history, reply.favorites, batch.changes, reply.accepted,
                initialized, System.currentTimeMillis(),
            )
            if (finished) {
                if (importLocal == true) importGuest(account, installation, reply.cursor)
                return account.library.syncState()
            }
        }
        if (importLocal != null) throw SyncServiceException("Pending account changes have not finished syncing")
        return account.library.syncState()
    }

    private suspend fun importGuest(account: Account, installation: String, startingCursor: Long) {
        val guestSnapshot = guest.snapshot()
        val histories = guestSnapshot.history.chunked(BATCH_SIZE)
        val favorites = guestSnapshot.favorites.chunked(BATCH_SIZE)
        val batchCount = max(histories.size + favorites.size, 1)
        var cursor = startingCursor
        repeat(batchCount) { index ->
            val history = histories.getOrNull(index).orEmpty()
            val favorite = favorites.getOrNull(index - histories.size).orEmpty()
            val payload = LibrarySyncWire.importRequest(cursor, history, favorite)
            val reply = LibrarySyncWire.parse(transport.exchange(account.session, payload), cursor, installation, emptyList())
            if (!reply.imported) throw SyncServiceException()
            cursor = reply.cursor
            account.library.applySync(
                cursor, reply.history, reply.favorites, emptyList(), emptyMap(),
                index == batchCount - 1, System.currentTimeMillis(),
            )
        }
    }

    private fun installationId(): String {
        val file = AtomicFile(File(appContext.noBackupFilesDir, "account-sync-installation"))
        try {
            return file.openRead().bufferedReader().use { reader -> UUID.fromString(reader.readLine()).toString() }
        } catch (_: FileNotFoundException) {
            val id = UUID.randomUUID().toString()
            val stream = file.startWrite()
            try {
                stream.write(id.encodeToByteArray())
                file.finishWrite(stream)
            } catch (error: Exception) {
                file.failWrite(stream)
                throw error
            }
            return id
        }
    }

    private fun runRequest(block: suspend () -> Unit): Job {
        mutableStatus.update { it.copy(busy = true, lastError = null) }
        return scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: LocalSyncException) {
                setError(SyncError.LOCAL_STORAGE)
            } catch (_: SQLiteException) {
                setError(SyncError.LOCAL_STORAGE)
            } catch (_: SyncServiceException) {
                setError(SyncError.SERVICE)
            } catch (_: IOException) {
                setError(SyncError.NETWORK)
            } finally {
                mutableStatus.update { it.copy(busy = false) }
            }
        }
    }

    private fun setError(error: SyncError?) {
        mutableStatus.update { it.copy(lastError = error) }
    }

    private suspend fun <T> localDisk(block: () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (_: IOException) {
            throw LocalSyncException()
        } catch (_: IllegalArgumentException) {
            throw LocalSyncException()
        }
    }

    private class LocalSyncException : Exception()

    private companion object {
        const val BATCH_SIZE = 64
        const val MAX_CHANGE_BATCHES = 64
        const val LOCAL_CHANGE_DELAY = 8_000L
        const val AUTO_INTERVAL = 45_000L
        const val FOREGROUND_REFRESH_INTERVAL = 60_000L
        const val MAX_AUTOMATIC_RETRIES = 3
    }
}
