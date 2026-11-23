// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.watch.data

/**
 * Mirror of the watch enum so `:proxy` can compile [com.hongguo.watch.upstream.MobilePlayback]
 * without the Android `:app` module.
 */
enum class PlaybackRoutePreference {
    AUTO,
    PRIMARY,
    PRIMARY_A,
    PRIMARY_HL,
    PRIMARY_LF,
    VIDEO_MODEL,
    VIDEO_TAB,
    PRELOAD,
    PUBLIC_360P,
}
