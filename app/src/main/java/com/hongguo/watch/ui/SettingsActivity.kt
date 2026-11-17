package com.hongguo.watch.ui

import android.os.Bundle
import android.widget.ArrayAdapter
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val qualities = SettingsStore.QUALITY_OPTIONS
        val labels = listOf(
            getString(R.string.quality_480),
            getString(R.string.quality_720),
            getString(R.string.quality_1080),
        )
        binding.qualitySpinner.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)

        lifecycleScope.launch {
            binding.wifiOnlySwitch.isChecked = container.settings.wifiOnly.first()
            val maxQ = container.settings.maxQuality.first()
            binding.qualitySpinner.setSelection(qualities.indexOf(maxQ).coerceAtLeast(0))
            binding.showDemoSwitch.isChecked = container.settings.showDemo.first()
            binding.officialSwitch.isChecked = container.settings.enableOfficialDeprecated.first()
        }

        binding.wifiOnlySwitch.setOnCheckedChangeListener { _, checked ->
            lifecycleScope.launch { container.settings.setWifiOnly(checked) }
        }
        binding.showDemoSwitch.setOnCheckedChangeListener { _, checked ->
            lifecycleScope.launch { container.settings.setShowDemo(checked) }
        }
        binding.officialSwitch.setOnCheckedChangeListener { _, checked ->
            lifecycleScope.launch { container.settings.setEnableOfficialDeprecated(checked) }
        }
        binding.qualitySpinner.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: android.view.View?,
                position: Int,
                id: Long,
            ) {
                lifecycleScope.launch {
                    container.settings.setMaxQuality(qualities[position])
                }
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        })

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
}
