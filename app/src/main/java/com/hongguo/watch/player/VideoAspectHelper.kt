package com.hongguo.watch.player

import android.graphics.Matrix
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout

/**
 * 播放画面缩放模式：
 * - [VideoResizeMode.FIT] 等比完整显示（letterbox / pillarbox）
 * - [VideoResizeMode.FILL] 等比铺满裁切（cover）
 * - [VideoResizeMode.STRETCH] 拉伸铺满（不保持比例）
 *
 * IJK 的 TextureView 默认会把解码帧 stretch 到 View 尺寸，必须显式变换。
 * 双指缩放和平移叠在模式变换之上。
 */
enum class VideoResizeMode {
    FIT,
    FILL,
    STRETCH,
    ;

    fun next(): VideoResizeMode = when (this) {
        FIT -> FILL
        FILL -> STRETCH
        STRETCH -> FIT
    }

    companion object {
        fun fromStorage(raw: String?): VideoResizeMode =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: FIT
    }
}

object VideoAspectHelper {
    const val MIN_USER_ZOOM = 1f
    const val MAX_USER_ZOOM = 3f

    data class Transform(val zoom: Float = MIN_USER_ZOOM, val x: Float = 0f, val y: Float = 0f)

    /** Video dimensions at 1× and the visible area that clips the transformed picture. */
    data class Viewport(val width: Float, val height: Float, val videoWidth: Float, val videoHeight: Float) {
        fun constrain(transform: Transform): Transform {
            val zoom = transform.zoom.coerceIn(MIN_USER_ZOOM, MAX_USER_ZOOM)
            if (zoom == MIN_USER_ZOOM) return Transform()
            val maxX = ((videoWidth * zoom - width) / 2f).coerceAtLeast(0f)
            val maxY = ((videoHeight * zoom - height) / 2f).coerceAtLeast(0f)
            return Transform(zoom, transform.x.coerceIn(-maxX, maxX), transform.y.coerceIn(-maxY, maxY))
        }

        fun moveFocus(
            transform: Transform,
            factor: Float,
            oldX: Float,
            oldY: Float,
            newX: Float,
            newY: Float,
        ): Transform {
            val zoom = (transform.zoom * factor).coerceIn(MIN_USER_ZOOM, MAX_USER_ZOOM)
            val ratio = zoom / transform.zoom
            val cx = width / 2f
            val cy = height / 2f
            return constrain(Transform(
                zoom,
                newX - cx - (oldX - cx - transform.x) * ratio,
                newY - cy - (oldY - cy - transform.y) * ratio,
            ))
        }
    }

    /**
     * 计算 FIT（contain）缩放到居中所需的 scaleX / scaleY（相对 View 尺寸）。
     * 结果 ≤ 1：画面完全落在 View 内，多出的一边留黑边。
     */
    fun fitScale(
        viewWidth: Float,
        viewHeight: Float,
        videoWidth: Int,
        videoHeight: Int,
        sarNum: Int = 1,
        sarDen: Int = 1,
    ): Pair<Float, Float>? = baseScale(
        viewWidth,
        viewHeight,
        videoWidth,
        videoHeight,
        sarNum,
        sarDen,
        VideoResizeMode.FIT,
    )

    /** FILL（cover）：短边贴齐，长边溢出裁切；结果 ≥ 1。 */
    fun fillScale(
        viewWidth: Float,
        viewHeight: Float,
        videoWidth: Int,
        videoHeight: Int,
        sarNum: Int = 1,
        sarDen: Int = 1,
    ): Pair<Float, Float>? = baseScale(
        viewWidth,
        viewHeight,
        videoWidth,
        videoHeight,
        sarNum,
        sarDen,
        VideoResizeMode.FILL,
    )

    /** STRETCH：相对已铺满的 TextureView 内容不再改比例（1,1）。 */
    fun stretchScale(
        viewWidth: Float,
        viewHeight: Float,
        videoWidth: Int,
        videoHeight: Int,
        sarNum: Int = 1,
        sarDen: Int = 1,
    ): Pair<Float, Float>? = baseScale(
        viewWidth,
        viewHeight,
        videoWidth,
        videoHeight,
        sarNum,
        sarDen,
        VideoResizeMode.STRETCH,
    )

    fun scaleFor(
        mode: VideoResizeMode,
        viewWidth: Float,
        viewHeight: Float,
        videoWidth: Int,
        videoHeight: Int,
        sarNum: Int = 1,
        sarDen: Int = 1,
        userZoom: Float = MIN_USER_ZOOM,
    ): Pair<Float, Float>? {
        val base = baseScale(
            viewWidth,
            viewHeight,
            videoWidth,
            videoHeight,
            sarNum,
            sarDen,
            mode,
        ) ?: return null
        val zoom = userZoom.coerceIn(MIN_USER_ZOOM, MAX_USER_ZOOM)
        return (base.first * zoom) to (base.second * zoom)
    }

    private fun baseScale(
        viewWidth: Float,
        viewHeight: Float,
        videoWidth: Int,
        videoHeight: Int,
        sarNum: Int,
        sarDen: Int,
        mode: VideoResizeMode,
    ): Pair<Float, Float>? {
        if (viewWidth <= 0f || viewHeight <= 0f) return null
        if (videoWidth <= 0 || videoHeight <= 0) return null
        if (mode == VideoResizeMode.STRETCH) {
            return 1f to 1f
        }
        val sar = if (sarNum > 0 && sarDen > 0) sarNum.toFloat() / sarDen.toFloat() else 1f
        val videoAspect = (videoWidth.toFloat() / videoHeight.toFloat()) * sar
        val viewAspect = viewWidth / viewHeight
        return when (mode) {
            VideoResizeMode.FIT -> if (videoAspect > viewAspect) {
                // 片更宽 → 以宽为准，上下黑边
                1f to (viewAspect / videoAspect)
            } else {
                (videoAspect / viewAspect) to 1f
            }
            VideoResizeMode.FILL -> if (videoAspect > viewAspect) {
                // 片更宽 → 以高铺满，左右裁切
                (videoAspect / viewAspect) to 1f
            } else {
                1f to (viewAspect / videoAspect)
            }
            VideoResizeMode.STRETCH -> 1f to 1f
        }
    }

    /** 在 TextureView 上应用模式 + 用户缩放；View 未 layout 时 no-op。 */
    fun applyTransform(
        textureView: TextureView,
        mode: VideoResizeMode,
        videoWidth: Int,
        videoHeight: Int,
        sarNum: Int = 1,
        sarDen: Int = 1,
        transform: Transform = Transform(),
    ) {
        val w = textureView.width.toFloat()
        val h = textureView.height.toFloat()
        val scales = scaleFor(mode, w, h, videoWidth, videoHeight, sarNum, sarDen, transform.zoom)
            ?: return
        val matrix = Matrix()
        matrix.setScale(scales.first, scales.second, w / 2f, h / 2f)
        matrix.postTranslate(transform.x, transform.y)
        textureView.setTransform(matrix)
    }

    /** @deprecated 使用 [applyTransform]；保留兼容旧调用。 */
    fun applyFitTransform(
        textureView: TextureView,
        videoWidth: Int,
        videoHeight: Int,
        sarNum: Int = 1,
        sarDen: Int = 1,
    ) {
        applyTransform(
            textureView,
            VideoResizeMode.FIT,
            videoWidth,
            videoHeight,
            sarNum,
            sarDen,
        )
    }

    /**
     * 将子 View 的 LayoutParams 调成 FIT 居中尺寸（可选路径；IJK 主用 matrix）。
     * @return 是否改动了 layoutParams
     */
    fun applyFitLayoutParams(
        child: View,
        parentWidth: Int,
        parentHeight: Int,
        videoWidth: Int,
        videoHeight: Int,
        sarNum: Int = 1,
        sarDen: Int = 1,
    ): Boolean {
        if (parentWidth <= 0 || parentHeight <= 0) return false
        val scales = fitScale(
            parentWidth.toFloat(),
            parentHeight.toFloat(),
            videoWidth,
            videoHeight,
            sarNum,
            sarDen,
        ) ?: return false
        val targetW = (parentWidth * scales.first).toInt().coerceAtLeast(1)
        val targetH = (parentHeight * scales.second).toInt().coerceAtLeast(1)
        val lp = child.layoutParams as? FrameLayout.LayoutParams ?: return false
        if (lp.width == targetW && lp.height == targetH &&
            lp.gravity == (android.view.Gravity.CENTER)
        ) {
            return false
        }
        lp.width = targetW
        lp.height = targetH
        lp.gravity = android.view.Gravity.CENTER
        child.layoutParams = lp
        return true
    }
}
