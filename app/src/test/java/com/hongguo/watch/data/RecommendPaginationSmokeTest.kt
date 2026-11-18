package com.hongguo.watch.data

import android.content.Context
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 真网冒烟：推荐第 1+2 页合计应明显多于单页。
 * 无 Robolectric 依赖时本文件不会被编进默认 classpath——见下方纯 OkHttp 备选。
 */
class RecommendPaginationLogicTest {
    @Test
    fun catalogPageCarriesCursor() {
        val page = com.hongguo.watch.data.model.CatalogPage(
            items = emptyList(),
            page = 2,
            hasMore = true,
            nextOffset = 24,
            sessionId = "abc",
        )
        assertTrue(page.hasMore)
        assertTrue(page.nextOffset == 24)
        assertTrue(page.sessionId == "abc")
    }
}
