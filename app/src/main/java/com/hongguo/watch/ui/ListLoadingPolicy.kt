package com.hongguo.watch.ui

/**
 * 首页列表加载指示器：同一时刻只允许一种。
 * 避免中心 ProgressBar 与 SwipeRefreshLayout 圈叠在一起。
 */
object ListLoadingPolicy {
    enum class Indicator {
        /** 无指示器 */
        NONE,
        /** 空列表 / 切分类：中心转圈 */
        CENTER,
        /** 用户下拉刷新：只用 SwipeRefresh 圈 */
        REFRESH,
    }

    /**
     * @param swipeRefreshing 用户已触发下拉刷新（SwipeRefresh 已进入 refreshing）
     * @param clearingList 本次会清空 adapter（切 Tab / 强制刷新 / 换分类）
     * @param hasVisibleContent 当前列表是否已有可见行
     */
    fun choose(
        swipeRefreshing: Boolean,
        clearingList: Boolean,
        hasVisibleContent: Boolean,
    ): Indicator = when {
        swipeRefreshing -> Indicator.REFRESH
        clearingList || !hasVisibleContent -> Indicator.CENTER
        // 软刷新（如 onResume 已有内容）：不闪中心圈，避免叠在列表上
        else -> Indicator.NONE
    }

    /** 首屏内容已写入列表后，中心遮罩必须立刻关掉。 */
    fun afterContentShown(current: Indicator): Indicator =
        if (current == Indicator.CENTER) Indicator.NONE else current
}
