package com.hongguo.watch.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpisodeWheelTest {
    @Test
    fun systemScrollBridgeMapsBothDirectionsAndIgnoresSmallMotion() {
        assertEquals(1, EpisodeScrollBridge.scrollDirection(140, thresholdPx = 100))
        assertEquals(-1, EpisodeScrollBridge.scrollDirection(-140, thresholdPx = 100))
        assertNull(EpisodeScrollBridge.scrollDirection(20, thresholdPx = 100))
        assertNull(EpisodeScrollBridge.scrollDirection(-20, thresholdPx = 100))
    }

    @Test
    fun frameworkEncoderCallbackMapsBothDirections() {
        assertEquals(1, EpisodeWheel.frameworkScrollStep(50))
        assertEquals(-1, EpisodeWheel.frameworkScrollStep(-50))
        assertNull(EpisodeWheel.frameworkScrollStep(0))
    }

    @Test
    fun registrationWarmupAndZeroDirectionAreHandled() {
        var now = 1_000_000_000L
        val filter = EpisodeWheel.StepFilter(900_000_000L, nowNanos = { now })

        now = 999_000_000L
        assertNull(filter.step(floatArrayOf(0f, 1f)))
        now = 1_000_000_000L
        assertEquals(-1, filter.step(floatArrayOf(0f, 0f)))
        now += 301_000_000L
        assertNull(filter.step(floatArrayOf(1f),))
        now += 301_000_000L
        assertNull(filter.step(floatArrayOf(0f, Float.NaN)))
        now += 301_000_000L
        assertEquals(1, filter.step(floatArrayOf(0f, 1f)))
    }

    @Test
    fun repeatedPulsesChangeOneEpisodeAndASeparateSwipeCanGoBack() {
        var now = 100_000_000L
        val filter = EpisodeWheel.StepFilter(0L, nowNanos = { now })
        val forward = floatArrayOf(0f, 3f)
        val backward = floatArrayOf(0f, -3f)

        assertEquals(1, filter.step(forward))
        now += 299_000_000L
        assertNull(filter.step(forward))
        now += 1_000_000L
        assertEquals(1, filter.step(forward))
        now += 1_000_000L
        assertNull(filter.step(backward))
        now += 300_000_000L
        assertEquals(-1, filter.step(backward))
    }
}
