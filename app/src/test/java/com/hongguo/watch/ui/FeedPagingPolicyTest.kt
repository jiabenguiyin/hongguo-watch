package com.hongguo.watch.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedPagingPolicyTest {
    @Test
    fun prefetchTriggersBeforeEnd() {
        assertTrue(FeedPagingPolicy.shouldPrefetch(lastVisiblePosition = 10, itemCount = 16))
        assertTrue(FeedPagingPolicy.shouldPrefetch(lastVisiblePosition = 15, itemCount = 16))
        assertFalse(FeedPagingPolicy.shouldPrefetch(lastVisiblePosition = 4, itemCount = 16))
        assertFalse(FeedPagingPolicy.shouldPrefetch(lastVisiblePosition = -1, itemCount = 16))
    }

    @Test
    fun initialFillStopsAtTargetOrCap() {
        assertTrue(
            FeedPagingPolicy.shouldContinueInitialFill(
                currentItems = 6,
                hasMore = true,
                pagesFetched = 1,
            ),
        )
        assertFalse(
            FeedPagingPolicy.shouldContinueInitialFill(
                currentItems = FeedPagingPolicy.INITIAL_FILL_TARGET,
                hasMore = true,
                pagesFetched = 2,
            ),
        )
        assertFalse(
            FeedPagingPolicy.shouldContinueInitialFill(
                currentItems = 10,
                hasMore = true,
                pagesFetched = FeedPagingPolicy.INITIAL_FILL_MAX_PAGES,
            ),
        )
        assertFalse(
            FeedPagingPolicy.shouldContinueInitialFill(
                currentItems = 3,
                hasMore = false,
                pagesFetched = 1,
            ),
        )
    }
}
