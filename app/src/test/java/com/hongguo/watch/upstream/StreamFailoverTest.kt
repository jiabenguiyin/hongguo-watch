package com.hongguo.watch.upstream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamFailoverTest {
    private fun r(q: Int, codec: String) =
        Rendition(q, codec, 854, 480, "https://example.test/$q-$codec", null)

    @Test
    fun afterHighAvcFailsPicksLowerAvc() {
        val ladder = listOf(
            r(720, "h264"),
            r(480, "h264"),
            r(360, "h264"),
            r(720, "bytevc1"),
        )
        val first = StreamFailover.pick(ladder, 720, emptySet())
        assertNotNull(first)
        assertEquals(720, first!!.quality)
        assertTrue(first.isAvc)

        val next = StreamFailover.pick(
            ladder,
            720,
            setOf(StreamFailover.key(first)),
        )
        assertNotNull(next)
        assertEquals(480, next!!.quality)
        assertTrue(next.isAvc)
    }

    @Test
    fun afterAllAvcFailsFallsBackToHevcWhenAllowed() {
        val ladder = listOf(
            r(480, "h264"),
            r(360, "h264"),
            r(480, "bytevc1"),
        )
        val failed = ladder.filter { it.isAvc }.map { StreamFailover.key(it) }.toSet()
        val next = StreamFailover.pick(
            ladder,
            480,
            failed,
            preferAvc = true,
            avcOnly = false,
        )
        assertNotNull(next)
        assertTrue(next!!.isHevc)
        assertTrue(StreamFailover.shouldPreferIjkSoft(next))
    }

    @Test
    fun avcOnlyNeverReturnsHevcEvenAfterAvcExhausted() {
        val ladder = listOf(r(480, "h264"), r(480, "bytevc1"))
        val failed = setOf(StreamFailover.key(ladder.first()))
        assertNull(
            StreamFailover.pick(
                ladder,
                480,
                failed,
                preferAvc = true,
                avcOnly = true,
            ),
        )
        assertFalse(
            StreamFailover.hasCandidate(
                ladder,
                480,
                failed,
                avcOnly = true,
            ),
        )
    }

    @Test
    fun respectsUserMaxQualityCap() {
        val ladder = listOf(r(1080, "h264"), r(720, "h264"), r(480, "h264"))
        val picked = StreamFailover.pick(ladder, 480, emptySet())
        assertNotNull(picked)
        assertEquals(480, picked!!.quality)
    }

    @Test
    fun describeIsWatchSafeShort() {
        assertEquals("480p · h264", StreamFailover.describe(r(480, "h264")))
    }

    @Test
    fun maxAttemptsIsFinite() {
        assertTrue(StreamFailover.MAX_ATTEMPTS in 2..6)
        assertTrue(StreamFailover.STALL_TIMEOUT_MS >= 8_000L)
    }
}
