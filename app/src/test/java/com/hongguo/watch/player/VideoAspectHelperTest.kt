package com.hongguo.watch.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class VideoAspectHelperTest {
    @Test
    fun fitScale_portraitView_landscapeVideo_letterboxes() {
        // 手表竖屏 320×360，片源 16:9 → 上下黑边，scaleY < 1
        val scales = VideoAspectHelper.fitScale(320f, 360f, 1920, 1080)
        assertNotNull(scales)
        assertEquals(1f, scales!!.first, 0.001f)
        assertEquals((320f / 360f) / (1920f / 1080f), scales.second, 0.001f)
    }

    @Test
    fun fitScale_landscapeView_landscapeVideo_fillsWidth() {
        // 横屏 360×320，片源 16:9 → 仍 letterbox（片更宽）
        val scales = VideoAspectHelper.fitScale(360f, 320f, 1920, 1080)
        assertNotNull(scales)
        assertEquals(1f, scales!!.first, 0.001f)
        assertEquals((360f / 320f) / (1920f / 1080f), scales.second, 0.001f)
    }

    @Test
    fun fitScale_portraitVideo_pillarboxes() {
        // 竖屏片在方屏上 → 左右黑边
        val scales = VideoAspectHelper.fitScale(320f, 320f, 720, 1280)
        assertNotNull(scales)
        assertEquals((720f / 1280f) / 1f, scales!!.first, 0.001f)
        assertEquals(1f, scales.second, 0.001f)
    }

    @Test
    fun fitScale_respectsSampleAspectRatio() {
        // 非方像素：SAR 4:3 会把有效宽拉大
        val plain = VideoAspectHelper.fitScale(320f, 240f, 320, 240, 1, 1)!!
        val sar = VideoAspectHelper.fitScale(320f, 240f, 320, 240, 4, 3)!!
        assertEquals(1f, plain.first, 0.001f)
        assertEquals(1f, plain.second, 0.001f)
        // SAR 后片更宽 → scaleY 变小
        assertEquals(1f, sar.first, 0.001f)
        assertEquals((320f / 240f) / ((320f / 240f) * (4f / 3f)), sar.second, 0.001f)
    }

    @Test
    fun fitScale_invalidInputs_returnNull() {
        assertNull(VideoAspectHelper.fitScale(0f, 100f, 1920, 1080))
        assertNull(VideoAspectHelper.fitScale(100f, 100f, 0, 1080))
        assertNull(VideoAspectHelper.fitScale(100f, 100f, 1920, -1))
    }
}
