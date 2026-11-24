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
        // 真机 paste kzty6：sprd / uws6152
        assertTrue(
            WatchDecodePolicy.isFragileSoc(
                manufacturer = "sprd",
                brand = "BiDeZhi",
                hardware = "uws6152_1h10_go",
                board = "uws6152_1h10_go",
                model = "CD_Watch_A",
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

    @Test
    fun effectiveMaxQualityCapsFragileLowMemAt480() {
        org.junit.Assert.assertEquals(
            480,
            WatchDecodePolicy.effectiveMaxQuality(720, fragileSoc = true, memClassMb = 192),
        )
        org.junit.Assert.assertEquals(
            360,
            WatchDecodePolicy.effectiveMaxQuality(360, fragileSoc = true, memClassMb = 192),
        )
        org.junit.Assert.assertEquals(
            720,
            WatchDecodePolicy.effectiveMaxQuality(720, fragileSoc = false, memClassMb = 192),
        )
    }

    @Test
    fun effectiveMaxQualityCapsSoftHevcPathAt360() {
        org.junit.Assert.assertEquals(
            360,
            WatchDecodePolicy.effectiveMaxQuality(
                userMaxQuality = 720,
                fragileSoc = true,
                memClassMb = 192,
                softHevcPath = true,
            ),
        )
        org.junit.Assert.assertEquals(
            360,
            WatchDecodePolicy.effectiveMaxQuality(
                userMaxQuality = 480,
                fragileSoc = true,
                memClassMb = 256,
                softHevcPath = true,
            ),
        )
        // 有硬解路径（softHevcPath=false）仍按低内存 480
        org.junit.Assert.assertEquals(
            480,
            WatchDecodePolicy.effectiveMaxQuality(
                userMaxQuality = 720,
                fragileSoc = true,
                memClassMb = 192,
                softHevcPath = false,
            ),
        )
    }
}
