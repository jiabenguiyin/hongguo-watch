package com.hongguo.watch.data

import com.hongguo.watch.data.model.CatalogPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 分页游标字段契约：续页依赖 nextOffset / sessionId / hasMore。 */
class RecommendPaginationLogicTest {
    @Test
    fun catalogPageCarriesCursor() {
        val page = CatalogPage(
            items = emptyList(),
            page = 2,
            hasMore = true,
            nextOffset = 24,
            sessionId = "abc",
        )
        assertTrue(page.hasMore)
        assertEquals(24, page.nextOffset)
        assertEquals("abc", page.sessionId)
    }
}
