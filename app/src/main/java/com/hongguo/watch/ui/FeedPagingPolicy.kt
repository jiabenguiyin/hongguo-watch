package com.hongguo.watch.ui

import com.hongguo.watch.data.model.CatalogPage
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 手表首页推荐/榜单分页策略：服务端常一次只给几条，
 * 用首屏连拉 + 提前预取让小屏尽快可滑，又不过度囤页。
 */
object FeedPagingPolicy {
    private const val INITIAL_BATCH_WAIT_MS = 650L
    const val INITIAL_BATCH_TARGET = 8
    private const val INITIAL_BATCH_MAX_PAGES = 3

    fun mergeInitialPage(previous: CatalogPage, next: CatalogPage): CatalogPage =
        next.copy(
            items = (previous.items + next.items).distinctBy { it.id },
            nextOffset = next.nextOffset.coerceAtLeast(previous.nextOffset),
            sessionId = next.sessionId.ifBlank { previous.sessionId },
        )

    /** Show a useful first batch, then deliver an unfinished request without starting it again. */
    internal suspend fun showRecommendationStart(
        first: CatalogPage,
        waitMs: Long = INITIAL_BATCH_WAIT_MS,
        fetchNext: suspend (CatalogPage) -> CatalogPage,
        show: (page: CatalogPage, append: Boolean) -> Unit,
    ): Boolean = supervisorScope {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMs)
        var page = first
        repeat(INITIAL_BATCH_MAX_PAGES) {
            if (!page.hasMore || page.items.size >= INITIAL_BATCH_TARGET) {
                show(page, false)
                return@supervisorScope page.hasMore
            }
            val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (remaining <= 0) {
                show(page, false)
                return@supervisorScope true
            }
            val previous = page
            val request = async {
                try {
                    fetchNext(previous)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            }
            val received = withTimeoutOrNull(remaining) { request.await() }
            if (received == null) {
                show(previous, false)
                val late = request.await() ?: return@supervisorScope false
                show(late, true)
                return@supervisorScope late.hasMore && late.nextOffset > previous.nextOffset
            }
            page = mergeInitialPage(previous, received)
            if (received.nextOffset <= previous.nextOffset) {
                show(page.copy(hasMore = false), false)
                return@supervisorScope false
            }
        }
        show(page, false)
        page.hasMore
    }

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
