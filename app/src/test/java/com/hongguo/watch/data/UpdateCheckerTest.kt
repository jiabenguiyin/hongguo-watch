package com.hongguo.watch.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    @Test
    fun normalizeStripsVPrefix() {
        assertEquals("0.4.2", UpdateChecker.normalizeVersionName("v0.4.2"))
        assertEquals("0.4.1", UpdateChecker.normalizeVersionName("0.4.1"))
    }

    @Test
    fun newerBySemver() {
        assertTrue(UpdateChecker.isNewer("0.4.2", "0.4.1", localCode = 7))
        assertFalse(UpdateChecker.isNewer("0.4.1", "0.4.1", localCode = 7))
        assertFalse(UpdateChecker.isNewer("0.4.0", "0.4.1", localCode = 7))
    }
}
