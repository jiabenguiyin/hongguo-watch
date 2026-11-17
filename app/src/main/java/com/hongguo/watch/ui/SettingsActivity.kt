package com.hongguo.watch.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import coil.imageLoader
import com.hongguo.watch.HongguoWatchApp
import com.hongguo.watch.data.SettingsStore
import com.hongguo.watch.databinding.ActivitySettingsBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private val container get() = (application as HongguoWatchApp).container
    private var currentQuality = 480

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WatchUi.applySafePadding(binding.settingsRoot)

        binding.backButton.setOnClickListener { finish() }

        lifecycleScope.launch {
            binding.wifiOnlySwitch.isChecked = container.settings.wifiOnly.first()
            currentQuality = container.settings.maxQuality.first()
            refreshQualityChips()
            binding.showDemoSwitch.isChecked = container.settings.showDemo.first()
        }

        binding.wifiOnlySwitch.setOnCheckedChangeListener { _, checked ->
            lifecycleScope.launch { container.settings.setWifiOnly(checked) }
        }
        binding.showDemoSwitch.setOnCheckedChangeListener { _, checked ->
            lifecycleScope.launch { container.settings.setShowDemo(checked) }
        }

        binding.quality480.setOnClickListener { pickQuality(480) }
        binding.quality720.setOnClickListener { pickQuality(720) }
        binding.quality1080.setOnClickListener { pickQuality(1080) }

        binding.clearHistoryButton.setOnClickListener {
            lifecycleScope.launch {
                container.repository.clearHistory()
                Toast.makeText(this@SettingsActivity, "历史已清除", Toast.LENGTH_SHORT).show()
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
                Toast.makeText(this@SettingsActivity, "缓存已清除", Toast.LENGTH_SHORT).show()
            }
        }
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
