package com.hongguo.watch.ui

import kotlin.math.roundToInt

/**
 * 手表更新弹窗几何：纯计算，便于单测（CD12 Max 等方屏大圆角）。
 */
object WatchDialogLayout {
    /** 内容区宽度 = 屏宽 − 两侧圆角安全边。 */
    fun contentWidthPx(screenWidthPx: Int, edgeInsetPx: Int, minWidthPx: Int): Int {
        val width = screenWidthPx.coerceAtLeast(1)
        return (width - edgeInsetPx * 2).coerceIn(minWidthPx.coerceIn(1, width), width)
    }

    /**
     * 更新说明最大高度：屏高减去标题/元信息/三键预留，且不超过屏高约 42%。
     */
    fun notesMaxHeightPx(
        screenHeightPx: Int,
        reservedPx: Int,
        minHeightPx: Int,
    ): Int {
        val byReserve = screenHeightPx - reservedPx
        val byRatio = (screenHeightPx * 0.42f).roundToInt()
        return byReserve.coerceIn(minHeightPx, byRatio.coerceAtLeast(minHeightPx))
    }
}
