package com.hongguo.watch.player

import kotlin.math.abs

/**
 * 播放器亮度 / 音量 / 进度手势分区。
 *
 * 触点 X 与 [contentWidth] 必须同属「可用内容区」坐标：
 * `0 .. contentWidth`，即 `playerRoot` 去掉 padding / cutout 内边后的宽度。
 * 若误用外框 `view.width`（含左右 padding），方表大圆角 inset 下
 * 会把左半区撑到约 60%–70%，体感左右不对称。
 *
 * - 横屏：竖直滑动按可用宽度 **50% / 50%** 分左右（左亮度、右音量）；横向→进度。
 * - 竖屏：保持左 1/3 亮度、右 1/3 音量；中间优先横向进度，纯竖直再按半宽回落。
 * - 放大时：仅左右外侧的近竖直滑动调节亮度/音量，其余方向用于平移画面。
 */
object PlayerGestureZones {
    enum class Mode { SEEK, BRIGHTNESS, VOLUME, PAN }

    fun classify(
        startXInContent: Float,
        dx: Float,
        dy: Float,
        contentWidth: Int,
        landscape: Boolean,
        zoomed: Boolean = false,
    ): Mode {
        val width = contentWidth.coerceAtLeast(1).toFloat()
        val x = startXInContent.coerceIn(0f, width)
        if (zoomed) {
            if (abs(dy) <= abs(dx) * 1.2f) return Mode.PAN
            return when {
                x < width / 3f -> Mode.BRIGHTNESS
                x > width * 2f / 3f -> Mode.VOLUME
                else -> Mode.PAN
            }
        }
        // 主轴互斥：与 [PlayerGestureArbiter.dominantAxis] 一致，避免对角同时像 seek 又像音量
        return when (PlayerGestureArbiter.dominantAxis(dx, dy)) {
            PlayerGestureArbiter.Axis.HORIZONTAL -> Mode.SEEK
            PlayerGestureArbiter.Axis.VERTICAL -> if (landscape) {
                if (x < width / 2f) Mode.BRIGHTNESS else Mode.VOLUME
            } else {
                when {
                    x < width / 3f -> Mode.BRIGHTNESS
                    x > width * 2f / 3f -> Mode.VOLUME
                    x < width / 2f -> Mode.BRIGHTNESS
                    else -> Mode.VOLUME
                }
            }
        }
    }

    /** 内容区宽度：去掉左右 padding（含安全边 / cutout）。 */
    fun contentWidth(viewWidth: Int, paddingLeft: Int, paddingRight: Int): Int =
        (viewWidth - paddingLeft - paddingRight).coerceAtLeast(1)

    /** 内容区高度：去掉上下 padding。 */
    fun contentHeight(viewHeight: Int, paddingTop: Int, paddingBottom: Int): Int =
        (viewHeight - paddingTop - paddingBottom).coerceAtLeast(1)
}
