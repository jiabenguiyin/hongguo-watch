package com.hongguo.watch.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListTabPolicyTest {
    @Test
    fun clearWhenSwitchingFromHistoryOrFavorites() {
        assertTrue(ListTabPolicy.shouldClearHomeAdapter(switchingToHome = true, forceReload = false))
    }

    @Test
    fun clearOnForceReloadEvenIfAlreadyHome() {
        assertTrue(ListTabPolicy.shouldClearHomeAdapter(switchingToHome = false, forceReload = true))
    }

    @Test
    fun keepHomeRowsOnSoftResume() {
        // 已在首页 onResume 再进 showHome：不必先闪空列表
        assertFalse(ListTabPolicy.shouldClearHomeAdapter(switchingToHome = false, forceReload = false))
    }
}
