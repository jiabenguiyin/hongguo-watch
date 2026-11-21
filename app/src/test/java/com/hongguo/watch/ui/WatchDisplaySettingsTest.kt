package com.hongguo.watch.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class WatchDisplaySettingsTest {
    @Test
    fun maximumScaleMatchesAvailableWidthOnHighDensityWatch() {
        assertEquals(105, WatchDisplaySettings.maximumScalePercent(320, 408))
        assertEquals(336, WatchDisplaySettings(scalePercent = 105).effectiveDensity(320, 408))
        assertEquals(340, WatchDisplaySettings(scalePercent = 110).effectiveDensity(320, 408))
    }

    @Test
    fun maximumScaleAllowsDefaultOnAlreadyNarrowDeviceAndFullRangeOnWideDevice() {
        assertEquals(100, WatchDisplaySettings.maximumScalePercent(320, 320))
        assertEquals(145, WatchDisplaySettings.maximumScalePercent(160, 400))
        assertEquals(145, WatchDisplaySettings.maximumScalePercent(0, 0))
    }
}
