package com.hongguo.watch.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

class WatchStatusFormatTest {
    @Test
    fun formatClock_usesHHmmInGivenZone() {
        // 2024-01-15 20:47:30 Asia/Shanghai
        val tz = TimeZone.getTimeZone("Asia/Shanghai")
        val millis = 1_705_322_850_000L
        assertEquals("20:47", WatchStatusFormat.formatClock(millis, tz))
    }

    @Test
    fun formatClock_padsSingleDigitHourAndMinute() {
        val tz = TimeZone.getTimeZone("UTC")
        // 2024-01-15 01:05:00 UTC
        val millis = 1_705_280_700_000L
        assertEquals("01:05", WatchStatusFormat.formatClock(millis, tz))
    }

    @Test
    fun batteryPercent_scalesAndClamps() {
        assertEquals(85, WatchStatusFormat.batteryPercent(85, 100))
        assertEquals(50, WatchStatusFormat.batteryPercent(50, 100))
        assertEquals(100, WatchStatusFormat.batteryPercent(200, 100))
        assertEquals(0, WatchStatusFormat.batteryPercent(-5, 100))
        assertEquals(50, WatchStatusFormat.batteryPercent(1, 2))
        assertEquals(0, WatchStatusFormat.batteryPercent(0, 0))
    }

    @Test
    fun formatBatteryPercent_appendsSuffix() {
        assertEquals("85%", WatchStatusFormat.formatBatteryPercent(85))
        assertEquals("0%", WatchStatusFormat.formatBatteryPercent(-1))
        assertEquals("100%", WatchStatusFormat.formatBatteryPercent(120))
    }
}
