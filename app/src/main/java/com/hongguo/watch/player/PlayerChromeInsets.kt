// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.watch.player

import android.view.View
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.hongguo.watch.ui.WatchUi
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 播放器控件栏 WindowInsets：顶栏避让 statusBars / displayCutout，
 * 并叠圆屏 / 大圆角安全边。视频层保持全屏，不在此加 padding。
 */
object PlayerChromeInsets {
    data class Padding(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    )

    fun topFactor(roundLayout: Boolean, cornerSafe: Boolean): Float = when {
        roundLayout -> 1f
        cornerSafe -> 0.95f
        else -> 0.5f
    }

    /**
     * @param edgeInsetPx 圆屏 / 大圆角 / 普通边距（[WatchUi.edgeInsetPx]）
     * @param system 已合并的 statusBars | navigationBars | displayCutout
     */
    fun compute(
        edgeInsetPx: Int,
        topFactor: Float,
        system: Insets,
    ): Padding {
        val topEdge = (edgeInsetPx * topFactor).roundToInt()
        return Padding(
            left = max(edgeInsetPx, system.left),
            top = max(topEdge, max(system.top, 0)),
            right = max(edgeInsetPx, system.right),
            bottom = max(edgeInsetPx, max(system.bottom, 0)),
        )
    }

    fun systemChromeInsets(insets: WindowInsetsCompat): Insets {
        return insets.getInsets(
            WindowInsetsCompat.Type.statusBars() or
                WindowInsetsCompat.Type.navigationBars() or
                WindowInsetsCompat.Type.displayCutout(),
        )
    }

    /**
     * 仅给控件叠层加安全边；[videoRoot] 清零 padding，避免画面被额外 letterbox。
     * 手势 50/50 分区仍相对 videoRoot 内容区（此时即全屏宽）。
     */
    fun install(
        videoRoot: View,
        chromeOverlay: View,
        miniProgress: View? = null,
        onApplied: ((Padding) -> Unit)? = null,
    ) {
        videoRoot.setPadding(0, 0, 0, 0)
        fun applyPadding(pad: Padding) {
            chromeOverlay.setPadding(pad.left, pad.top, pad.right, pad.bottom)
            miniProgress?.let { progress ->
                val lp = progress.layoutParams as? android.widget.FrameLayout.LayoutParams
                if (lp != null) {
                    lp.bottomMargin = pad.bottom
                    progress.layoutParams = lp
                }
            }
            onApplied?.invoke(pad)
        }
        ViewCompat.setOnApplyWindowInsetsListener(chromeOverlay) { v, windowInsets ->
            val ctx = v.context
            applyPadding(
                compute(
                    edgeInsetPx = WatchUi.edgeInsetPx(ctx),
                    topFactor = topFactor(
                        roundLayout = WatchUi.useRoundLayout(ctx),
                        cornerSafe = WatchUi.needsCornerSafeInset(ctx),
                    ),
                    system = systemChromeInsets(windowInsets),
                ),
            )
            windowInsets
        }
        // 首帧：尚无 inset 回调时先用圆角边距，避免顶栏贴边闪一下
        val edge = WatchUi.edgeInsetPx(chromeOverlay.context)
        val factor = topFactor(
            roundLayout = WatchUi.useRoundLayout(chromeOverlay.context),
            cornerSafe = WatchUi.needsCornerSafeInset(chromeOverlay.context),
        )
        applyPadding(
            compute(
                edgeInsetPx = edge,
                topFactor = factor,
                system = Insets.NONE,
            ),
        )
        ViewCompat.requestApplyInsets(chromeOverlay)
    }
}
