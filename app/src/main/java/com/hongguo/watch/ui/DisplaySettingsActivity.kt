package com.hongguo.watch.ui

import android.os.Bundle
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.widget.SeekBar
import androidx.core.content.ContextCompat
import com.hongguo.watch.R
import com.hongguo.watch.databinding.ActivityDisplaySettingsBinding
import kotlin.math.roundToInt

class DisplaySettingsActivity : WatchDisplayActivity() {
    override val useCustomDisplaySize: Boolean = false
    private lateinit var binding: ActivityDisplaySettingsBinding
    private lateinit var current: WatchDisplaySettings
    private lateinit var initial: WatchDisplaySettings
    private var maximumScale = WatchDisplaySettings.MAX_SCALE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initial = WatchDisplaySettings.load(this)
        val metrics = applicationContext.resources.displayMetrics
        maximumScale = WatchDisplaySettings.maximumScalePercent(
            metrics.densityDpi,
            minOf(metrics.widthPixels, metrics.heightPixels),
        )
        current = WatchDisplaySettings(
            scalePercent = (savedInstanceState?.getInt("scale") ?: initial.scalePercent)
                .coerceIn(WatchDisplaySettings.MIN_SCALE, maximumScale),
            textPercent = savedInstanceState?.getInt("text") ?: initial.textPercent,
            horizontalPercent = savedInstanceState?.getInt("horizontal") ?: initial.horizontalPercent,
            verticalPercent = savedInstanceState?.getInt("vertical") ?: initial.verticalPercent,
            shape = WatchDisplaySettings.Shape.entries.firstOrNull {
                it.name == savedInstanceState?.getString("shape")
            } ?: initial.shape,
        )
        binding = ActivityDisplaySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (WatchUi.isCompactWatch(this)) WatchUi.applyImmersive(this)
        WatchUi.applySafePadding(binding.displayRoot, topChrome = binding.displayToolbar, bottomChrome = binding.saveButton)
        WatchToolbarClock(this, binding.toolbarClock)
        binding.backButton.setOnClickListener { finish() }
        binding.shapeAuto.setOnClickListener { setShape(WatchDisplaySettings.Shape.AUTO) }
        binding.shapeRound.setOnClickListener { setShape(WatchDisplaySettings.Shape.ROUND) }
        binding.shapeRectangle.setOnClickListener { setShape(WatchDisplaySettings.Shape.RECTANGLE) }
        val step = WatchDisplaySettings.SIZE_STEP
        setupSlider(binding.scaleSlider, (maximumScale - WatchDisplaySettings.MIN_SCALE) / step) {
            current = current.copy(scalePercent = WatchDisplaySettings.MIN_SCALE + it * step)
        }
        setupSlider(binding.textSlider, (WatchDisplaySettings.MAX_TEXT - WatchDisplaySettings.MIN_TEXT) / step) {
            current = current.copy(textPercent = WatchDisplaySettings.MIN_TEXT + it * step)
        }
        setupSlider(binding.horizontalSlider, WatchDisplaySettings.MAX_MARGIN + 1) {
            current = current.copy(horizontalPercent = it - 1)
        }
        setupSlider(binding.verticalSlider, WatchDisplaySettings.MAX_MARGIN + 1) {
            current = current.copy(verticalPercent = it - 1)
        }
        binding.resetButton.setOnClickListener {
            current = WatchDisplaySettings()
            refreshControls()
        }
        binding.saveButton.setOnClickListener {
            current.save(this)
            finish()
        }
        binding.previewArea.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) refreshPreview()
        }
        refreshControls()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("scale", current.scalePercent)
        outState.putInt("text", current.textPercent)
        outState.putInt("horizontal", current.horizontalPercent)
        outState.putInt("vertical", current.verticalPercent)
        outState.putString("shape", current.shape.name)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && WatchUi.isCompactWatch(this)) WatchUi.applyImmersive(this)
    }

    private fun setShape(shape: WatchDisplaySettings.Shape) {
        current = current.copy(shape = shape)
        refreshControls()
    }

    private fun setupSlider(slider: SeekBar, maximum: Int, update: (Int) -> Unit) {
        slider.max = maximum
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                update(progress)
                refreshControls()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    private fun refreshControls() {
        binding.scaleSlider.progress = (current.scalePercent - WatchDisplaySettings.MIN_SCALE) / WatchDisplaySettings.SIZE_STEP
        binding.textSlider.progress = (current.textPercent - WatchDisplaySettings.MIN_TEXT) / WatchDisplaySettings.SIZE_STEP
        binding.horizontalSlider.progress = current.horizontalPercent + 1
        binding.verticalSlider.progress = current.verticalPercent + 1
        binding.scaleValue.text = getString(R.string.display_scale_value, current.scalePercent)
        binding.textValue.text = getString(R.string.display_text_value, current.textPercent)
        binding.horizontalValue.text = getString(R.string.display_horizontal_value, marginLabel(current.horizontalPercent))
        binding.verticalValue.text = getString(R.string.display_vertical_value, marginLabel(current.verticalPercent))
        listOf(
            binding.shapeAuto to WatchDisplaySettings.Shape.AUTO,
            binding.shapeRound to WatchDisplaySettings.Shape.ROUND,
            binding.shapeRectangle to WatchDisplaySettings.Shape.RECTANGLE,
        ).forEach { (view, shape) ->
            view.isSelected = current.shape == shape
            view.setTextColor(ContextCompat.getColor(this, if (view.isSelected) R.color.bg else R.color.text_primary))
        }
        refreshPreview()
    }

    private fun marginLabel(value: Int): String = if (value < 0) {
        getString(R.string.display_auto_margin)
    } else {
        getString(R.string.display_margin_percent, value)
    }

    private fun refreshPreview() {
        val area = binding.previewArea
        if (area.width <= 0 || area.height <= 0) return
        val system = applicationContext.resources.displayMetrics
        val density = current.effectiveDensity(system.densityDpi, minOf(system.widthPixels, system.heightPixels))
        val layoutDensity = density / 160f
        val round = current.useRoundLayout(WatchUi.isRoundScreen(this))
        val inset = WatchDisplayGeometry.preferredInsets(
            system.widthPixels, system.heightPixels, system.density, layoutDensity,
            round, WatchUi.isCompactWatch(this), current.horizontalPercent, current.verticalPercent,
        )
        val horizontal = (area.width * inset.horizontal.toFloat() / system.widthPixels.coerceAtLeast(1)).roundToInt()
        val top = (area.height * inset.top.toFloat() / system.heightPixels.coerceAtLeast(1)).roundToInt()
        val bottom = (area.height * inset.vertical.toFloat() / system.heightPixels.coerceAtLeast(1)).roundToInt()
        if (area.paddingLeft != horizontal || area.paddingTop != top || area.paddingBottom != bottom) {
            area.setPadding(horizontal, top, horizontal, bottom)
        }
        val outline = area.background as? GradientDrawable ?: GradientDrawable().apply {
            setColor(ContextCompat.getColor(this@DisplaySettingsActivity, R.color.bg_elevated))
            area.background = this
        }
        outline.cornerRadius = if (round) area.height / 2f else WatchUi.dp(this, 12f).toFloat()
        area.clipToOutline = true
        val sizeFactor = density / system.densityDpi.toFloat() * current.textPercent / 100f
        binding.previewTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.watch_title) * sizeFactor)
        binding.previewBody.setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.watch_meta) * sizeFactor)
    }
}
