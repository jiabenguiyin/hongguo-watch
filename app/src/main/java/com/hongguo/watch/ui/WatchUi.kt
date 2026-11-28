package com.hongguo.watch.ui

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Point
import android.os.Build
import android.util.TypedValue
import android.view.RoundedCorner
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.graphics.Insets
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
        val platform = context.applicationContext.resources
        val density = platform.displayMetrics.density
        val metrics = context.resources.displayMetrics
        val shortest = (min(metrics.widthPixels, metrics.heightPixels) / density).roundToInt()
        val config = platform.configuration
        val smallSquare = max(metrics.widthPixels, metrics.heightPixels) <= 600 &&
            WatchDisplayGeometry.isNearlySquare(metrics.widthPixels, metrics.heightPixels)
        return shortest <= 360 ||
            smallSquare ||
            config.smallestScreenWidthDp in 1..360 ||
            (config.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_WATCH
    }

    /**
     * 自动圆屏检测。仅信任系统 [Configuration.isScreenRound]；
     * **不再**把极小正方屏近似成圆盘——CD12 Max 等方屏大圆角机会被误判，
     * 导致默认边距过大或布局错位。方屏大圆角改走 [needsCornerSafeInset]。
     * 设置里「圆屏适配」仍可强制开/关。
     */
    fun isRoundScreen(context: Context): Boolean {
        val config = context.resources.configuration
        return Build.VERSION.SDK_INT >= 23 && config.isScreenRound
    }

    /**
     * 布局是否按圆屏处理：设置强制开/关优先，否则跟随 [isRoundScreen]。
     */
    fun useRoundLayout(context: Context): Boolean =
        WatchDisplaySettings.load(context).useRoundLayout(isRoundScreen(context))

    /**
     * 普通方屏但大圆角（如 CD12 Max / 华强北方表）：四角仍会裁切 chrome，
     * 不能只靠「圆屏适配」开关。
     */
    fun needsCornerSafeInset(context: Context): Boolean = isCompactWatch(context)

    internal fun preferredInsets(context: Context): WatchDisplayGeometry.PreferredInsets {
        val metrics = context.resources.displayMetrics
        val settings = WatchDisplaySettings.load(context)
        return WatchDisplayGeometry.preferredInsets(
            widthPx = metrics.widthPixels,
            heightPx = metrics.heightPixels,
            platformDensity = context.applicationContext.resources.displayMetrics.density,
            layoutDensity = metrics.density,
            round = settings.useRoundLayout(isRoundScreen(context)),
            compact = needsCornerSafeInset(context),
            horizontalPercent = settings.horizontalPercent,
            verticalPercent = settings.verticalPercent,
        )
    }

    fun edgeInsetPx(context: Context): Int = preferredInsets(context).horizontal

    fun availableWidthDp(context: Context): Int =
        (widthDp(context) - 2 * (edgeInsetPx(context) / context.resources.displayMetrics.density)).roundToInt()
            .coerceAtLeast(1)

    /** 圆屏 / 大圆角方屏侧栏额外内收，避免贴圆弧。 */
    fun sideMeterMarginPx(context: Context): Int {
        val base = edgeInsetPx(context)
        if (WatchDisplaySettings.load(context).horizontalPercent >= 0) return base
        return when {
            useRoundLayout(context) -> (base * 0.85f).roundToInt()
            needsCornerSafeInset(context) -> (base * 0.7f).roundToInt().coerceAtLeast(dp(context, 8f))
            else -> dp(context, 6f)
        }
    }

    fun dp(context: Context, value: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            context.resources.displayMetrics,
        ).roundToInt()
    }

    /**
     * 手表弹窗可用宽度：屏幕宽减去两侧圆角安全边，避免窄面板 + 底栏按钮被裁。
     */
    fun dialogContentWidthPx(context: Context): Int {
        val metrics = context.resources.displayMetrics
        return WatchDialogLayout.contentWidthPx(
            screenWidthPx = metrics.widthPixels,
            edgeInsetPx = edgeInsetPx(context),
            minWidthPx = dp(context, 200f),
        )
    }

    /**
     * 更新说明区最大高度：为标题/元信息/三键预留空间，保证按钮始终可见。
     */
    fun updateNotesMaxHeightPx(context: Context): Int {
        val metrics = context.resources.displayMetrics
        return WatchDialogLayout.notesMaxHeightPx(
            screenHeightPx = metrics.heightPixels,
            reservedPx = dp(context, 220f),
            minHeightPx = dp(context, 72f),
        )
    }

    fun episodeColumns(context: Context): Int {
        val w = widthDp(context)
        val preferred = when {
            w < 280 -> 4
            w < 360 -> 5
            else -> 6
        }
        return preferred.coerceAtMost((availableWidthDp(context) / 43).coerceAtLeast(2))
    }

    fun applyImmersive(activity: Activity) {
        val window = activity.window
        // 与 fitsSystemWindows=false 一致：内容可画入系统栏区域，由各页自行 padding。
        // 不使用 FLAG_LAYOUT_NO_LIMITS（会打乱 inset 与刘海避让）。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    /**
     * 手表安全边距。小屏沉浸式时**忽略** systemBars inset——
     * 系统栏已隐藏，若仍按其高度 padding，底 Tab 会被挤出可视区。
     *
     * @param bottomChromeContentPadding 为 true 时，底栏用与设置页相同的四角几何，
     * 但以 **padding** 内收文字（全宽底色保留，避免 margin 挤成悬浮灰条）。
     * 显示设置保存钮等仍默认 false（整钮 margin 内收）。
     */
    fun applySafePadding(
        view: View,
        topChrome: View? = null,
        bottomChrome: View? = null,
        bottomChromeContentPadding: Boolean = false,
    ) {
        fun apply(insets: WindowInsetsCompat?) {
            val context = view.context
            val preferred = preferredInsets(context)
            val compact = isCompactWatch(context)
            val types = WindowInsetsCompat.Type.displayCutout() or
                if (compact) 0 else WindowInsetsCompat.Type.systemBars()
            val system = insets?.getInsets(types) ?: Insets.NONE
            val left = max(preferred.horizontal, system.left)
            val right = max(preferred.horizontal, system.right)
            var top = max(preferred.top, system.top)
            var bottom = max(preferred.vertical, system.bottom)
            if (topChrome != null || bottomChrome != null) {
                val settings = WatchDisplaySettings.load(context)
                // Resource metrics may exclude the hidden navigation bar, making a square watch look tall or wide.
                val size = windowSize(context)
                val round = settings.useRoundLayout(isRoundScreen(context))
                val shortEdge = min(size.x, size.y)
                val fallback = WatchDisplayGeometry.fallbackCornerRadius(
                    size.x, size.y, round, compact,
                )
                if (topChrome != null) {
                    val reported = roundedCornerRadius(insets, RoundedCorner.POSITION_TOP_LEFT, RoundedCorner.POSITION_TOP_RIGHT)
                    val radius = if (round || reported == 0) fallback else reported
                    top = applyBarInsets(topChrome, top, left, right, shortEdge, radius, settings)
                }
                if (bottomChrome != null) {
                    val reported = roundedCornerRadius(insets, RoundedCorner.POSITION_BOTTOM_LEFT, RoundedCorner.POSITION_BOTTOM_RIGHT)
                    val radius = if (round || reported == 0) fallback else reported
                    bottom = applyBarInsets(
                        bottomChrome,
                        bottom,
                        left,
                        right,
                        shortEdge,
                        radius,
                        settings,
                        useContentPadding = bottomChromeContentPadding,
                    )
                }
            }
            view.setPadding(left, top, right, bottom)
        }
        apply(ViewCompat.getRootWindowInsets(view))
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            apply(insets)
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }

    private fun windowSize(context: Context): Point {
        val windows = context.getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= 30) {
            val bounds = windows.currentWindowMetrics.bounds
            return Point(bounds.width(), bounds.height())
        }
        @Suppress("DEPRECATION")
        return Point().also { windows.defaultDisplay.getRealSize(it) }
    }

    private fun roundedCornerRadius(insets: WindowInsetsCompat?, first: Int, second: Int): Int {
        if (Build.VERSION.SDK_INT < 31) return 0
        val window = insets?.toWindowInsets() ?: return 0
        return maxOf(window.getRoundedCorner(first)?.radius ?: 0, window.getRoundedCorner(second)?.radius ?: 0)
    }

    private fun applyBarInsets(
        bar: View,
        vertical: Int,
        left: Int,
        right: Int,
        shortEdge: Int,
        radius: Int,
        settings: WatchDisplaySettings,
        useContentPadding: Boolean = false,
    ): Int {
        val safeVertical = WatchDisplayGeometry.barVerticalInset(vertical, shortEdge, radius, settings.verticalPercent < 0)
        val params = bar.layoutParams as? ViewGroup.MarginLayoutParams ?: return safeVertical
        val arcInset = WatchDisplayGeometry.cornerSideInset(radius, safeVertical)
        val side = if (settings.horizontalPercent < 0 && arcInset > 0) arcInset + dp(bar.context, 2f) else 0
        val extra = (side - left).coerceAtLeast(0)
        val extraRight = (side - right).coerceAtLeast(0)
        if (useContentPadding) {
            // 全宽底色 + 内容内收：清 margin，避免悬浮灰条；左右用与设置页相同的侧向安全量
            if (params.leftMargin != 0 || params.rightMargin != 0) {
                params.leftMargin = 0
                params.rightMargin = 0
                bar.layoutParams = params
            }
            if (bar.paddingLeft != extra || bar.paddingRight != extraRight) {
                bar.setPadding(extra, bar.paddingTop, extraRight, bar.paddingBottom)
            }
        } else {
            if (params.leftMargin != extra || params.rightMargin != extraRight) {
                params.leftMargin = extra
                params.rightMargin = extraRight
                bar.layoutParams = params
            }
        }
        return safeVertical
    }
}
