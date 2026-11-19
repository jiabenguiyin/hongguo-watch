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
 * 播放器控件栏 WindowInsets：算出 statusBars / displayCutout / 圆角安全边，
 * 交给可点控件吃；顶/底 chrome **背景**始终贴物理屏边。
 *
 * 布局约定：
 * - 视频层 / [chromeOverlay] / [topChrome] / [bottomBar] **不加** 安全边 padding，
 *   渐变背景贴绝对顶/底，不整栏内浮；
 * - [miniProgress] 贴真底（bottomMargin=0），不随圆角边上抬；
 * - 安全边通过 [onApplied] 只写到命中目标（状态条/顶栏钮/底栏钮等）。
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
     * 视频与叠层 / 顶底栏容器满幅贴边；进度条贴真底。
     * 安全边只经 [onApplied] 交给命中目标，避免整栏内浮留黑缝。
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
        // 保留 XML 顶间距（进度条上方呼吸），底/左右清零——背景贴物理底边
        val bottomTop = bottomBar.paddingTop.coerceAtLeast(0)
        fun applyEdgeFlush() {
            topChrome.setPadding(0, 0, 0, 0)
            bottomBar.setPadding(0, bottomTop, 0, 0)
            miniProgress?.let { progress ->
                val lp = progress.layoutParams as? android.widget.FrameLayout.LayoutParams
                if (lp != null && lp.bottomMargin != 0) {
                    lp.bottomMargin = 0
                    progress.layoutParams = lp
                }
            }
        }
        fun applyPadding(pad: Padding) {
            applyEdgeFlush()
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
        // 首帧：尚无 inset 回调时先用圆角边距，避免顶栏控件贴边闪一下
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
