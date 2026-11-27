package com.hongguo.watch.ui

import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import com.hongguo.watch.BuildConfig
import com.hongguo.watch.HongguoWatchApp
import com.hongguo.watch.R
import com.hongguo.watch.data.UpdateApkInstaller
import com.hongguo.watch.data.UpdateChecker
import com.hongguo.watch.data.UpdateMirrorPolicy
import com.hongguo.watch.databinding.DialogUpdateAvailableBinding
import com.hongguo.watch.databinding.DialogUpdateProgressBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** The same update, download and install UI for startup and Settings. */
internal class UpdateDialogs(
    private val activity: WatchDisplayActivity,
    private val showWatchDialog: (AlertDialog) -> Unit,
) : DefaultLifecycleObserver {
    private var activeDialog: AlertDialog? = null
    private var downloadJob: Job? = null
    private var downloadCancellation: AtomicBoolean? = null

    init {
        activity.lifecycle.addObserver(this)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        downloadCancellation?.set(true)
        downloadJob?.cancel()
        activeDialog?.dismiss()
    }

    fun showLatest() {
        show(
            builder()
                .setTitle(R.string.update_latest_title)
                .setMessage(activity.getString(R.string.update_latest, BuildConfig.VERSION_NAME))
                .setPositiveButton(R.string.update_ok, null)
                .create(),
        )
    }

    fun showAvailable(result: UpdateChecker.Result) {
        val views = DialogUpdateAvailableBinding.inflate(activity.layoutInflater)
        views.updateTitle.text = activity.getString(R.string.update_available_title, result.versionName)
        views.updateMeta.text = activity.getString(
            R.string.update_available_meta,
            BuildConfig.VERSION_NAME,
            result.versionName,
        )
        views.updateNotes.text = result.body.ifBlank { activity.getString(R.string.update_notes_missing) }.take(280)

        val dialog = builder().setView(views.root).create()
        val pageUrl = result.htmlUrl.ifBlank { UpdateChecker.releasesPageUrl() }
        if (result.apkUrl.isNullOrBlank()) {
            views.updatePrimaryButton.setText(R.string.update_open_page)
            views.updatePrimaryButton.setOnClickListener {
                dialog.dismiss()
                openUrl(pageUrl)
            }
            views.updateTertiaryButton.visibility = View.GONE
            (views.updateSecondaryButton.layoutParams as android.widget.LinearLayout.LayoutParams).marginEnd = 0
        } else {
            val existing = UpdateApkInstaller.resolveLocalFile(activity, result.apkName, result.versionName)
            val downloaded = existing.exists() && existing.length() > 0L
            views.updatePrimaryButton.setText(if (downloaded) R.string.update_install_apk else R.string.update_download_apk)
            views.updatePrimaryButton.setOnClickListener {
                dialog.dismiss()
                if (downloaded) installApk(existing) else startDownload(result)
            }
            views.updateTertiaryButton.visibility = View.VISIBLE
            views.updateTertiaryButton.setOnClickListener {
                dialog.dismiss()
                openUrl(pageUrl)
            }
        }
        views.updateSecondaryButton.setOnClickListener { dialog.dismiss() }
        show(dialog)
        views.updateNotesScroll.post {
            val maxHeight = WatchUi.updateNotesMaxHeightPx(activity)
            if (views.updateNotes.height > maxHeight) {
                views.updateNotesScroll.layoutParams = views.updateNotesScroll.layoutParams.apply {
                    height = maxHeight
                }
            }
        }
    }

    private fun startDownload(result: UpdateChecker.Result) {
        val url = result.apkUrl
        if (url.isNullOrBlank()) {
            Toast.makeText(activity, R.string.update_no_apk, Toast.LENGTH_LONG).show()
            return
        }
        downloadCancellation?.set(true)
        downloadJob?.cancel()
        val cancel = AtomicBoolean(false)
        downloadCancellation = cancel

        val views = DialogUpdateProgressBinding.inflate(activity.layoutInflater)
        views.progressLabel.text = activity.getString(R.string.update_downloading_body, 0)
        val dialog = builder()
            .setTitle(R.string.update_downloading_title)
            .setView(views.root)
            .setNegativeButton(R.string.update_cancel) { _, _ ->
                cancel.set(true)
                downloadJob?.cancel()
            }
            .setCancelable(false)
            .create()
        show(dialog)

        downloadJob = activity.lifecycleScope.launch {
            try {
                val preference = (activity.application as HongguoWatchApp)
                    .container.settings.updateMirrorPreferenceNow()
                val candidates = UpdateMirrorPolicy.downloadCandidateUrls(preference, url)
                val file = withContext(Dispatchers.IO) {
                    UpdateApkInstaller.download(
                        context = activity.applicationContext,
                        url = url,
                        apkName = result.apkName,
                        versionName = result.versionName,
                        cancel = cancel,
                        urls = candidates,
                    ) { percent ->
                        activity.runOnUiThread {
                            if (!activity.isDestroyed && dialog.isShowing) {
                                views.progressBar.progress = percent
                                views.progressLabel.text = activity.getString(R.string.update_downloading_body, percent)
                            }
                        }
                    }
                }
                activity.lifecycle.withResumed {
                    if (!activity.isFinishing) {
                        dialog.dismiss()
                        showDownloadDone(file)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!cancel.get() && error !is InterruptedException) {
                    val message = activity.getString(
                        R.string.update_download_failed,
                        error.message ?: activity.getString(R.string.error_network),
                    )
                    Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
                }
            } finally {
                if (dialog.isShowing) dialog.dismiss()
            }
        }
    }

    private fun showDownloadDone(file: File) {
        val message = file.name + "\n\n" + activity.getString(R.string.update_signing_note)
        show(
            builder()
                .setTitle(R.string.update_download_done)
                .setMessage(message)
                .setNegativeButton(R.string.update_later, null)
                .setPositiveButton(R.string.update_install_apk) { _, _ -> installApk(file) }
                .create(),
        )
    }

    private fun installApk(file: File) {
        runCatching { UpdateApkInstaller.install(activity, file) }.onFailure { error ->
            val message = if (error is SecurityException) activity.getString(R.string.update_install_permission)
            else activity.getString(R.string.update_install_failed, error.message ?: activity.getString(R.string.error_generic))
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun openUrl(url: String) {
        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.onFailure {
            Toast.makeText(activity, R.string.error_generic, Toast.LENGTH_SHORT).show()
        }
    }

    private fun builder() = AlertDialog.Builder(activity, R.style.Theme_HongguoWatch_AlertDialog)

    private fun show(dialog: AlertDialog) {
        activeDialog = dialog
        showWatchDialog(dialog)
    }
}
