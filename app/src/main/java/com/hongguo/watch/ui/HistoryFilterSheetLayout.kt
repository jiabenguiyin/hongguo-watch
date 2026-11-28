package com.hongguo.watch.ui

/**
 * 历史筛选全屏面板几何（纯计算，便于单测）。
 *
 * 面板本身走与显示设置相同的 [WatchUi.applySafePadding]；
 * 这里只校验「可滚内容必须停在固定底栏之上」——即 LinearLayout 里
 * Scroll(weight=1) 与 bottomChrome 为兄弟时，内容区高度 = 屏高 − 顶栏 − 底栏 − 安全边。
 */
object HistoryFilterSheetLayout {
    /**
     * 可滚内容区高度：屏高减去顶/底 chrome 与安全边后必须为正，
     * 保证芯片不会落到固定「清空/确定」下面。
     */
    fun scrollViewportHeightPx(
        screenHeightPx: Int,
        topChromePx: Int,
        bottomChromePx: Int,
        topSafePx: Int,
        bottomSafePx: Int,
    ): Int {
        val h = screenHeightPx.coerceAtLeast(1)
        return (
            h - topChromePx.coerceAtLeast(0) -
                bottomChromePx.coerceAtLeast(0) -
                topSafePx.coerceAtLeast(0) -
                bottomSafePx.coerceAtLeast(0)
            ).coerceAtLeast(0)
    }
}
