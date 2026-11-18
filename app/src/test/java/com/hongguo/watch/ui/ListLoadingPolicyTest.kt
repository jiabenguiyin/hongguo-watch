package com.hongguo.watch.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ListLoadingPolicyTest {
    @Test
    fun pullToRefreshUsesSwipeOnly() {
        assertEquals(
            ListLoadingPolicy.Indicator.REFRESH,
            ListLoadingPolicy.choose(
                swipeRefreshing = true,
                clearingList = true,
                hasVisibleContent = true,
            ),
        )
    }

    @Test
    fun emptyInitialLoadUsesCenter() {
        assertEquals(
            ListLoadingPolicy.Indicator.CENTER,
            ListLoadingPolicy.choose(
                swipeRefreshing = false,
                clearingList = true,
                hasVisibleContent = false,
            ),
        )
    }

    @Test
    fun softResumeWithContentShowsNone() {
        assertEquals(
            ListLoadingPolicy.Indicator.NONE,
            ListLoadingPolicy.choose(
                swipeRefreshing = false,
                clearingList = false,
                hasVisibleContent = true,
            ),
        )
    }

    @Test
    fun afterContentHidesCenterOverlay() {
        assertEquals(
            ListLoadingPolicy.Indicator.NONE,
            ListLoadingPolicy.afterContentShown(ListLoadingPolicy.Indicator.CENTER),
        )
        assertEquals(
            ListLoadingPolicy.Indicator.REFRESH,
            ListLoadingPolicy.afterContentShown(ListLoadingPolicy.Indicator.REFRESH),
        )
    }
}
