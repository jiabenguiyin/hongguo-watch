// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.watch.player

import androidx.core.graphics.Insets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerChromeInsetsTest {
    @Test
    fun top_clearsStatusBarWhenLargerThanEdge() {
        val pad = PlayerChromeInsets.compute(
            edgeInsetPx = 16,
            topFactor = 0.5f,
            system = Insets.of(0, 48, 0, 0),
        )
        assertEquals(48, pad.top)
        assertEquals(16, pad.left)
        assertEquals(16, pad.right)
    }

    @Test
    fun top_usesRoundEdgeWhenSystemBarsHidden() {
        val edge = 40
        val pad = PlayerChromeInsets.compute(
            edgeInsetPx = edge,
            topFactor = 1f,
            system = Insets.NONE,
        )
        assertEquals(edge, pad.top)
        assertEquals(edge, pad.bottom)
    }

    @Test
    fun top_takesMaxOfCutoutAndStatusBar() {
        val pad = PlayerChromeInsets.compute(
            edgeInsetPx = 10,
            topFactor = 0.5f,
            system = Insets.of(0, 24, 0, 0),
        )
        // cutout+status 合并后 top=24 > edge*0.5=5
        assertEquals(24, pad.top)
    }

    @Test
    fun bottom_clearsNavigationBar() {
        val pad = PlayerChromeInsets.compute(
            edgeInsetPx = 12,
            topFactor = 0.5f,
            system = Insets.of(0, 0, 0, 36),
        )
        assertEquals(36, pad.bottom)
    }

    @Test
    fun topFactor_roundUsesFullEdge() {
        assertEquals(1f, PlayerChromeInsets.topFactor(roundLayout = true, cornerSafe = false))
        assertEquals(0.95f, PlayerChromeInsets.topFactor(roundLayout = false, cornerSafe = true))
        assertEquals(0.5f, PlayerChromeInsets.topFactor(roundLayout = false, cornerSafe = false))
    }

    @Test
    fun sides_preferLargerOfEdgeAndCutout() {
        val pad = PlayerChromeInsets.compute(
            edgeInsetPx = 20,
            topFactor = 1f,
            system = Insets.of(8, 0, 28, 0),
        )
        assertEquals(20, pad.left)
        assertEquals(28, pad.right)
        assertTrue(pad.right >= pad.left)
    }

    @Test
    fun contentInsets_keepPositiveSafePaddingForCornerDevices() {
        // 满幅背景仍要求控件有可点安全边（CD12 Max 大圆角）
        val pad = PlayerChromeInsets.compute(
            edgeInsetPx = 24,
            topFactor = 0.95f,
            system = Insets.NONE,
        )
        assertEquals(24, pad.left)
        assertEquals(24, pad.right)
        assertEquals(kotlin.math.round(24 * 0.95f).toInt(), pad.top)
        assertEquals(24, pad.bottom)
        assertTrue(pad.left > 0 && pad.bottom > 0)
    }
}
