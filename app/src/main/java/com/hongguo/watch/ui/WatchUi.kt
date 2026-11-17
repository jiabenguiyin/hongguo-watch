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

/** 手表小屏度量与沉浸式辅助。 */
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

    /** 圆角/窄屏两侧预留，避免控件贴边难点。 */
    fun edgeInsetPx(context: Context): Int {
        val metrics = context.resources.displayMetrics
        val edge = min(metrics.widthPixels, metrics.heightPixels)
        val preferred = (edge * 0.04f).roundToInt()
        val minPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            8f,
            metrics,
        ).roundToInt()
        val maxPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            18f,
            metrics,
        ).roundToInt()
        return preferred.coerceIn(minPx, maxPx)
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
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                max(inset, bars.left),
                max(inset / 2, bars.top),
                max(inset, bars.right),
                max(inset, bars.bottom) + extraBottom,
            )
            insets
        }
        view.requestApplyInsets()
    }
}
