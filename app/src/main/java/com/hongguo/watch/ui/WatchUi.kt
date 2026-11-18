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
    /**
     * 圆屏适配偏好缓存：`null` = 跟随 [isRoundScreen]；
     * `true`/`false` = 强制开/关（设置里可改）。
     */
    @Volatile
    private var roundAdaptOverride: Boolean? = null

    fun setRoundAdaptOverride(enabled: Boolean?) {
        roundAdaptOverride = enabled
    }

    fun roundAdaptOverride(): Boolean? = roundAdaptOverride

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
    fun useRoundLayout(context: Context): Boolean {
        return roundAdaptOverride ?: isRoundScreen(context)
    }

    /**
     * 普通方屏但大圆角（如 CD12 Max / 华强北方表）：四角仍会裁切 chrome，
     * 不能只靠「圆屏适配」开关。
     */
    fun needsCornerSafeInset(context: Context): Boolean = isCompactWatch(context)

    /**
     * 安全边：
     * - 圆屏适配开：约 0.14×短边
     * - 小屏方表（大圆角）：约 0.09×短边，避免顶栏/底 Tab 被圆角吃掉
     * - 手机/大屏：轻边距
     */
    fun edgeInsetPx(context: Context): Int {
        val metrics = context.resources.displayMetrics
        val edge = min(metrics.widthPixels, metrics.heightPixels)
        val round = useRoundLayout(context)
        val cornerSafe = !round && needsCornerSafeInset(context)
        val ratio = when {
            round -> 0.14f
            // CD12 Max 等方屏大圆角：四角内切约需短边 10%+
            cornerSafe -> 0.10f
            else -> 0.04f
        }
        val preferred = (edge * ratio).roundToInt()
        val minDp = when {
            round -> 20f
            cornerSafe -> 16f
            else -> 8f
        }
        val maxDp = when {
            round -> 48f
            cornerSafe -> 32f
            else -> 18f
        }
        val minPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, minDp, metrics).roundToInt()
        val maxPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, maxDp, metrics).roundToInt()
        return preferred.coerceIn(minPx, maxPx)
    }

    /** 圆屏 / 大圆角方屏侧栏额外内收，避免贴圆弧。 */
    fun sideMeterMarginPx(context: Context): Int {
        val base = edgeInsetPx(context)
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
     */
    fun applySafePadding(view: View, extraBottom: Int = 0) {
        val inset = edgeInsetPx(view.context)
        // 圆屏 / 大圆角方屏顶部按满 inset，避免品牌名与搜索钮被四角裁切
        val topFactor = when {
            useRoundLayout(view.context) -> 1f
            needsCornerSafeInset(view.context) -> 0.95f
            else -> 0.5f
        }
        val compact = isCompactWatch(view.context)
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = if (compact) {
                // 沉浸式：只吃左右手势提示等非系统栏，避免顶/底虚高
                insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            } else {
                insets.getInsets(WindowInsetsCompat.Type.systemBars())
            }
            v.setPadding(
                max(inset, bars.left),
                max((inset * topFactor).roundToInt(), bars.top),
                max(inset, bars.right),
                max(inset, if (compact) 0 else bars.bottom) + extraBottom,
            )
            insets
        }
        view.requestApplyInsets()
        // 立即按 edge inset 落一版，避免首帧仍用系统栏旧 padding
        val top = (inset * topFactor).roundToInt()
        view.setPadding(inset, top, inset, inset + extraBottom)
    }
}
