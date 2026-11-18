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
        assertTrue(
            WatchDecodePolicy.isWatchLike(
                model = "CD12 Max",
                brand = "oem",
                manufacturer = "oem",
                product = "phone",
                widthPixels = 0,
                heightPixels = 0,
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

    @Test
    fun fragileSocIncludesUnisocAndCd12() {
        assertTrue(
            WatchDecodePolicy.isFragileSoc(
                manufacturer = "Unisoc",
                brand = "sprd",
                hardware = "ums512",
                board = "ums512_1h10",
                model = "W527",
            ),
        )
        assertTrue(
            WatchDecodePolicy.isFragileSoc(
                manufacturer = "oem",
                brand = "oem",
                hardware = "qcom",
                board = "board",
                model = "CD12 Max",
            ),
        )
        assertFalse(
            WatchDecodePolicy.isFragileSoc(
                manufacturer = "Google",
                brand = "google",
                hardware = "tensor",
                board = "shiba",
                model = "Pixel 8",
            ),
        )
    }
}
