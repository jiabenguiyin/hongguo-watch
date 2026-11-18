package com.hongguo.watch.player

import kotlin.math.abs
import kotlin.math.hypot

/**
 * 播放器单指手势仲裁：长按加速 vs 横向 seek vs 纵向亮度/音量。
 *
 * 优先级（同一次按下）：
 * 1. 双指 / 缩放手势 → 独占，取消长按与滑动认领
 * 2. 位移超过 [swipeClaimSlop] → 按主轴认领滑动（不再触发长按）
 * 3. 位移超过 [longPressCancelSlop] 但未认领滑动 → 取消待决长按
 * 4. 手指基本不动直到长按超时 → 临时加速
 * 5. 单击 / 双击由上层 GestureDetector（关闭其内置长按）处理
 */
object PlayerGestureArbiter {
    enum class Axis { HORIZONTAL, VERTICAL }

    /** 超过此位移取消待决长按（手指不再「按住不动」）。默认 = touchSlop。 */
    fun longPressCancelSlop(touchSlopPx: Float): Float = touchSlopPx.coerceAtLeast(1f)

    /** 超过此位移认领滑动（主轴互斥）。默认 = 1.5 × touchSlop。 */
    fun swipeClaimSlop(touchSlopPx: Float): Float =
        (touchSlopPx * 1.5f).coerceAtLeast(longPressCancelSlop(touchSlopPx))

    fun movement(dx: Float, dy: Float): Float = hypot(dx.toDouble(), dy.toDouble()).toFloat()

    fun shouldCancelLongPress(dx: Float, dy: Float, touchSlopPx: Float): Boolean =
        movement(dx, dy) > longPressCancelSlop(touchSlopPx)

    fun shouldClaimSwipe(dx: Float, dy: Float, touchSlopPx: Float): Boolean =
        movement(dx, dy) > swipeClaimSlop(touchSlopPx)

    /**
     * 主轴判定：明显横向 → 进度；明显纵向 → 亮度/音量。
     * 接近对角时取绝对值较大轴，避免同时触发两类。
     */
    fun dominantAxis(dx: Float, dy: Float, dominanceRatio: Float = 1.2f): Axis {
        val ax = abs(dx)
        val ay = abs(dy)
        return when {
            ax > ay * dominanceRatio -> Axis.HORIZONTAL
            ay > ax * dominanceRatio -> Axis.VERTICAL
            ax >= ay -> Axis.HORIZONTAL
            else -> Axis.VERTICAL
        }
    }

    fun isMultiTouch(pointerCount: Int): Boolean = pointerCount > 1
}
