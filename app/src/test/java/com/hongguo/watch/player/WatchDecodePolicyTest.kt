package com.hongguo.watch.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchDecodePolicyTest {
    @Test
    fun watchLikeByModelOrSize() {
        assertTrue(
            WatchDecodePolicy.isWatchLike(
                model = "SmartWatch X",
                brand = "oem",
                manufacturer = "oem",
                product = "watch",
                widthPixels = 0,
                heightPixels = 0,
            ),
        )
        assertTrue(
            WatchDecodePolicy.isWatchLike(
                model = "CD12",
                brand = "oem",
                manufacturer = "oem",
                product = "phone",
                widthPixels = 480,
                heightPixels = 480,
            ),
        )
        assertFalse(
            WatchDecodePolicy.isWatchLike(
                model = "Pixel 8",
                brand = "google",
                manufacturer = "Google",
                product = "shiba",
                widthPixels = 1080,
                heightPixels = 2400,
            ),
        )
    }
}
