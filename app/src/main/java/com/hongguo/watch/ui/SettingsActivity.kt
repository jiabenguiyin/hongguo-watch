package com.hongguo.watch.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import coil.imageLoader
import com.hongguo.watch.BuildConfig
import com.hongguo.watch.HongguoWatchApp
import com.hongguo.watch.R
import com.hongguo.watch.data.SettingsStore
import com.hongguo.watch.data.UpdateChecker
import com.hongguo.watch.databinding.ActivitySettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private val container get() = (application as HongguoWatchApp).container
    private var currentQuality = 480
    private var roundSwitchReady = false
    private var updateChecking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (WatchUi.isCompactWatch(this)) WatchUi.applyImmersive(this)
        WatchUi.applySafePadding(binding.settingsRoot)

        binding.backButton.setOnClickListener { finish() }
        binding.versionLabel.text = "当前版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）"

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

        binding.checkUpdateButton.setOnClickListener { checkForUpdate() }

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

    private fun checkForUpdate() {
        if (updateChecking) return
        updateChecking = true
        binding.checkUpdateButton.isEnabled = false
        Toast.makeText(this, R.string.checking_update, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) { UpdateChecker.check() }
            }
            updateChecking = false
            binding.checkUpdateButton.isEnabled = true
            outcome.onSuccess { result ->
                if (!result.isNewer) {
                    Toast.makeText(
                        this@SettingsActivity,
                        getString(R.string.update_latest, BuildConfig.VERSION_NAME),
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@onSuccess
                }
                val notes = result.body.ifBlank { "前往发布页查看说明。" }.take(280)
                val message = getString(
                    R.string.update_available_body,
                    BuildConfig.VERSION_NAME,
                    result.versionName,
                    notes,
                )
                val dialog = AlertDialog.Builder(this@SettingsActivity)
                    .setTitle(getString(R.string.update_available_title, result.versionName))
                    .setMessage(message)
                    .setNegativeButton(R.string.update_later, null)
                    .setNeutralButton(R.string.update_open_page) { _, _ ->
                        openUrl(result.htmlUrl.ifBlank { UpdateChecker.releasesPageUrl() })
                    }
                if (!result.apkUrl.isNullOrBlank()) {
                    dialog.setPositiveButton(R.string.update_download_apk) { _, _ ->
                        openUrl(result.apkUrl)
                    }
                } else {
                    dialog.setPositiveButton(R.string.update_open_page) { _, _ ->
                        openUrl(result.htmlUrl.ifBlank { UpdateChecker.releasesPageUrl() })
                    }
                }
                dialog.show()
            }.onFailure { err ->
                Toast.makeText(
                    this@SettingsActivity,
                    getString(
                        R.string.update_check_failed,
                        err.message ?: getString(R.string.error_network),
                    ),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun openUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Toast.makeText(this, R.string.error_generic, Toast.LENGTH_SHORT).show()
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
