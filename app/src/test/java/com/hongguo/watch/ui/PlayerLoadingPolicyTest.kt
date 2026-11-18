// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.watch.ui

import com.hongguo.watch.upstream.StreamFailover
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerLoadingPolicyTest {
    @Test
    fun initial_beforeFirstFrame() {
        assertEquals(
            PlayerLoadingPolicy.Phase.INITIAL,
            PlayerLoadingPolicy.phase(firstFrameRendered = false, bufferingElapsedMs = 0L),
        )
        assertEquals(
            PlayerLoadingPolicy.Phase.INITIAL,
            PlayerLoadingPolicy.phase(firstFrameRendered = false, bufferingElapsedMs = 60_000L),
        )
    }

    @Test
    fun rebuffer_afterFirstFrame_beforeProlonged() {
        assertEquals(
            PlayerLoadingPolicy.Phase.REBUFFER,
            PlayerLoadingPolicy.phase(
                firstFrameRendered = true,
                bufferingElapsedMs = PlayerLoadingPolicy.INDICATOR_DELAY_MS,
            ),
        )
        assertEquals(
            PlayerLoadingPolicy.Phase.REBUFFER,
            PlayerLoadingPolicy.phase(
                firstFrameRendered = true,
                bufferingElapsedMs = PlayerLoadingPolicy.PROLONGED_HINT_MS - 1L,
            ),
        )
    }

    @Test
    fun prolonged_afterThreshold() {
        assertEquals(
            PlayerLoadingPolicy.Phase.REBUFFER_PROLONGED,
            PlayerLoadingPolicy.phase(
                firstFrameRendered = true,
                bufferingElapsedMs = PlayerLoadingPolicy.PROLONGED_HINT_MS,
            ),
        )
    }

    @Test
    fun nextSeason_overrides() {
        assertEquals(
            PlayerLoadingPolicy.Phase.NEXT_SEASON,
            PlayerLoadingPolicy.phase(
                firstFrameRendered = true,
                bufferingElapsedMs = PlayerLoadingPolicy.PROLONGED_HINT_MS,
                nextSeason = true,
            ),
        )
    }

    @Test
    fun thresholds_orderedAgainstStallFailover() {
        assertTrue(PlayerLoadingPolicy.INDICATOR_DELAY_MS > 0L)
        assertTrue(
            PlayerLoadingPolicy.PROLONGED_HINT_MS > PlayerLoadingPolicy.INDICATOR_DELAY_MS,
        )
        assertTrue(
            PlayerLoadingPolicy.PROLONGED_HINT_MS < StreamFailover.STALL_TIMEOUT_MS,
        )
    }
}
