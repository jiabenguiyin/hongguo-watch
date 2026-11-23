package com.hongguo.watch.data

/** Controls which playback route is tried first. Other routes remain available as fallbacks. */
enum class PlaybackRoutePreference {
    AUTO,
    PRIMARY,
    PRIMARY_A,
    PRIMARY_HL,
    PRIMARY_LF,
    VIDEO_TAB,
    PRELOAD,
    PUBLIC_360P,
    ;

    companion object {
        internal fun fromStorage(value: String?): PlaybackRoutePreference =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: AUTO
    }
}
