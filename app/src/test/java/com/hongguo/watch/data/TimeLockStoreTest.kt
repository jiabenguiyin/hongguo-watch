package com.hongguo.watch.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeLockStoreTest {
    @Test
    fun pin_mustBeFourDigits() {
        assertTrue(TimeLockStore.isValidPin("1234"))
        assertFalse(TimeLockStore.isValidPin("12"))
        assertFalse(TimeLockStore.isValidPin("12345"))
        assertFalse(TimeLockStore.isValidPin("12a4"))
    }

    @Test
    fun limit_snapsToKnownOptions() {
        assertEquals(30, TimeLockStore.normalizeLimit(25))
        assertEquals(60, TimeLockStore.normalizeLimit(70))
        assertEquals(120, TimeLockStore.normalizeLimit(130))
        assertEquals(240, TimeLockStore.normalizeLimit(400))
    }

    @Test
    fun formatLimit_isChineseWatchFriendly() {
        assertEquals("30 分钟", TimeLockStore.formatLimitLabel(30))
        assertEquals("1 小时", TimeLockStore.formatLimitLabel(60))
        assertEquals("2 小时", TimeLockStore.formatLimitLabel(120))
    }
}
