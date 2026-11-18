// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.watch.player

/**
 * 手表息屏 / 退后台播放策略（纯逻辑，便于单测）。
 *
 * 默认：熄屏或退后台时暂停，避免 CD12 Max 等机型亮屏硬解长时间耗电。
 * 可选「息屏续听」：熄屏后继续出声，仅持有 PARTIAL_WAKE_LOCK，不强制亮屏。
 */
object ScreenOffPlaybackPolicy {
    /** 设置缺省：关闭续听（省电）。 */
    const val DEFAULT_CONTINUE_ENABLED = false

    /**
     * [onStop] 时是否继续播放（不 pause、不取消取流）。
     * 正在 finish 的 Activity 必须停下，避免返回键后后台偷播。
     */
    fun shouldContinueWhenStopping(
        continueEnabled: Boolean,
        isFinishing: Boolean,
        isActivelyPlaying: Boolean,
    ): Boolean = continueEnabled && !isFinishing && isActivelyPlaying

    /** 前台且在播时才 FLAG_KEEP_SCREEN_ON，避免息屏后续听仍锁亮屏。 */
    fun shouldKeepScreenOn(
        foreground: Boolean,
        isActivelyPlaying: Boolean,
    ): Boolean = foreground && isActivelyPlaying

    /** 仅息屏续听后台持有 PARTIAL_WAKE_LOCK，保证解码/出声。 */
    fun shouldHoldPartialWakeLock(
        continueEnabled: Boolean,
        foreground: Boolean,
        isActivelyPlaying: Boolean,
    ): Boolean = continueEnabled && !foreground && isActivelyPlaying
}
