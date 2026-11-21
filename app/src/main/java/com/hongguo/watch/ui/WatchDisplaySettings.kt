package com.hongguo.watch.ui

import android.content.Context
import android.content.res.Configuration
import android.util.DisplayMetrics
import kotlin.math.min
import kotlin.math.roundToInt

/** App-only display preferences. Read before Activity resources and views are created. */
internal data class WatchDisplaySettings(
    val scalePercent: Int = 100,
    val textPercent: Int = 100,
    val horizontalPercent: Int = AUTO_MARGIN,
    val verticalPercent: Int = AUTO_MARGIN,
    val shape: Shape = Shape.AUTO,
) {
    enum class Shape { AUTO, ROUND, RECTANGLE }

    fun useRoundLayout(systemRound: Boolean): Boolean = when (shape) {
        Shape.AUTO -> systemRound
        Shape.ROUND -> true
        Shape.RECTANGLE -> false
    }

    fun withDisplayResources(context: Context): Context {
        if (scalePercent == 100 && textPercent == 100) return context
        val metrics = context.resources.displayMetrics
        val configuration = Configuration().apply {
            if (scalePercent != 100) {
                densityDpi = effectiveDensity(metrics.densityDpi, min(metrics.widthPixels, metrics.heightPixels))
            }
            fontScale = context.resources.configuration.fontScale * textPercent / 100f
        }
        return context.createConfigurationContext(configuration)
    }

    fun effectiveDensity(stockDensity: Int, shortSidePx: Int): Int {
        val requested = (stockDensity * (scalePercent / 100f)).roundToInt().coerceAtLeast(1)
        if (shortSidePx <= 0 || requested <= stockDensity) return requested
        // Do not narrow the app's layout below 192dp, or below an already narrower device.
        val layoutLimit = shortSidePx * DisplayMetrics.DENSITY_DEFAULT / 192
        return min(requested, maxOf(stockDensity, layoutLimit))
    }

    fun save(context: Context) {
        preferences(context).edit()
            .putInt(SCALE, scalePercent)
            .putInt(TEXT, textPercent)
            .putInt(HORIZONTAL, horizontalPercent)
            .putInt(VERTICAL, verticalPercent)
            .putString(SHAPE, shape.name)
            .apply()
    }

    companion object {
        const val MIN_SCALE = 80
        const val MAX_SCALE = 145
        const val MIN_TEXT = 85
        const val MAX_TEXT = 135
        const val AUTO_MARGIN = -1
        const val MAX_MARGIN = 20
        const val SIZE_STEP = 5

        fun maximumScalePercent(stockDensity: Int, shortSidePx: Int): Int {
            if (stockDensity <= 0 || shortSidePx <= 0) return MAX_SCALE
            val limit = maxOf(stockDensity, shortSidePx * DisplayMetrics.DENSITY_DEFAULT / 192)
            return (MAX_SCALE downTo 100 step SIZE_STEP).first { percent ->
                (stockDensity * (percent / 100f)).roundToInt() <= limit
            }
        }

        private const val SCALE = "scale"
        private const val TEXT = "text"
        private const val HORIZONTAL = "horizontal"
        private const val VERTICAL = "vertical"
        private const val SHAPE = "shape"

        private fun preferences(context: Context) =
            context.getSharedPreferences("watch_display", Context.MODE_PRIVATE)

        fun load(context: Context): WatchDisplaySettings {
            val prefs = preferences(context)
            return WatchDisplaySettings(
                scalePercent = prefs.getInt(SCALE, 100).coerceIn(MIN_SCALE, MAX_SCALE),
                textPercent = prefs.getInt(TEXT, 100).coerceIn(MIN_TEXT, MAX_TEXT),
                horizontalPercent = prefs.getInt(HORIZONTAL, AUTO_MARGIN).coerceIn(AUTO_MARGIN, MAX_MARGIN),
                verticalPercent = prefs.getInt(VERTICAL, AUTO_MARGIN).coerceIn(AUTO_MARGIN, MAX_MARGIN),
                shape = Shape.entries.firstOrNull { it.name == prefs.getString(SHAPE, null) } ?: Shape.AUTO,
            )
        }

        /** Preserve the old round-screen switch the first time the new settings are used. */
        fun migrateShape(context: Context, oldRound: Boolean?) {
            if (oldRound == null) return
            val prefs = preferences(context)
            if (!prefs.contains(SHAPE)) {
                val shape = if (oldRound) Shape.ROUND else Shape.RECTANGLE
                prefs.edit().putString(SHAPE, shape.name).apply()
            }
        }
    }
}
