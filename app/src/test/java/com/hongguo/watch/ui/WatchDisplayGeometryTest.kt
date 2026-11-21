package com.hongguo.watch.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchDisplayGeometryTest {
    @Test
    fun fallbackKeepsFixedBarsInsideUnreportedWatchCorners() {
        val screen = 450
        val radius = WatchDisplayGeometry.fallbackCornerRadius(screen, screen, round = false, compact = true)
        val vertical = WatchDisplayGeometry.barVerticalInset(23, screen, radius, automatic = true)
        val side = WatchDisplayGeometry.cornerSideInset(radius, vertical)
        val dx = radius - side
        val dy = radius - vertical

        assertTrue(dx * dx + dy * dy <= radius * radius)
        assertTrue(side > 23)
        assertTrue(screen - 2 * side >= 270)
        assertEquals(23, WatchDisplayGeometry.barVerticalInset(23, screen, radius, automatic = false))
        assertEquals(0, WatchDisplayGeometry.fallbackCornerRadius(450, 800, round = false, compact = true))
    }

    @Test
    fun compactRectangleUsesSmallerAutomaticInsetsThanRoundScreen() {
        val rectangle = calculate(round = false, compact = true)
        val round = calculate(round = true, compact = true)

        assertEquals(18, rectangle.horizontal)
        assertEquals(48, round.horizontal)
        assertEquals(17, rectangle.top)
        assertEquals(48, round.top)
    }

    @Test
    fun manualInsetsAreIndependentAndUseFullTopMargin() {
        val insets = calculate(horizontal = 5, vertical = 10)

        assertEquals(20, insets.horizontal)
        assertEquals(40, insets.vertical)
        assertEquals(40, insets.top)
    }

    @Test
    fun manualInsetsLeaveMinimumRoomForContent() {
        val insets = WatchDisplayGeometry.preferredInsets(
            widthPx = 200,
            heightPx = 140,
            platformDensity = 1f,
            layoutDensity = 1f,
            round = false,
            compact = true,
            horizontalPercent = 20,
            verticalPercent = 20,
        )

        assertEquals(12, insets.horizontal)
        assertEquals(10, insets.vertical)
    }

    private fun calculate(
        round: Boolean = false,
        compact: Boolean = true,
        horizontal: Int = -1,
        vertical: Int = -1,
    ) = WatchDisplayGeometry.preferredInsets(
        widthPx = 400,
        heightPx = 400,
        platformDensity = 1f,
        layoutDensity = 1f,
        round = round,
        compact = compact,
        horizontalPercent = horizontal,
        verticalPercent = vertical,
    )
}
