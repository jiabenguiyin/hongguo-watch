package com.hongguo.watch.ui

import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal object WatchDisplayGeometry {
    data class PreferredInsets(val horizontal: Int, val vertical: Int, val topFactor: Float) {
        val top: Int get() = (vertical * topFactor).roundToInt()
    }

    fun isNearlySquare(widthPx: Int, heightPx: Int): Boolean =
        maxOf(widthPx, heightPx) <= minOf(widthPx, heightPx) * 1.25

    // Some compact watches expose square display bounds with no hardware corner data.
    fun fallbackCornerRadius(widthPx: Int, heightPx: Int, round: Boolean, compact: Boolean): Int {
        val shortEdge = minOf(widthPx, heightPx)
        if (round) return shortEdge / 2
        return if (compact && isNearlySquare(widthPx, heightPx)) (shortEdge * 0.45).roundToInt() else 0
    }

    fun barVerticalInset(current: Int, shortEdge: Int, radius: Int, automatic: Boolean): Int =
        if (automatic) maxOf(current, minOf(shortEdge * 0.13, radius * 0.30).roundToInt()) else current

    // The left/right edge of the bar must be inside the visible arc at its outermost row.
    fun cornerSideInset(radius: Int, verticalInset: Int): Int {
        if (radius <= 0 || verticalInset >= radius) return 0
        val distance = verticalInset.coerceAtLeast(0).toDouble()
        return ceil(radius - sqrt(distance * (2.0 * radius - distance))).toInt()
    }

    /**
     * 底栏「选集 / 倍速」左右内收：够清圆角即可。
     * 全量 [cornerSideInset] 在方屏大圆角上会留下巨大空沟（0.5.6）；横屏必须封顶。
     */
    fun bottomChromeSidePad(
        sidePad: Int,
        cornerSideInset: Int,
        landscape: Boolean,
        cornerSafe: Boolean,
        landscapeCapPx: Int,
        portraitCapPx: Int,
    ): Int {
        if (!landscape && !cornerSafe) return sidePad
        val cap = if (landscape) landscapeCapPx.coerceAtLeast(0) else portraitCapPx.coerceAtLeast(0)
        val cappedCorner = minOf(cornerSideInset.coerceAtLeast(0), cap)
        return maxOf(sidePad.coerceAtLeast(0), cappedCorner)
    }

    fun preferredInsets(
        widthPx: Int,
        heightPx: Int,
        platformDensity: Float,
        layoutDensity: Float,
        round: Boolean,
        compact: Boolean,
        horizontalPercent: Int,
        verticalPercent: Int,
    ): PreferredInsets {
        val edge = minOf(widthPx, heightPx).coerceAtLeast(0)
        val automatic = when {
            round -> automaticInset(edge, platformDensity, 0.14f, 20, 48)
            compact -> automaticInset(edge, platformDensity, 0.05f, 8, 18)
            else -> automaticInset(edge, platformDensity, 0.04f, 8, 18)
        }
        val topFactor = when {
            verticalPercent >= 0 || round -> 1f
            compact -> 0.95f
            else -> 0.5f
        }
        return PreferredInsets(
            horizontal = if (horizontalPercent < 0) automatic else
                manualInset(widthPx, horizontalPercent, (176 * layoutDensity).roundToInt()),
            vertical = if (verticalPercent < 0) automatic else
                manualInset(heightPx, verticalPercent, (120 * layoutDensity).roundToInt()),
            topFactor = topFactor,
        )
    }

    private fun automaticInset(edgePx: Int, density: Float, ratio: Float, minDp: Int, maxDp: Int): Int =
        (edgePx * ratio).roundToInt().coerceIn((minDp * density).roundToInt(), (maxDp * density).roundToInt())

    private fun manualInset(axisPx: Int, percent: Int, minContentPx: Int): Int {
        val limit = ((axisPx - minContentPx) / 2).coerceAtLeast(0)
        return (axisPx * (percent.coerceIn(0, 20) / 100f)).roundToInt().coerceIn(0, limit)
    }
}
