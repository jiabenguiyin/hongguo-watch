package com.hongguo.watch.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import coil.imageLoader
import com.hongguo.watch.HongguoWatchApp
import com.hongguo.watch.R
import com.hongguo.watch.data.SettingsStore
import com.hongguo.watch.databinding.ActivitySettingsBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private val container get() = (application as HongguoWatchApp).container
    private var currentQuality = 480
    private var roundSwitchReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (WatchUi.isCompactWatch(this)) WatchUi.applyImmersive(this)
        WatchUi.applySafePadding(binding.settingsRoot)

        binding.backButton.setOnClickListener { finish() }

        lifecycleScope.launch {
            binding.wifiOnlySwitch.isChecked = container.settings.wifiOnly.first()
            currentQuality = container.settings.maxQuality.first()
            refreshQualityChips()
            // 开关展示当前生效状态；AUTO 时跟 isRoundScreen
            binding.roundAdaptSwitch.isChecked = WatchUi.useRoundLayout(this@SettingsActivity)
            roundSwitchReady = true
        }

        binding.wifiOnlySwitch.setOnCheckedChangeListener { _, checked ->
            lifecycleScope.launch { container.settings.setWifiOnly(checked) }
        }
        binding.roundAdaptSwitch.setOnCheckedChangeListener { _, checked ->
            if (!roundSwitchReady) return@setOnCheckedChangeListener
            lifecycleScope.launch {
                container.settings.setRoundAdaptEnabled(checked)
                WatchUi.setRoundAdaptOverride(checked)
                WatchUi.applySafePadding(binding.settingsRoot)
            }
        }

        binding.quality480.setOnClickListener { pickQuality(480) }
        binding.quality720.setOnClickListener { pickQuality(720) }
        binding.quality1080.setOnClickListener { pickQuality(1080) }

        binding.clearHistoryButton.setOnClickListener {
            lifecycleScope.launch {
                container.repository.clearHistory()
                Toast.makeText(this@SettingsActivity, R.string.history_cleared, Toast.LENGTH_SHORT).show()
            }
        }
        binding.clearCacheButton.setOnClickListener {
            imageLoader.memoryCache?.clear()
            lifecycleScope.launch {
                @Suppress("OPT_IN_USAGE")
                imageLoader.diskCache?.clear()
                cacheDir.listFiles()?.forEach { child ->
                    if (child.name != "hongguo_watch.db" && !child.name.startsWith("hongguo_watch.db")) {
                        child.deleteRecursively()
                    }
                }
                Toast.makeText(this@SettingsActivity, R.string.cache_cleared, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && WatchUi.isCompactWatch(this)) WatchUi.applyImmersive(this)
    }

    override fun onResume() {
        super.onResume()
        if (WatchUi.isCompactWatch(this)) WatchUi.applyImmersive(this)
    }

    private fun pickQuality(height: Int) {
        lifecycleScope.launch {
            container.settings.setMaxQuality(height)
            currentQuality = SettingsStore.normalizeQuality(height)
            refreshQualityChips()
        }
    }

    private fun refreshQualityChips() {
        binding.quality480.isSelected = currentQuality == 480
        binding.quality720.isSelected = currentQuality == 720
        binding.quality1080.isSelected = currentQuality == 1080
    }
}
