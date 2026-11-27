package com.hongguo.watch.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import coil.imageLoader
import com.hongguo.watch.BuildConfig
import com.hongguo.watch.HongguoWatchApp
import com.hongguo.watch.R
import com.hongguo.watch.data.DiagUploader
import com.hongguo.watch.data.PlaybackRoutePreference
import com.hongguo.watch.data.SettingsStore
import com.hongguo.watch.databinding.ActivitySettingsBinding
import com.hongguo.watch.player.DecodePath
import com.hongguo.watch.player.PlayerDiagLog
import com.hongguo.watch.player.PlayerKernel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI

class SettingsActivity : WatchDisplayActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var toolbarClock: WatchToolbarClock
    private val container get() = (application as HongguoWatchApp).container
    private var currentQuality = 480
    private var currentKernel = PlayerKernel.AUTO
    private var currentDecode = DecodePath.AUTO
    private var currentRoute = PlaybackRoutePreference.AUTO
    private var currentProxyUrl = ""
    private var currentProxyToken = ""
    private var updateChecking = false
    private val updateDialogs by lazy { UpdateDialogs(this) { showWatchDialog(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (WatchUi.isCompactWatch(this)) WatchUi.applyImmersive(this)
        WatchUi.applySafePadding(binding.settingsRoot, topChrome = binding.settingsToolbar)
        toolbarClock = WatchToolbarClock(this, binding.toolbarClock)

        binding.backButton.setOnClickListener { finish() }
        binding.displaySettingsButton.setOnClickListener {
            startActivity(Intent(this, DisplaySettingsActivity::class.java))
        }
        binding.timeLockButton.setOnClickListener {
            startActivity(Intent(this, TimeLockActivity::class.java))
        }
        binding.versionLabel.text =
            getString(R.string.setting_version_status, BuildConfig.VERSION_NAME)

        lifecycleScope.launch {
            binding.wifiOnlySwitch.isChecked = container.settings.wifiOnly.first()
            binding.screenOffContinueSwitch.isChecked =
                container.settings.screenOffContinue.first()
            binding.libraryLayoutClassicSwitch.isChecked =
                container.settings.libraryLayoutClassic.first()
            binding.wifiOnlySwitch.setOnCheckedChangeListener { _, checked ->
                lifecycleScope.launch { container.settings.setWifiOnly(checked) }
            }
            binding.screenOffContinueSwitch.setOnCheckedChangeListener { _, checked ->
                lifecycleScope.launch { container.settings.setScreenOffContinue(checked) }
            }
            binding.libraryLayoutClassicSwitch.setOnCheckedChangeListener { _, checked ->
                lifecycleScope.launch { container.settings.setLibraryLayoutClassic(checked) }
            }
            binding.wifiOnlySwitch.isEnabled = true
            binding.screenOffContinueSwitch.isEnabled = true
            binding.libraryLayoutClassicSwitch.isEnabled = true
            if (EpisodeWheel.findSensor(this@SettingsActivity) != null) {
                binding.episodeWheelSwitch.isChecked = container.settings.episodeWheelEnabled.first()
                binding.episodeWheelSwitch.setOnCheckedChangeListener { _, checked ->
                    lifecycleScope.launch { container.settings.setEpisodeWheelEnabled(checked) }
                }
                binding.episodeWheelRow.visibility = View.VISIBLE
            }
            currentQuality = container.settings.maxQuality.first()
            currentKernel = container.settings.playerKernel.first()
            currentDecode = container.settings.decodePath.first()
            currentRoute = container.settings.playbackRouteNow()
            currentProxyUrl = container.settings.playbackProxyUrlNow()
            currentProxyToken = container.settings.playbackProxyTokenNow()
            refreshPlaybackLabels()
            refreshPlaybackRouteLabel()
            refreshPlaybackProxyLabel()
            binding.qualityButton.setOnClickListener { showQualityDialog() }
            binding.kernelButton.setOnClickListener { showKernelDialog() }
            binding.decodeButton.setOnClickListener { showDecodeDialog() }
            binding.playbackRouteButton.setOnClickListener { showPlaybackRouteDialog() }
            binding.playbackProxyButton.setOnClickListener { showPlaybackProxyDialog() }
            binding.qualityButton.isEnabled = true
            binding.kernelButton.isEnabled = true
            binding.decodeButton.isEnabled = true
            binding.playbackRouteButton.isEnabled = true
            binding.playbackProxyButton.isEnabled = true
        }

        binding.manageOfflineButton.setOnClickListener {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    putExtra(MainActivity.EXTRA_OPEN_LIBRARY_OFFLINE, true)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                },
            )
        }

        binding.checkUpdateButton.setOnClickListener { checkForUpdate() }
        binding.uploadDiagButton.setOnClickListener { uploadDiagnostics() }

        binding.clearHistoryButton.setOnClickListener {
            val dialog = watchDialogBuilder()
                .setTitle(R.string.settings_clear_watch_history_confirm)
                .setNegativeButton(R.string.update_cancel, null)
                .setPositiveButton(R.string.clear_history) { _, _ ->
                    lifecycleScope.launch {
                        container.repository.clearHistory()
                        Toast.makeText(this@SettingsActivity, R.string.history_cleared, Toast.LENGTH_SHORT).show()
                    }
                }
                .create()
            showWatchDialog(dialog)
        }
        binding.clearCacheButton.setOnClickListener {
            imageLoader.memoryCache?.clear()
            lifecycleScope.launch {
                @Suppress("OPT_IN_USAGE")
                imageLoader.diskCache?.clear()
                // 仅清应用 cacheDir（封面等）；视频缓存在 filesDir/video_cache，由上方单独清除
                cacheDir.listFiles()?.forEach { child ->
                    if (child.name != "hongguo_watch.db" && !child.name.startsWith("hongguo_watch.db")) {
                        child.deleteRecursively()
                    }
                }
                Toast.makeText(this@SettingsActivity, R.string.cache_cleared, Toast.LENGTH_SHORT).show()
            }
        }

        // 底部 QQ 群：轻量展示；点按/长按复制群号
        val copyQq = View.OnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("qq", "1130845653"))
            Toast.makeText(this, R.string.qq_group_copied, Toast.LENGTH_SHORT).show()
        }
        binding.qqGroupLabel.setOnClickListener(copyQq)
        binding.qqGroupLabel.setOnLongClickListener {
            copyQq.onClick(it)
            true
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && WatchUi.isCompactWatch(this)) WatchUi.applyImmersive(this)
    }

    override fun onResume() {
        super.onResume()
        if (recreateForDisplaySizeChange()) return
        if (WatchUi.isCompactWatch(this)) WatchUi.applyImmersive(this)
        WatchUi.applySafePadding(binding.settingsRoot, topChrome = binding.settingsToolbar)
        val isDefault = WatchDisplaySettings.load(this) == WatchDisplaySettings()
        binding.displaySettingsStatus.setText(
            if (isDefault) R.string.display_shape_auto else R.string.settings_display_custom,
        )
        refreshVideoCacheUi()
        refreshTimeLockStatus()
    }

    private fun refreshTimeLockStatus() {
        lifecycleScope.launch {
            val lock = container.timeLock
            if (lock.enabledNow()) {
                binding.timeLockStatus.text = getString(
                    R.string.time_lock_status_on,
                    com.hongguo.watch.data.TimeLockStore.formatLimitLabel(lock.dailyLimitMinutesNow()),
                )
            } else {
                binding.timeLockStatus.setText(R.string.time_lock_status_off)
            }
        }
    }

    private fun watchDialogBuilder(): AlertDialog.Builder {
        return AlertDialog.Builder(this, R.style.Theme_HongguoWatch_AlertDialog)
    }

    /** 弹窗近全宽（减圆角安全边），避免两侧黑边过大、底栏被裁。 */
    private fun showWatchDialog(dialog: AlertDialog): AlertDialog {
        dialog.show()
        dialog.window?.let { window ->
            val width = WatchUi.dialogContentWidthPx(this)
            window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
            window.setBackgroundDrawableResource(R.drawable.bg_alert_dialog)
        }
        return dialog
    }

    private fun checkForUpdate() {
        if (updateChecking) return
        updateChecking = true
        binding.checkUpdateButton.isEnabled = false
        Toast.makeText(this, R.string.checking_update, Toast.LENGTH_SHORT).show()
        val updates = (application as HongguoWatchApp).appUpdates
        lifecycleScope.launch {
            try {
                val outcome = updates.checkManually()
                lifecycle.withResumed {
                    if (!isFinishing) {
                        outcome.onSuccess { result ->
                            updates.markManualResultShown()
                            if (result.isNewer) updateDialogs.showAvailable(result) else updateDialogs.showLatest()
                        }.onFailure { error ->
                            val message = getString(
                                R.string.update_check_failed,
                                error.message ?: getString(R.string.error_network),
                            )
                            Toast.makeText(this@SettingsActivity, message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } finally {
                updateChecking = false
                binding.checkUpdateButton.isEnabled = true
            }
        }
    }

    private fun uploadDiagnostics() {
        Toast.makeText(this, R.string.diag_uploading, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val outcome = runCatching {
                val report = PlayerDiagLog.buildReport(
                    this@SettingsActivity,
                    reason = "manual_settings",
                )
                withContext(Dispatchers.IO) { DiagUploader.upload(report) }
            }
            outcome.onSuccess { result ->
                showWatchDialog(
                    watchDialogBuilder()
                        .setTitle(R.string.diag_uploaded_title)
                        .setMessage(getString(R.string.diag_uploaded_body, result.url))
                        .setNegativeButton(R.string.update_ok, null)
                        .setPositiveButton(R.string.diag_copy_link) { _, _ ->
                            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("diag", result.url))
                            Toast.makeText(
                                this@SettingsActivity,
                                R.string.update_ok,
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                        .create(),
                )
            }.onFailure { err ->
                Toast.makeText(
                    this@SettingsActivity,
                    getString(
                        R.string.diag_upload_failed,
                        err.message ?: getString(R.string.error_network),
                    ),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun showQualityDialog() {
        val options = SettingsStore.QUALITY_OPTIONS
        showChoiceDialog(R.string.max_quality, options.map(::qualityLabel), options.indexOf(currentQuality)) { index ->
            lifecycleScope.launch {
                currentQuality = options[index]
                container.settings.setMaxQuality(currentQuality)
                refreshPlaybackLabels()
            }
        }
    }

    private fun showKernelDialog() {
        val options = PlayerKernel.entries
        val labels = options.map { getText(kernelLabel(it)) }
        showChoiceDialog(R.string.player_kernel_label, labels, options.indexOf(currentKernel)) { index ->
            lifecycleScope.launch {
                currentKernel = options[index]
                container.settings.setPlayerKernel(currentKernel)
                refreshPlaybackLabels()
            }
        }
    }

    private fun showDecodeDialog() {
        val options = DecodePath.entries
        val labels = options.map { getText(decodeLabel(it)) }
        showChoiceDialog(R.string.decode_path_label, labels, options.indexOf(currentDecode)) { index ->
            lifecycleScope.launch {
                currentDecode = options[index]
                container.settings.setDecodePath(currentDecode)
                refreshPlaybackLabels()
            }
        }
    }

    private fun showPlaybackRouteDialog() {
        val options = PlaybackRoutePreference.entries
        val labels = options.map { getText(playbackRouteLabel(it)) }
        showChoiceDialog(R.string.playback_route_label, labels, options.indexOf(currentRoute)) { index ->
            lifecycleScope.launch {
                currentRoute = options[index]
                container.settings.setPlaybackRoute(currentRoute)
                refreshPlaybackRouteLabel()
            }
        }
    }

    private fun showPlaybackProxyDialog() {
        val pad = (12 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val urlInput = EditText(this).apply {
            hint = getString(R.string.playback_proxy_url_hint)
            setText(currentProxyUrl)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(getColor(R.color.text_primary))
            setHintTextColor(getColor(R.color.text_secondary))
        }
        val tokenInput = EditText(this).apply {
            hint = getString(R.string.playback_proxy_token_hint)
            setText(currentProxyToken)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(getColor(R.color.text_primary))
            setHintTextColor(getColor(R.color.text_secondary))
        }
        column.addView(urlInput)
        column.addView(tokenInput)
        val dialog = watchDialogBuilder()
            .setTitle(R.string.playback_proxy_label)
            .setView(column)
            .setNegativeButton(R.string.update_cancel, null)
            .setNeutralButton(R.string.playback_proxy_clear) { _, _ ->
                lifecycleScope.launch {
                    currentProxyUrl = ""
                    currentProxyToken = ""
                    container.settings.setPlaybackProxyUrl("")
                    container.settings.setPlaybackProxyToken("")
                    refreshPlaybackProxyLabel()
                    Toast.makeText(this@SettingsActivity, R.string.playback_proxy_saved, Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val rawUrl = urlInput.text?.toString().orEmpty()
                val token = tokenInput.text?.toString().orEmpty()
                val normalized = SettingsStore.normalizeProxyUrl(rawUrl)
                if (rawUrl.isNotBlank() && normalized.isEmpty()) {
                    Toast.makeText(this, R.string.playback_proxy_invalid, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    currentProxyUrl = normalized
                    currentProxyToken = token.trim()
                    container.settings.setPlaybackProxyUrl(normalized)
                    container.settings.setPlaybackProxyToken(currentProxyToken)
                    refreshPlaybackProxyLabel()
                    Toast.makeText(this@SettingsActivity, R.string.playback_proxy_saved, Toast.LENGTH_SHORT).show()
                }
            }
            .create()
        showWatchDialog(dialog)
    }

    private fun showChoiceDialog(
        title: Int,
        options: List<CharSequence>,
        checked: Int,
        onSelect: (Int) -> Unit,
    ) {
        val textColor = getColor(R.color.text_primary)
        // The default single-choice row may use a light theme even on our dark dialog.
        fun lightLabel(label: CharSequence): CharSequence = buildSpannedString {
            color(textColor) { append(label) }
        }
        val dialog = watchDialogBuilder()
            .setTitle(lightLabel(getText(title)))
            .setSingleChoiceItems(options.map(::lightLabel).toTypedArray(), checked) { chooser, index ->
                chooser.dismiss()
                onSelect(index)
            }
            .setNegativeButton(R.string.update_cancel, null)
            .create()
        showWatchDialog(dialog)
    }

    private fun refreshPlaybackRouteLabel() {
        binding.playbackRouteStatus.setText(playbackRouteLabel(currentRoute))
    }

    private fun refreshPlaybackProxyLabel() {
        binding.playbackProxyStatus.text = if (currentProxyUrl.isEmpty()) {
            getString(R.string.playback_proxy_off)
        } else {
            runCatching { URI(currentProxyUrl).host }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: currentProxyUrl.take(18)
        }
    }

    private fun playbackRouteLabel(route: PlaybackRoutePreference): Int = when (route) {
        PlaybackRoutePreference.AUTO -> R.string.playback_route_auto
        PlaybackRoutePreference.PRIMARY -> R.string.playback_route_primary
        PlaybackRoutePreference.PRIMARY_A -> R.string.playback_route_primary_a
        PlaybackRoutePreference.PRIMARY_HL -> R.string.playback_route_primary_hl
        PlaybackRoutePreference.PRIMARY_LF -> R.string.playback_route_primary_lf
        PlaybackRoutePreference.VIDEO_MODEL -> R.string.playback_route_video_model
        PlaybackRoutePreference.VIDEO_TAB -> R.string.playback_route_video_tab
        PlaybackRoutePreference.PRELOAD -> R.string.playback_route_preload
        PlaybackRoutePreference.PUBLIC_360P -> R.string.playback_route_public_360p
    }

    private fun refreshVideoCacheUi() {
        val summary = container.videoCache.spaceSummary()
        binding.videoCacheUsage.text = getString(
            R.string.video_cache_usage,
            summary.usedLabel.replace(' ', '\u00A0'),
            summary.budgetLabel.replace(' ', '\u00A0'),
        )
    }

    private fun refreshPlaybackLabels() {
        binding.qualityStatus.text = qualityLabel(currentQuality)
        binding.kernelStatus.setText(kernelLabel(currentKernel))
        binding.decodeStatus.setText(decodeLabel(currentDecode))
    }

    private fun qualityLabel(height: Int): String = getString(R.string.setting_quality_status, height)

    private fun kernelLabel(kernel: PlayerKernel): Int = when (kernel) {
        PlayerKernel.AUTO -> R.string.setting_kernel_status_auto
        PlayerKernel.MEDIA3 -> R.string.setting_kernel_status_media3
        PlayerKernel.IJK -> R.string.setting_kernel_status_ijk
    }

    private fun decodeLabel(path: DecodePath): Int = when (path) {
        DecodePath.AUTO -> R.string.setting_decode_status_auto
        DecodePath.HARDWARE -> R.string.setting_decode_status_hw
        DecodePath.SOFTWARE -> R.string.setting_decode_status_sw
    }
}
