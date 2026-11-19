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
 * 并叠圆屏 / 大圆角安全边。
 *
 * 布局约定（满幅美学）：
 * - 视频层 / [chromeOverlay] 本身 **不加** padding，顶/底渐变背景贴屏幕边；
 * - 安全边写在 [topChrome] / [bottomBar] 的 padding 上——Android 背景仍铺满 view，
 *   只有可点控件内缩，避免大圆角下出现「悬浮圆角卡片」空隙。
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
     * 视频与叠层容器满幅；顶/底 chrome 背景满幅，内容吃安全边。
     * 手势 50/50 分区仍相对 videoRoot 内容区（此时即全屏宽）。
     */
    fun install(
        videoRoot: View,
        chromeOverlay: View,
        topChrome: View,
        bottomBar: View,
        miniProgress: View? = null,
        onApplied: ((Padding) -> Unit)? = null,
    ) {
        videoRoot.setPadding(0, 0, 0, 0)
        chromeOverlay.setPadding(0, 0, 0, 0)
        fun applyPadding(pad: Padding) {
            // 背景铺满；padding 只内缩子控件（status / 返回 / 选集等）
            // topChrome 底边不垫：与 mid 区衔接；左右/顶吃安全边，背景仍满幅
            topChrome.setPadding(pad.left, pad.top, pad.right, 0)
            // 保留 XML 的 paddingTop（进度条上方间距），底边改为系统/圆角安全边
            val bottomTop = bottomBar.paddingTop.coerceAtLeast(0)
            bottomBar.setPadding(pad.left, bottomTop, pad.right, pad.bottom)
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
