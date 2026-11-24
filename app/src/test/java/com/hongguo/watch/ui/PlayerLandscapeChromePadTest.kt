package com.hongguo.watch.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 横屏底栏左右需清圆角，但不得全量角弧内收造成巨大空沟。 */
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

    @Test
    fun landscapeBottomPad_capsHugeCornerInset() {
        val sidePad = 16
        val hugeArc = 98
        val capped = WatchDisplayGeometry.bottomChromeSidePad(
            sidePad = sidePad,
            cornerSideInset = hugeArc,
            landscape = true,
            cornerSafe = true,
            landscapeCapPx = 18,
            portraitCapPx = 20,
        )
        assertEquals(18, capped)
        assertTrue(capped < hugeArc)
        assertTrue(capped >= sidePad)
    }

    @Test
    fun landscapeBottomPad_keepsSidePadWhenLargerThanCap() {
        val pad = WatchDisplayGeometry.bottomChromeSidePad(
            sidePad = 24,
            cornerSideInset = 10,
            landscape = true,
            cornerSafe = true,
            landscapeCapPx = 18,
            portraitCapPx = 20,
        )
        assertEquals(24, pad)
    }

    @Test
    fun nonCornerPortrait_usesSidePadOnly() {
        val pad = WatchDisplayGeometry.bottomChromeSidePad(
            sidePad = 12,
            cornerSideInset = 80,
            landscape = false,
            cornerSafe = false,
            landscapeCapPx = 18,
            portraitCapPx = 20,
        )
        assertEquals(12, pad)
    }
}
