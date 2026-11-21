package com.hongguo.watch.ui

import com.hongguo.watch.data.model.CatalogPage
import com.hongguo.watch.data.model.Series
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedPagingPolicyTest {
    @Test
    fun slowNextPageDoesNotBlockShowingTheFirstOrStartADuplicateRequest() = runBlocking {
        val first = CatalogPage(listOf(Series("1", "one")), page = 1, hasMore = true, nextOffset = 4, sessionId = "session")
        val next = CatalogPage(listOf(Series("2", "two")), page = 2, nextOffset = 9)
        val requested = CompletableDeferred<CatalogPage>()
        val deliverNext = CompletableDeferred<CatalogPage>()
        val shownFirst = CompletableDeferred<CatalogPage>()
        val displayed = mutableListOf<Pair<CatalogPage, Boolean>>()
        var requests = 0

        withTimeout(3_000) {
            val load = launch {
                assertFalse(FeedPagingPolicy.showRecommendationStart(first, waitMs = 200, fetchNext = {
                    requests++
                    requested.complete(it)
                    deliverNext.await()
                }) { page, append ->
                    displayed += page to append
                    if (!append) shownFirst.complete(page)
                })
            }
            assertEquals(first, requested.await())
            assertEquals(first, shownFirst.await())
            assertEquals(listOf(first to false), displayed)
            deliverNext.complete(next)
            load.join()
        }
        assertEquals(1, requests)
        assertEquals(listOf(first to false, next to true), displayed)
    }

    @Test
    fun initialBatchKeepsOrderAndLatestCursorWithoutDuplicates() {
        val first = CatalogPage(
            items = listOf(Series("1", "one"), Series("2", "two")),
            page = 1, hasMore = true, nextOffset = 2, sessionId = "session",
        )
        val second = CatalogPage(
            items = listOf(Series("2", "two"), Series("3", "three")),
            page = 2, hasMore = true, nextOffset = 4,
        )
        val combined = FeedPagingPolicy.mergeInitialPage(first, second)
        assertEquals(listOf("1", "2", "3"), combined.items.map { it.id })
        assertEquals(2, combined.page)
        assertEquals(4, combined.nextOffset)
        assertEquals("session", combined.sessionId)
    }

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
