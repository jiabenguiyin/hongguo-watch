package com.hongguo.watch.ui

/**
 * 手表首页推荐/榜单分页策略：服务端常一次只给几条，
 * 用首屏连拉 + 提前预取让小屏尽快可滑，又不过度囤页。
 */
object FeedPagingPolicy {
    /** 首屏自动连拉目标条数（不含 Header）。 */
    const val INITIAL_FILL_TARGET = 24

    /** 首屏最多连拉页数，防止弱网卡死。 */
    const val INITIAL_FILL_MAX_PAGES = 8

    /**
     * 距列表末尾还剩多少项时触发续页。
     * 越大越早预取；手表可视约 3～5 行，8 可提前约 1～2 屏。
     */
    const val PREFETCH_REMAINING = 8

    fun shouldPrefetch(lastVisiblePosition: Int, itemCount: Int): Boolean {
        if (itemCount <= 0 || lastVisiblePosition < 0) return false
        return lastVisiblePosition >= itemCount - PREFETCH_REMAINING
    }

    fun shouldContinueInitialFill(currentItems: Int, hasMore: Boolean, pagesFetched: Int): Boolean {
        return hasMore && currentItems < INITIAL_FILL_TARGET && pagesFetched < INITIAL_FILL_MAX_PAGES
    }
}
