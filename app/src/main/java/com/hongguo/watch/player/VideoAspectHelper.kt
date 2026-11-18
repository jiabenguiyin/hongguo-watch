package com.hongguo.watch.player

import android.graphics.Matrix
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout

/**
 * 播放画面等比适配：letterbox / pillarbox，禁止拉伸铺满。
 * IJK 的 TextureView 默认会把解码帧 stretch 到 View 尺寸，必须显式变换。
 */
object VideoAspectHelper {
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
    ): Pair<Float, Float>? {
        if (viewWidth <= 0f || viewHeight <= 0f) return null
        if (videoWidth <= 0 || videoHeight <= 0) return null
        val sar = if (sarNum > 0 && sarDen > 0) sarNum.toFloat() / sarDen.toFloat() else 1f
        val videoAspect = (videoWidth.toFloat() / videoHeight.toFloat()) * sar
        val viewAspect = viewWidth / viewHeight
        return if (videoAspect > viewAspect) {
            // 片更宽 → 以宽为准，上下黑边（letterbox）
            1f to (viewAspect / videoAspect)
        } else {
            // 片更高/接近正方 → 以高为准，左右黑边（pillarbox）
            (videoAspect / viewAspect) to 1f
        }
    }

    /** 在 TextureView 上应用 FIT 变换；View 未 layout 时 no-op。 */
    fun applyFitTransform(
        textureView: TextureView,
        videoWidth: Int,
        videoHeight: Int,
        sarNum: Int = 1,
        sarDen: Int = 1,
    ) {
        val w = textureView.width.toFloat()
        val h = textureView.height.toFloat()
        val scales = fitScale(w, h, videoWidth, videoHeight, sarNum, sarDen) ?: return
        val matrix = Matrix()
        matrix.setScale(scales.first, scales.second, w / 2f, h / 2f)
        textureView.setTransform(matrix)
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
