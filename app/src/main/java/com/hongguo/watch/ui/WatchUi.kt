package com.hongguo.watch.ui

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 手表小屏度量与沉浸式辅助（含纯圆盘屏）。 */
object WatchUi {
    fun widthDp(context: Context): Int {
        val metrics = context.resources.displayMetrics
        return (metrics.widthPixels / metrics.density).roundToInt()
    }

    fun heightDp(context: Context): Int {
        val metrics = context.resources.displayMetrics
        return (metrics.heightPixels / metrics.density).roundToInt()
    }

    fun isCompactWatch(context: Context): Boolean {
        val shortest = min(widthDp(context), heightDp(context))
        return shortest <= 360 ||
            context.resources.configuration.smallestScreenWidthDp <= 360 ||
            (
                context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
                ) == Configuration.UI_MODE_TYPE_WATCH
    }

    /** API 23+ 圆屏；方屏圆角机也可通过短边近似判定。 */
    fun isRoundScreen(context: Context): Boolean {
        val config = context.resources.configuration
        if (Build.VERSION.SDK_INT >= 23 && config.isScreenRound) return true
        // 部分华强北圆盘 ROM 未标 isScreenRound，近似：近正方形且极小
        val w = widthDp(context)
        val h = heightDp(context)
        return isCompactWatch(context) && absDiff(w, h) <= 24 && min(w, h) <= 320
    }

    private fun absDiff(a: Int, b: Int): Int = if (a >= b) a - b else b - a

    /**
     * 圆盘内接安全边：约 0.12～0.15×短边，避免四角裁切；
     * 方屏仍用较轻边距。
     */
    fun edgeInsetPx(context: Context): Int {
        val metrics = context.resources.displayMetrics
        val edge = min(metrics.widthPixels, metrics.heightPixels)
        val round = isRoundScreen(context)
        val ratio = if (round) 0.14f else 0.04f
        val preferred = (edge * ratio).roundToInt()
        val minDp = if (round) 20f else 8f
        val maxDp = if (round) 48f else 18f
        val minPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, minDp, metrics).roundToInt()
        val maxPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, maxDp, metrics).roundToInt()
        return preferred.coerceIn(minPx, maxPx)
    }

    /** 圆屏侧栏（亮度/音量条）额外内收，避免贴圆弧。 */
    fun sideMeterMarginPx(context: Context): Int {
        val base = edgeInsetPx(context)
        return if (isRoundScreen(context)) (base * 0.85f).roundToInt() else dp(context, 6f)
    }

    fun dp(context: Context, value: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            context.resources.displayMetrics,
        ).roundToInt()
    }

    fun episodeColumns(context: Context): Int {
        val w = widthDp(context)
        return when {
            w < 280 -> 4
            w < 360 -> 5
            else -> 6
        }
    }

    fun applyImmersive(activity: Activity) {
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    fun applySafePadding(view: View, extraBottom: Int = 0) {
        val inset = edgeInsetPx(view.context)
        // 圆屏顶部也按满 inset，避免返回/标题贴圆弧
        val topFactor = if (isRoundScreen(view.context)) 1f else 0.5f
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                max(inset, bars.left),
                max((inset * topFactor).roundToInt(), bars.top),
                max(inset, bars.right),
                max(inset, bars.bottom) + extraBottom,
            )
            insets
        }
        view.requestApplyInsets()
    }
}
