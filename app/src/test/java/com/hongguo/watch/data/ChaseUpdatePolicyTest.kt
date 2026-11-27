package com.hongguo.watch.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChaseUpdatePolicyTest {
    @Test
    fun updateOnlyWhenSeededAndCountGrew() {
        // last_serial_count 语义：未种子不报更新
        assertFalse(ChaseUpdatePolicy.hasUpdate(80, 0, seeded = false))
        assertFalse(ChaseUpdatePolicy.hasUpdate(80, 80, seeded = true))
        assertFalse(ChaseUpdatePolicy.hasUpdate(79, 80, seeded = true))
        assertTrue(ChaseUpdatePolicy.hasUpdate(81, 80, seeded = true))
    }

    @Test
    fun deltaNeverNegative() {
        assertEquals(0, ChaseUpdatePolicy.newEpisodeDelta(10, 12))
        assertEquals(3, ChaseUpdatePolicy.newEpisodeDelta(15, 12))
    }

    @Test
    fun progressLabelMatchesOfficialStyle() {
        assertEquals("7集/130集", ChaseUpdatePolicy.progressLabel(7, 130))
        assertEquals("全80集", ChaseUpdatePolicy.progressLabel(null, 80))
        assertEquals("看到第3集", ChaseUpdatePolicy.progressLabel(3, 0))
        assertEquals("", ChaseUpdatePolicy.progressLabel(null, 0))
    }
}
