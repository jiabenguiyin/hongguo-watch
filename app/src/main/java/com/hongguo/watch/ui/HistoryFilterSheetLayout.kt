package com.hongguo.watch.ui

/**
 * 历史筛选底栏几何：纯计算，便于单测。
 * 顶边留白对应旧实现约 12% 屏高（即内容区约 88%），避免首帧被 Behavior fit-to-contents 裁切。
 */
object HistoryFilterSheetLayout {
    fun expandedTopOffsetPx(screenHeightPx: Int): Int {
        val h = screenHeightPx.coerceAtLeast(1)
        return (h * 0.12f).toInt().coerceIn(0, (h * 0.25f).toInt())
    }
}
