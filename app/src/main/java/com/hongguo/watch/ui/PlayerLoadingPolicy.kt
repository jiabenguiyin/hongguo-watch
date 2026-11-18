// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.watch.ui

/**
 * 播放器缓冲提示相位：区分起播、播放中卡顿、久卡切线提示。
 * 与指示器防抖配合；久卡文案应早于 [com.hongguo.watch.upstream.StreamFailover.STALL_TIMEOUT_MS]。
 */
object PlayerLoadingPolicy {
    enum class Phase {
        /** 起播 / 切集 / 尚未出首帧 */
        INITIAL,
        /** 首帧后的中途缓冲 */
        REBUFFER,
        /** 中途缓冲较久：静默提示可能自动切线（不用 Toast） */
        REBUFFER_PROLONGED,
        /** 跨季加载 */
        NEXT_SEASON,
    }

    /** 播放中缓冲：短于此时长不闪指示器。 */
    const val INDICATOR_DELAY_MS = 450L

    /**
     * 自「需要显示缓冲」起多久切到久卡文案。
     * 须大于 [INDICATOR_DELAY_MS]，且明显小于 stall 切流超时。
     */
    const val PROLONGED_HINT_MS = 5_000L

    fun phase(
        firstFrameRendered: Boolean,
        bufferingElapsedMs: Long,
        nextSeason: Boolean = false,
    ): Phase {
        if (nextSeason) return Phase.NEXT_SEASON
        if (!firstFrameRendered) return Phase.INITIAL
        if (bufferingElapsedMs >= PROLONGED_HINT_MS) return Phase.REBUFFER_PROLONGED
        return Phase.REBUFFER
    }
}
