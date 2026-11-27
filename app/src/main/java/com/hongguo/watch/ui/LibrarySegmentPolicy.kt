package com.hongguo.watch.ui

import kotlin.math.abs

/**
 * 库页内「收藏 / 历史 / 离线」分段切换策略。
 *
 * 点赞 / 预约 / 追更 云端未打通前不进分段（避免失败空页与转圈）。
 * 左右滑只在库内相邻分段之间移动；到边即停，不循环。
 *
 * 三列 / 列表是全局布局模式（设置开关），对各分段共用；
 * 离线钻入分集时仍用列表。
 */
object LibrarySegmentPolicy {
    enum class Segment {
        FAVORITES,
        HISTORY,
        OFFLINE,
    }

    val ORDER: List<Segment> = listOf(
        Segment.FAVORITES,
        Segment.HISTORY,
        Segment.OFFLINE,
    )

    /**
     * 是否用三列海报格。
     * [listMode] true=列表；false=三列（默认）。
     * 离线钻入分集时调用方应强制列表。
     */
    fun usesPosterGrid(segment: Segment, listMode: Boolean): Boolean {
        if (listMode) return false
        return segment in ORDER
    }

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
