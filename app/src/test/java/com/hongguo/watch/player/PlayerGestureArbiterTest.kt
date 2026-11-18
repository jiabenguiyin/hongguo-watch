package com.hongguo.watch.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerGestureArbiterTest {
    private val slop = 16f

    @Test
    fun longPressSurvivesTinyJitter() {
        assertFalse(PlayerGestureArbiter.shouldCancelLongPress(4f, 3f, slop))
        assertFalse(PlayerGestureArbiter.shouldClaimSwipe(4f, 3f, slop))
    }

    @Test
    fun movePastTouchSlopCancelsLongPressBeforeSwipeClaim() {
        // ≈ 18px：大于 cancel(16) 小于 claim(24)
        assertTrue(PlayerGestureArbiter.shouldCancelLongPress(18f, 0f, slop))
        assertFalse(PlayerGestureArbiter.shouldClaimSwipe(18f, 0f, slop))
    }

    @Test
    fun largerMoveClaimsSwipe() {
        assertTrue(PlayerGestureArbiter.shouldClaimSwipe(30f, 5f, slop))
        assertTrue(PlayerGestureArbiter.shouldCancelLongPress(30f, 5f, slop))
    }

    @Test
    fun dominantAxisIsExclusive() {
        assertEquals(
            PlayerGestureArbiter.Axis.HORIZONTAL,
            PlayerGestureArbiter.dominantAxis(40f, 10f),
        )
        assertEquals(
            PlayerGestureArbiter.Axis.VERTICAL,
            PlayerGestureArbiter.dominantAxis(10f, -40f),
        )
        // 对角接近：取较大绝对值轴，避免双认领
        assertEquals(
            PlayerGestureArbiter.Axis.HORIZONTAL,
            PlayerGestureArbiter.dominantAxis(30f, 29f),
        )
    }

    @Test
    fun multiTouchFlag() {
        assertFalse(PlayerGestureArbiter.isMultiTouch(1))
        assertTrue(PlayerGestureArbiter.isMultiTouch(2))
    }
}
