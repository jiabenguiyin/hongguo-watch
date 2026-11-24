package com.hongguo.watch.ui

import org.junit.Assert.assertTrue
import org.junit.Test

/** 横屏底栏左右需大于普通侧边，避免圆角裁切「选集 / 倍速」。 */
class PlayerLandscapeChromePadTest {
    @Test
    fun cornerSideInset_growsNearBottomArc() {
        val radius = 160
        val bottomSafe = 20
        val arc = WatchDisplayGeometry.cornerSideInset(radius, bottomSafe)
        assertTrue("arc inset should clear corner at bottom row", arc > 20)
        assertTrue(arc < radius)
    }

    @Test
    fun compactSquare_fallbackRadius_isLarge() {
        val radius = WatchDisplayGeometry.fallbackCornerRadius(
            widthPx = 400,
            heightPx = 400,
            round = false,
            compact = true,
        )
        assertTrue(radius >= 160)
    }
}
