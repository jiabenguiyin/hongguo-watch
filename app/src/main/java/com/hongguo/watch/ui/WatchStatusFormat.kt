// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.watch.ui

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * 手表状态条纯格式化：顶栏 HH:mm、播放器电量百分比。
 * 无 Context，便于单测。
 */
object WatchStatusFormat {
    /** 本地时区 24 小时制 HH:mm。 */
    fun formatClock(
        epochMillis: Long,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): String {
        val cal = Calendar.getInstance(timeZone)
        cal.timeInMillis = epochMillis
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        return String.format(Locale.US, "%02d:%02d", hour, minute)
    }

    /**
     * sticky [android.content.Intent.ACTION_BATTERY_CHANGED] 的 level/scale → 0..100。
     * scale≤0 时按 100 兜底。
     */
    fun batteryPercent(level: Int, scale: Int): Int {
        val safeScale = if (scale > 0) scale else 100
        val pct = ((level.toLong() * 100L) / safeScale.toLong()).toInt()
        return pct.coerceIn(0, 100)
    }

    fun formatBatteryPercent(percent: Int): String = "${percent.coerceIn(0, 100)}%"
}
