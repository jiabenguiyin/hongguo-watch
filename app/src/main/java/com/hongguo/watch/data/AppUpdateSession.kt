package com.hongguo.watch.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

/** Update checks shared by the app's activities. Call from the main dispatcher. */
internal class AppUpdateSession(
    private val scope: CoroutineScope,
    private val checkLatest: suspend () -> UpdateChecker.Result = {
        withContext(Dispatchers.IO) { UpdateChecker.check() }
    },
) {
    private var inFlight: Deferred<Result<UpdateChecker.Result>>? = null
    private var startupCheck: Deferred<Result<UpdateChecker.Result>>? = null
    private var startupHandled = false

    suspend fun takeStartupUpdate(): UpdateChecker.Result? {
        if (startupHandled) return null
        // The app owns this request so leaving or recreating the activity does not cancel it.
        val check = startupCheck ?: currentOrNewCheck().also { startupCheck = it }
        val result = check.await().getOrNull()
        if (startupHandled) return null
        startupHandled = true
        startupCheck = null
        return result?.takeIf { it.isNewer }
    }

    suspend fun checkManually(): Result<UpdateChecker.Result> = currentOrNewCheck().await()

    fun markManualResultShown() {
        startupHandled = true
        startupCheck = null
    }

    private fun currentOrNewCheck(): Deferred<Result<UpdateChecker.Result>> {
        val active = inFlight
        if (active != null && !active.isCompleted) return active
        return scope.async {
            try {
                Result.success(checkLatest())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
        }.also { inFlight = it }
    }
}
