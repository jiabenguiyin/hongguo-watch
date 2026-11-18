// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.watch.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenOffPlaybackPolicyTest {
    @Test
    fun defaultContinue_isOff_forBattery() {
        assertFalse(ScreenOffPlaybackPolicy.DEFAULT_CONTINUE_ENABLED)
    }

    @Test
    fun continueWhenStopping_requiresEnabledPlayingAndNotFinishing() {
        assertTrue(
            ScreenOffPlaybackPolicy.shouldContinueWhenStopping(
                continueEnabled = true,
                isFinishing = false,
                isActivelyPlaying = true,
            ),
        )
        assertFalse(
            ScreenOffPlaybackPolicy.shouldContinueWhenStopping(
                continueEnabled = false,
                isFinishing = false,
                isActivelyPlaying = true,
            ),
        )
        assertFalse(
            ScreenOffPlaybackPolicy.shouldContinueWhenStopping(
                continueEnabled = true,
                isFinishing = true,
                isActivelyPlaying = true,
            ),
        )
        assertFalse(
            ScreenOffPlaybackPolicy.shouldContinueWhenStopping(
                continueEnabled = true,
                isFinishing = false,
                isActivelyPlaying = false,
            ),
        )
    }

    @Test
    fun keepScreenOn_onlyForegroundPlaying() {
        assertTrue(ScreenOffPlaybackPolicy.shouldKeepScreenOn(true, true))
        assertFalse(ScreenOffPlaybackPolicy.shouldKeepScreenOn(true, false))
        assertFalse(ScreenOffPlaybackPolicy.shouldKeepScreenOn(false, true))
        assertFalse(ScreenOffPlaybackPolicy.shouldKeepScreenOn(false, false))
    }

    @Test
    fun partialWakeLock_onlyBackgroundContinuePlaying() {
        assertTrue(
            ScreenOffPlaybackPolicy.shouldHoldPartialWakeLock(
                continueEnabled = true,
                foreground = false,
                isActivelyPlaying = true,
            ),
        )
        assertFalse(
            ScreenOffPlaybackPolicy.shouldHoldPartialWakeLock(
                continueEnabled = false,
                foreground = false,
                isActivelyPlaying = true,
            ),
        )
        assertFalse(
            ScreenOffPlaybackPolicy.shouldHoldPartialWakeLock(
                continueEnabled = true,
                foreground = true,
                isActivelyPlaying = true,
            ),
        )
        assertFalse(
            ScreenOffPlaybackPolicy.shouldHoldPartialWakeLock(
                continueEnabled = true,
                foreground = false,
                isActivelyPlaying = false,
            ),
        )
    }
}
