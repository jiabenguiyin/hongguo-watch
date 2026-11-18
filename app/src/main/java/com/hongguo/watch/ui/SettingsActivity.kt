package com.hongguo.watch.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import coil.imageLoader
import com.hongguo.watch.BuildConfig
import com.hongguo.watch.HongguoWatchApp
import com.hongguo.watch.R
import com.hongguo.watch.data.DiagUploader
import com.hongguo.watch.data.SettingsStore
import com.hongguo.watch.data.UpdateApkInstaller
import com.hongguo.watch.data.UpdateChecker
import com.hongguo.watch.databinding.ActivitySettingsBinding
import com.hongguo.watch.player.DecodePath
import com.hongguo.watch.player.PlayerDiagLog
import com.hongguo.watch.player.PlayerKernel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private val container get() = (application as HongguoWatchApp).container
    private var currentQuality = 480
    private var currentKernel = PlayerKernel.AUTO
    private var currentDecode = DecodePath.AUTO
    private var roundSwitchReady = false
    private var updateChecking = false
    private var downloadJob: Job? = null
    private val downloadCancel = AtomicBoolean(false)

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
            currentKernel = container.settings.playerKernel.first()
            currentDecode = container.settings.decodePath.first()
            refreshQualityChips()
            refreshKernelChips()
            refreshDecodeChips()
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
        binding.kernelAuto.setOnClickListener { pickKernel(PlayerKernel.AUTO) }
        binding.kernelMedia3.setOnClickListener { pickKernel(PlayerKernel.MEDIA3) }
        binding.kernelIjk.setOnClickListener { pickKernel(PlayerKernel.IJK) }
        binding.decodeAuto.setOnClickListener { pickDecode(DecodePath.AUTO) }
        binding.decodeHw.setOnClickListener { pickDecode(DecodePath.HARDWARE) }
        binding.decodeSw.setOnClickListener { pickDecode(DecodePath.SOFTWARE) }

        binding.checkUpdateButton.setOnClickListener { checkForUpdate() }
        binding.uploadDiagButton.setOnClickListener { uploadDiagnostics() }
        binding.decodeTestButton.setOnClickListener {
            startActivity(Intent(this, DecodeTestActivity::class.java))
        }

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
        if (WatchUi.isCompactWatch(this)) WatchUi.applyImmersive(this)
    }

    override fun onDestroy() {
        downloadCancel.set(true)
        downloadJob?.cancel()
        super.onDestroy()
    }

    private fun watchDialogBuilder(): AlertDialog.Builder {
        return AlertDialog.Builder(this, R.style.Theme_HongguoWatch_AlertDialog)
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
                    showLatestDialog()
                    return@onSuccess
                }
                showUpdateAvailableDialog(result)
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

    private fun showLatestDialog() {
        watchDialogBuilder()
            .setTitle(R.string.update_latest_title)
            .setMessage(getString(R.string.update_latest, BuildConfig.VERSION_NAME))
            .setPositiveButton(R.string.update_ok, null)
            .show()
    }

    private fun showUpdateAvailableDialog(result: UpdateChecker.Result) {
        val notes = result.body.ifBlank { "前往发布页查看说明。" }.take(280)
        val message = getString(
            R.string.update_available_body,
            BuildConfig.VERSION_NAME,
            result.versionName,
            notes,
        )
        val pageUrl = result.htmlUrl.ifBlank { UpdateChecker.releasesPageUrl() }
        val builder = watchDialogBuilder()
            .setTitle(getString(R.string.update_available_title, result.versionName))
            .setMessage(message)
            .setNegativeButton(R.string.update_later, null)
            .setNeutralButton(R.string.update_open_page) { _, _ -> openUrl(pageUrl) }

        if (!result.apkUrl.isNullOrBlank()) {
            val existing = UpdateApkInstaller.resolveLocalFile(
                this,
                result.apkName,
                result.versionName,
            )
            if (existing.exists() && existing.length() > 0L) {
                builder.setPositiveButton(R.string.update_install_apk) { _, _ ->
                    installApk(existing)
                }
            } else {
                builder.setPositiveButton(R.string.update_download_apk) { _, _ ->
                    startApkDownload(result)
                }
            }
        } else {
            builder.setPositiveButton(R.string.update_open_page) { _, _ -> openUrl(pageUrl) }
        }
        builder.show()
    }

    private fun startApkDownload(result: UpdateChecker.Result) {
        val apkUrl = result.apkUrl
        if (apkUrl.isNullOrBlank()) {
            Toast.makeText(this, R.string.update_no_apk, Toast.LENGTH_LONG).show()
            return
        }
        downloadJob?.cancel()
        downloadCancel.set(false)

        val content = LayoutInflater.from(this).inflate(R.layout.dialog_update_progress, null)
        val progressBar = content.findViewById<ProgressBar>(R.id.progressBar)
        val progressLabel = content.findViewById<TextView>(R.id.progressLabel)
        progressBar.progress = 0
        progressLabel.text = getString(R.string.update_downloading_body, 0)

        val dialog = watchDialogBuilder()
            .setTitle(R.string.update_downloading_title)
            .setView(content)
            .setNegativeButton(R.string.update_cancel) { _, _ ->
                downloadCancel.set(true)
                downloadJob?.cancel()
            }
            .setCancelable(false)
            .create()
        dialog.show()

        downloadJob = lifecycleScope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    UpdateApkInstaller.download(
                        context = this@SettingsActivity,
                        url = apkUrl,
                        apkName = result.apkName,
                        versionName = result.versionName,
                        cancel = downloadCancel,
                    ) { percent ->
                        runOnUiThread {
                            if (!isDestroyed && dialog.isShowing) {
                                progressBar.progress = percent
                                progressLabel.text =
                                    getString(R.string.update_downloading_body, percent)
                            }
                        }
                    }
                }
            }
            if (!isActive) {
                if (dialog.isShowing) dialog.dismiss()
                return@launch
            }
            if (dialog.isShowing) dialog.dismiss()
            outcome.onSuccess { file ->
                showDownloadDoneDialog(file)
            }.onFailure { err ->
                if (err is InterruptedException || downloadCancel.get()) return@onFailure
                Toast.makeText(
                    this@SettingsActivity,
                    getString(
                        R.string.update_download_failed,
                        err.message ?: getString(R.string.error_network),
                    ),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun showDownloadDoneDialog(apkFile: File) {
        val message = apkFile.name + "\n\n" + getString(R.string.update_signing_note)
        watchDialogBuilder()
            .setTitle(R.string.update_download_done)
            .setMessage(message)
            .setNegativeButton(R.string.update_later, null)
            .setPositiveButton(R.string.update_install_apk) { _, _ -> installApk(apkFile) }
            .show()
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
                watchDialogBuilder()
                    .setTitle(R.string.diag_uploaded_title)
                    .setMessage(getString(R.string.diag_uploaded_body, result.url))
                    .setNegativeButton(R.string.update_ok, null)
                    .setPositiveButton(R.string.diag_copy_link) { _, _ ->
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("diag", result.url))
                        Toast.makeText(this@SettingsActivity, R.string.update_ok, Toast.LENGTH_SHORT)
                            .show()
                    }
                    .show()
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

    private fun installApk(apkFile: File) {
        runCatching {
            UpdateApkInstaller.install(this, apkFile)
        }.onFailure { err ->
            if (err is SecurityException) {
                Toast.makeText(this, R.string.update_install_permission, Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(
                    this,
                    getString(
                        R.string.update_install_failed,
                        err.message ?: getString(R.string.error_generic),
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

    private fun pickKernel(kernel: PlayerKernel) {
        lifecycleScope.launch {
            container.settings.setPlayerKernel(kernel)
            currentKernel = kernel
            refreshKernelChips()
        }
    }

    private fun pickDecode(path: DecodePath) {
        lifecycleScope.launch {
            container.settings.setDecodePath(path)
            currentDecode = path
            refreshDecodeChips()
        }
    }

    private fun refreshQualityChips() {
        binding.quality480.isSelected = currentQuality == 480
        binding.quality720.isSelected = currentQuality == 720
        binding.quality1080.isSelected = currentQuality == 1080
    }

    private fun refreshKernelChips() {
        binding.kernelAuto.isSelected = currentKernel == PlayerKernel.AUTO
        binding.kernelMedia3.isSelected = currentKernel == PlayerKernel.MEDIA3
        binding.kernelIjk.isSelected = currentKernel == PlayerKernel.IJK
    }

    private fun refreshDecodeChips() {
        binding.decodeAuto.isSelected = currentDecode == DecodePath.AUTO
        binding.decodeHw.isSelected = currentDecode == DecodePath.HARDWARE
        binding.decodeSw.isSelected = currentDecode == DecodePath.SOFTWARE
    }
}
