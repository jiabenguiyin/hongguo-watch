package com.hongguo.watch.ui

import kotlin.math.abs

/**
 * 库页内「收藏 / 追更 / 历史 / 离线」分段切换策略。
 *
 * 左右滑只在库内相邻分段之间移动；到边即停，不循环，
 * 更不会映射到底栏「首页 / 我的」或任何父级 pager。
 */
object LibrarySegmentPolicy {
    enum class Segment {
        FAVORITES,
        CHASE,
        HISTORY,
        OFFLINE,
    }

    val ORDER: List<Segment> = listOf(
        Segment.FAVORITES,
        Segment.CHASE,
        Segment.HISTORY,
        Segment.OFFLINE,
    )

    /** 收藏 / 追更用三列海报格；历史 / 离线保持列表。 */
    fun usesPosterGrid(segment: Segment): Boolean =
        segment == Segment.FAVORITES || segment == Segment.CHASE

    /**
     * 根据水平 fling 给出下一段；垂直主导或速度不足返回 null。
     * 首尾再滑仍返回 null（夹住，不 wrap）。
     */
    fun targetAfterFling(
        current: Segment,
        velocityX: Float,
        velocityY: Float,
        minVelocity: Float = 800f,
    ): Segment? {
        if (abs(velocityX) < abs(velocityY) || abs(velocityX) < minVelocity) return null
        val index = ORDER.indexOf(current).let { if (it < 0) 0 else it }
        val next = if (velocityX < 0) index + 1 else index - 1
        if (next !in ORDER.indices) return null
        val target = ORDER[next]
        return if (target == current) null else target
    }

    /** 位移是否应认领为库内水平手势（阻止父级 / 底栏误跟手）。 */
    fun shouldClaimHorizontal(dx: Float, dy: Float, touchSlopPx: Float): Boolean {
        val ax = abs(dx)
        val ay = abs(dy)
        val slop = touchSlopPx.coerceAtLeast(1f)
        return ax > slop && ax >= ay
    }
}
