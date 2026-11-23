package com.hongguo.watch.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.core.widget.NestedScrollView
import kotlin.math.max
import kotlin.math.abs

/** Handles both direct sensor callbacks and the watch ROM's View-scroll routing. */
internal class EpisodeWheel private constructor(
    private val manager: SensorManager,
    private val sensor: Sensor,
    private val onStep: (Int) -> Unit,
) : SensorEventListener {
    private var filter: StepFilter? = null

    fun setEnabled(enabled: Boolean) {
        if (enabled == (filter != null)) return
        if (!enabled) {
            filter = null
            manager.unregisterListener(this)
            return
        }

        val newFilter = StepFilter(SystemClock.elapsedRealtimeNanos())
        val registered = try {
            manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        } catch (_: SecurityException) {
            false
        }
        if (registered) filter = newFilter
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != SENSOR_TYPE) return
        filter?.step(event.values)?.let(onStep)
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    internal class StepFilter(
        registeredAtNanos: Long,
        private val quietPeriodNanos: Long = 300_000_000L,
        private val nowNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
    ) {
        private val readyAtNanos = registeredAtNanos + 100_000_000L
        private var lastPulseNanos: Long? = null

        fun step(values: FloatArray): Int? {
            val direction = values.getOrNull(1) ?: return null
            if (!direction.isFinite()) return null
            val callbackNanos = nowNanos()
            if (callbackNanos < readyAtNanos) return null
            val previous = lastPulseNanos
            if (previous != null && callbackNanos - previous < quietPeriodNanos) return null
            lastPulseNanos = callbackNanos
            return if (direction > 0f) 1 else -1
        }
    }

    companion object {
        private const val SENSOR_TYPE = 65597
        private const val SENSOR_STRING_TYPE = "android.sensor.wheel"

        fun frameworkScrollStep(scrollDelta: Int): Int? = when {
            scrollDelta > 0 -> 1
            scrollDelta < 0 -> -1
            else -> null
        }

        fun findSensor(context: Context): Sensor? =
            context.getSystemService(SensorManager::class.java)?.wheelSensor()

        fun create(context: Context, onStep: (Int) -> Unit): EpisodeWheel? {
            val manager = context.getSystemService(SensorManager::class.java) ?: return null
            val sensor = manager.wheelSensor() ?: return null
            return EpisodeWheel(manager, sensor, onStep)
        }

        private fun SensorManager.wheelSensor(): Sensor? =
            getDefaultSensor(SENSOR_TYPE)?.takeIf { it.stringType == SENSOR_STRING_TYPE }
    }
}

internal class EpisodeScrollBridge(
    private val scrollView: NestedScrollView,
    private val onStep: (Int) -> Unit,
) : View.OnScrollChangeListener {
    private val handler = Handler(Looper.getMainLooper())
    private var enabled = false
    private var suppressScroll = false
    private var lastY = 0
    private var accumulatedDy = 0
    private val settleScroll = Runnable {
        val delta = accumulatedDy
        val threshold = scrollView.height / 4
        val direction = scrollDirection(delta, threshold)
        accumulatedDy = 0
        debug("settle delta=$delta threshold=$threshold direction=$direction enabled=$enabled")
        if (enabled) direction?.let(onStep)
        recenter("settle")
    }

    init {
        scrollView.isNestedScrollingEnabled = false
        scrollView.isFocusable = false
        scrollView.isFocusableInTouchMode = false
        scrollView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        scrollView.setOnScrollChangeListener(this)
        scrollView.getChildAt(0)?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            recenter("content-layout")
        }
        scrollView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            ensureScrollableContent()
            recenter("layout")
        }
        scrollView.post {
            ensureScrollableContent()
            recenter("init")
            debug(
                "initialized width=${scrollView.width} height=${scrollView.height} " +
                    "content=${scrollView.getChildAt(0)?.height} y=${scrollView.scrollY}",
            )
        }
    }

    fun setEnabled(value: Boolean) {
        enabled = value
        handler.removeCallbacks(settleScroll)
        accumulatedDy = 0
        debug(
            "setEnabled=$value size=${scrollView.width}x${scrollView.height} " +
                "content=${scrollView.getChildAt(0)?.height}",
        )
        scrollView.post {
            ensureScrollableContent()
            recenter("enable=$value")
        }
    }

    override fun onScrollChange(
        view: View,
        scrollX: Int,
        scrollY: Int,
        oldScrollX: Int,
        oldScrollY: Int,
    ) {
        if (suppressScroll) {
            lastY = scrollY
            return
        }
        accumulatedDy += scrollY - lastY
        lastY = scrollY
        debug(
            "scroll old=$oldScrollY new=$scrollY dy=${scrollY - oldScrollY} " +
                "sum=$accumulatedDy enabled=$enabled",
        )
        if (!enabled) {
            accumulatedDy = 0
            scrollView.post { recenter("disabled") }
            return
        }
        handler.removeCallbacks(settleScroll)
        handler.postDelayed(settleScroll, SETTLE_DELAY_MS)
    }

    private fun recenter(reason: String) {
        val content = scrollView.getChildAt(0) ?: run {
            debug("recenter skipped reason=$reason missing-child")
            return
        }
        val maxScroll = content.height - scrollView.height
        if (maxScroll <= 0) {
            debug(
                "recenter skipped reason=$reason size=${scrollView.width}x${scrollView.height} " +
                    "content=${content.height} maxScroll=$maxScroll",
            )
            return
        }
        val center = maxScroll / 2
        suppressScroll = true
        scrollView.scrollTo(0, center)
        lastY = center
        suppressScroll = false
        debug("recenter reason=$reason center=$center max=$maxScroll")
    }

    private fun ensureScrollableContent() {
        val content = scrollView.getChildAt(0) ?: return
        val minimumHeight = max(scrollView.height * 4, 2_000)
        if (content.minimumHeight < minimumHeight) {
            content.minimumHeight = minimumHeight
            content.requestLayout()
            scrollView.requestLayout()
            debug("content minimumHeight=$minimumHeight")
        }
    }

    private fun debug(message: String) {
        if (com.hongguo.watch.BuildConfig.DEBUG) Log.d(TAG, message)
    }

    internal companion object {
        private const val SETTLE_DELAY_MS = 380L
        private const val TAG = "HgwWheelScroll"

        fun scrollDirection(deltaY: Int, thresholdPx: Int): Int? =
            when {
                deltaY >= thresholdPx.coerceAtLeast(1) -> 1
                deltaY <= -thresholdPx.coerceAtLeast(1) -> -1
                else -> null
            }

    }
}
