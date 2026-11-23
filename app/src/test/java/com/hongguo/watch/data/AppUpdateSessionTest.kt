package com.hongguo.watch.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

class AppUpdateSessionTest {
    @Test(timeout = 5_000)
    fun startupRequestSurvivesLeavingTheActivityAndIsConsumedOnce() = runBlocking {
        val response = CompletableDeferred<UpdateChecker.Result>()
        var checks = 0
        val session = AppUpdateSession(this) {
            checks++
            response.await()
        }
        val firstActivity = async(start = CoroutineStart.UNDISPATCHED) { session.takeStartupUpdate() }
        firstActivity.cancelAndJoin()

        response.complete(release())
        assertEquals(release(), session.takeStartupUpdate())
        assertNull(session.takeStartupUpdate())
        assertEquals(1, checks)
    }

    @Test(timeout = 5_000)
    fun manualCheckJoinsStartupAndCanConsumeItsPrompt() = runBlocking {
        val response = CompletableDeferred<UpdateChecker.Result>()
        var checks = 0
        val session = AppUpdateSession(this) {
            checks++
            response.await()
        }
        val main = async(start = CoroutineStart.UNDISPATCHED) { session.takeStartupUpdate() }
        main.cancelAndJoin()
        val manual = async(start = CoroutineStart.UNDISPATCHED) { session.checkManually() }

        response.complete(release())
        assertEquals(release(), manual.await().getOrThrow())
        assertEquals(1, checks)
        session.markManualResultShown()
        assertNull(session.takeStartupUpdate())

        assertEquals(release(), session.checkManually().getOrThrow())
        assertEquals(2, checks)
    }

    @Test(timeout = 5_000)
    fun startupIgnoresNoUpdateAndFailuresButManualCheckReceivesThem() = runBlocking {
        val noUpdate = release(newer = false)
        val upToDate = AppUpdateSession(this) { noUpdate }
        assertNull(upToDate.takeStartupUpdate())
        assertEquals(noUpdate, upToDate.checkManually().getOrThrow())

        val offline = IOException("No network")
        var checks = 0
        val unavailable = AppUpdateSession(this) {
            checks++
            throw offline
        }
        assertNull(unavailable.takeStartupUpdate())
        assertNull(unavailable.takeStartupUpdate())
        assertEquals(1, checks)
        assertEquals(offline, unavailable.checkManually().exceptionOrNull())
        assertEquals(2, checks)
    }

    private fun release(newer: Boolean = true) = UpdateChecker.Result(
        tagName = "v0.5.1",
        versionName = "0.5.1",
        releaseName = "v0.5.1",
        body = "",
        htmlUrl = "",
        apkUrl = null,
        apkName = null,
        apkSize = 0,
        isNewer = newer,
    )
}
