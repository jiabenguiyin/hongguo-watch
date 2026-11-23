package com.hongguo.watch.ui

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.hongguo.watch.HongguoWatchApp
import com.hongguo.watch.R
import com.hongguo.watch.data.db.FavoriteEntity
import com.hongguo.watch.data.db.WatchHistoryEntity
import com.hongguo.watch.data.redfruit.RedfruitAccountError
import com.hongguo.watch.data.redfruit.RedfruitAccountState
import com.hongguo.watch.data.redfruit.RedfruitInitialMergeChoice
import com.hongguo.watch.data.redfruit.RedfruitLoginMode
import com.hongguo.watch.data.redfruit.RedfruitWriteSyncStatus
import com.hongguo.watch.databinding.ActivityAccountSyncBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AccountSyncActivity : WatchDisplayActivity() {
    private lateinit var binding: ActivityAccountSyncBinding
    private val container get() = (application as HongguoWatchApp).container
    private val account get() = container.redfruitAccount
    private val cloudAdapter by lazy { RedfruitCloudAdapter(::openCloudRow) }
    private var cloudMode: CloudMode? = null
    private var visibleLoginMode: RedfruitLoginMode? = null
    private var shownQr: ByteArray? = null
    private var initialMergePromptShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAccountSyncBinding.inflate(layoutInflater)
        setContentView(binding.root)
        cloudMode = CloudMode.entries.firstOrNull { it.name == savedInstanceState?.getString(STATE_CLOUD_MODE) }
        if (WatchUi.isCompactWatch(this)) WatchUi.applyImmersive(this)
        WatchUi.applySafePadding(binding.accountSyncRoot, topChrome = binding.accountToolbar)
        binding.accountScroll.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) resizeQr()
        }
        WatchToolbarClock(this, binding.toolbarClock)

        binding.cloudLibraryList.layoutManager = LinearLayoutManager(this)
        binding.cloudLibraryList.adapter = cloudAdapter
        onBackPressedDispatcher.addCallback(this) { navigateBack() }
        binding.backButton.setOnClickListener { navigateBack() }
        binding.pairButton.setOnClickListener { account.startQr() }
        binding.phoneButton.setOnClickListener { account.startPhone() }
        binding.retryQrButton.setOnClickListener { account.startQr() }
        binding.cancelPairingButton.setOnClickListener { account.cancelLogin() }
        binding.cancelPhoneButton.setOnClickListener {
            hideKeyboard()
            account.cancelLogin()
        }
        binding.sendSmsButton.setOnClickListener {
            hideKeyboard()
            account.sendSms(binding.phoneNumber.text?.toString().orEmpty())
        }
        binding.verifySmsButton.setOnClickListener {
            hideKeyboard()
            account.verifySms(binding.smsCode.text?.toString().orEmpty())
        }
        binding.checkMfaButton.setOnClickListener { account.checkMfa() }
        binding.cloudHistoryButton.setOnClickListener { showCloud(CloudMode.HISTORY) }
        binding.cloudFavoritesButton.setOnClickListener { showCloud(CloudMode.FAVORITES) }
        binding.cloudHistoryTab.setOnClickListener { showCloud(CloudMode.HISTORY) }
        binding.cloudFavoritesTab.setOnClickListener { showCloud(CloudMode.FAVORITES) }
        binding.syncButton.setOnClickListener { account.refresh() }
        binding.cloudRefreshButton.setOnClickListener { account.refresh() }
        binding.syncLocalButton.setOnClickListener { confirmLocalSync() }
        binding.autoSyncSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked && !account.status.value.autoSyncEnabled) {
                binding.autoSyncSwitch.isChecked = false
                confirmEnableAutoSync()
            } else {
                account.setAutoSyncEnabled(checked)
            }
        }
        binding.signOutButton.setOnClickListener { confirmSignOut() }

        render(account.status.value)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { account.status.collect(::render) }
                launch {
                    while (isActive) {
                        val state = account.status.value
                        if (state.mode == RedfruitLoginMode.PHONE && state.smsRetryAt > 0) updateSmsButton(state)
                        delay(1_000)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        account.onForeground()
        recreateForDisplaySizeChange()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_CLOUD_MODE, cloudMode?.name)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && WatchUi.isCompactWatch(this)) WatchUi.applyImmersive(this)
    }

    private fun navigateBack() {
        if (binding.cloudLibraryPanel.isVisible) {
            cloudMode = null
            render(account.status.value)
            return
        }
        if (account.status.value.mode != RedfruitLoginMode.CHOICE) {
            hideKeyboard()
            account.cancelLogin()
        }
        finish()
    }

    private fun render(state: RedfruitAccountState) {
        if (!::binding.isInitialized) return
        val signedIn = !state.accountName.isNullOrBlank()
        val qrMode = !signedIn && state.mode == RedfruitLoginMode.QR
        val phoneMode = !signedIn && state.mode == RedfruitLoginMode.PHONE
        val hasQr = qrMode && state.qrImage != null
        if (visibleLoginMode != state.mode) {
            visibleLoginMode = state.mode
            binding.accountScroll.scrollTo(0, 0)
            if (!phoneMode) binding.smsCode.text?.clear()
        }
        binding.accountStatus.isVisible = !signedIn && state.mode == RedfruitLoginMode.CHOICE
        binding.accountStatus.setText(
            if (state.restoring) R.string.account_sync_busy else R.string.redfruit_login_prompt,
        )
        binding.syncBusy.isVisible = !signedIn && state.busy && !hasQr
        binding.syncBusy.setText(if (qrMode) R.string.redfruit_qr_loading else R.string.account_sync_busy)
        binding.syncError.isVisible = state.error != null
        state.error?.let { binding.syncError.setText(errorText(it)) }

        binding.loginMethods.isVisible = !signedIn && !state.restoring && state.mode == RedfruitLoginMode.CHOICE
        binding.pairButton.isEnabled = !state.busy
        binding.phoneButton.isEnabled = !state.busy
        binding.pairButton.alpha = if (state.busy) 0.55f else 1f
        binding.phoneButton.alpha = if (state.busy) 0.55f else 1f

        binding.pairingPanel.isVisible = qrMode
        binding.pairQr.isVisible = hasQr
        binding.retryQrButton.isVisible = qrMode && !hasQr && !state.busy
        binding.pairInstructions.isVisible = hasQr
        binding.pairInstructions.setText(
            if (state.qrScanned) R.string.redfruit_qr_scanned else R.string.redfruit_qr_instructions,
        )
        showQr(state.qrImage)

        binding.phonePanel.isVisible = phoneMode
        binding.phoneNumber.isEnabled = !state.busy && !state.smsRequested
        if (phoneMode) updateSmsButton(state)
        binding.smsCode.isVisible = state.smsRequested
        binding.verifySmsButton.isVisible = state.smsRequested
        binding.verifySmsButton.isEnabled = !state.busy
        binding.mfaMessage.isVisible = state.mfa != null
        binding.checkMfaButton.isVisible = state.mfa != null
        binding.checkMfaButton.isEnabled = !state.busy
        state.mfa?.let {
            binding.mfaMessage.text = getString(R.string.redfruit_sms_challenge, it.number, it.content)
        }

        if (!signedIn && !state.restoring) cloudMode = null
        val section = cloudMode.takeIf { signedIn }
        val inCloud = section != null
        binding.accountScroll.isVisible = !inCloud
        binding.cloudLibraryPanel.isVisible = inCloud
        binding.toolbarClock.isVisible = !inCloud
        binding.cloudRefreshButton.isVisible = inCloud
        binding.accountTitle.setText(
            when {
                inCloud -> R.string.redfruit_cloud_title
                qrMode -> R.string.redfruit_qr_login
                phoneMode -> R.string.redfruit_phone_login
                else -> R.string.account_sync_title
            },
        )

        binding.accountDashboard.isVisible = signedIn && !inCloud
        if (!signedIn) cloudAdapter.submitList(emptyList())
        if (section != null) renderCloud(state, section)
        else if (signedIn) renderDashboard(state)
    }

    private fun renderDashboard(state: RedfruitAccountState) {
        binding.accountName.text = getString(R.string.account_sync_signed_in, state.accountName.orEmpty())
        val failed = state.historyFailed || state.favoritesFailed
        binding.cloudOverviewStatus.setText(
            when {
                state.historyLoading || state.favoritesLoading -> R.string.redfruit_cloud_refreshing
                state.historyFailed && state.favoritesFailed -> R.string.redfruit_cloud_failed
                failed -> R.string.redfruit_cloud_partial
                state.history != null && state.favorites != null -> R.string.redfruit_cloud_ready
                else -> R.string.redfruit_cloud_pending
            },
        )
        binding.cloudHistoryButton.text = cloudLabel(R.string.redfruit_cloud_history, state.history)
        binding.cloudFavoritesButton.text = cloudLabel(R.string.redfruit_cloud_favorites, state.favorites)
        if (binding.autoSyncSwitch.isChecked != state.autoSyncEnabled) {
            binding.autoSyncSwitch.isChecked = state.autoSyncEnabled
        }
        val syncBusy = state.writeSyncStatus == RedfruitWriteSyncStatus.RUNNING
        binding.autoSyncSwitch.isEnabled = !state.busy && !syncBusy
        binding.syncLocalButton.isEnabled = !state.busy && !syncBusy
        binding.syncLocalButton.alpha = if (state.busy || syncBusy) 0.55f else 1f
        if (state.writeSyncStatus == RedfruitWriteSyncStatus.SUCCESS &&
            (state.uploadedHistoryCount > 0 || state.uploadedFavoriteCount > 0)
        ) {
            binding.writeSyncStatus.text = getString(
                R.string.redfruit_sync_success_counts,
                state.uploadedHistoryCount,
                state.uploadedFavoriteCount,
            )
        } else {
            binding.writeSyncStatus.setText(
                when (state.writeSyncStatus) {
                    RedfruitWriteSyncStatus.DISABLED -> R.string.redfruit_sync_disabled
                    RedfruitWriteSyncStatus.IDLE -> if (state.autoSyncEnabled) {
                        R.string.redfruit_sync_enabled
                    } else {
                        R.string.redfruit_sync_disabled
                    }
                    RedfruitWriteSyncStatus.RUNNING -> R.string.redfruit_sync_running
                    RedfruitWriteSyncStatus.SUCCESS -> R.string.redfruit_sync_success
                    RedfruitWriteSyncStatus.FAILED -> R.string.redfruit_sync_failed
                },
            )
        }
        binding.syncButton.isEnabled = !state.busy && !syncBusy
        binding.syncButton.alpha = if (state.busy || syncBusy) 0.55f else 1f
        binding.signOutButton.isEnabled = !state.busy && !syncBusy
        binding.signOutButton.alpha = if (state.busy || syncBusy) 0.55f else 1f

        val recent = state.history.orEmpty().firstOrNull { it.episodeId.isNotBlank() || it.episodeNumber > 0 }
        binding.cloudResumeButton.isVisible = recent != null
        binding.cloudResumeButton.setOnClickListener(
            recent?.let { item -> android.view.View.OnClickListener { openCloudRow(historyRow(item)) } },
        )
        if (recent != null) {
            binding.cloudResumeButton.text = getString(R.string.redfruit_cloud_continue_title, recent.seriesTitle)
        }
        if (!state.needsInitialMergeChoice) {
            initialMergePromptShown = false
        } else {
            if (state.writeSyncStatus == RedfruitWriteSyncStatus.FAILED && !state.busy) {
                initialMergePromptShown = false
            }
            if (
                !state.accountName.isNullOrBlank() &&
                !state.busy &&
                state.writeSyncStatus != RedfruitWriteSyncStatus.RUNNING &&
                !initialMergePromptShown
            ) {
                showInitialMergePrompt(state)
            }
        }
    }

    private fun showInitialMergePrompt(state: RedfruitAccountState) {
        initialMergePromptShown = true
        val message = getString(
            R.string.redfruit_initial_merge_message,
            state.localHistoryCount,
            state.localFavoriteCount,
        )
        val dialog = AlertDialog.Builder(this, R.style.Theme_HongguoWatch_AlertDialog)
            .setTitle(R.string.redfruit_initial_merge_title)
            .setMessage(message)
            .setPositiveButton(R.string.redfruit_initial_merge) { _, _ ->
                account.resolveInitialMerge(RedfruitInitialMergeChoice.MERGE)
            }
            .setNegativeButton(R.string.redfruit_cloud_only) { _, _ ->
                account.resolveInitialMerge(RedfruitInitialMergeChoice.CLOUD_ONLY)
            }
            .setNeutralButton(R.string.redfruit_initial_merge_later, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isAllCaps = false
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.isAllCaps = false
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.isAllCaps = false
        }
        dialog.show()
        dialog.window?.setLayout(WatchUi.dialogContentWidthPx(this), WindowManager.LayoutParams.WRAP_CONTENT)
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_alert_dialog)
    }

    private fun confirmSignOut() {
        val dialog = AlertDialog.Builder(this, R.style.Theme_HongguoWatch_AlertDialog)
            .setTitle(R.string.redfruit_cloud_sign_out_confirm)
            .setMessage(R.string.redfruit_cloud_sign_out_message)
            .setNegativeButton(R.string.update_cancel, null)
            .setPositiveButton(R.string.account_sync_sign_out) { _, _ -> account.signOut() }
            .show()
        dialog.window?.setLayout(WatchUi.dialogContentWidthPx(this), WindowManager.LayoutParams.WRAP_CONTENT)
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_alert_dialog)
    }

    private fun confirmEnableAutoSync() {
        val dialog = AlertDialog.Builder(this, R.style.Theme_HongguoWatch_AlertDialog)
            .setTitle(R.string.redfruit_auto_sync_confirm_title)
            .setMessage(R.string.redfruit_auto_sync_confirm_message)
            .setNegativeButton(R.string.update_cancel, null)
            .setPositiveButton(R.string.redfruit_auto_sync) { _, _ -> account.setAutoSyncEnabled(true) }
            .show()
        dialog.window?.setLayout(WatchUi.dialogContentWidthPx(this), WindowManager.LayoutParams.WRAP_CONTENT)
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_alert_dialog)
    }

    private fun confirmLocalSync() {
        val dialog = AlertDialog.Builder(this, R.style.Theme_HongguoWatch_AlertDialog)
            .setTitle(R.string.redfruit_sync_local_confirm_title)
            .setMessage(R.string.redfruit_sync_local_confirm_message)
            .setNegativeButton(R.string.update_cancel, null)
            .setPositiveButton(R.string.redfruit_sync_local) { _, _ -> account.syncLocalNow() }
            .show()
        dialog.window?.setLayout(WatchUi.dialogContentWidthPx(this), WindowManager.LayoutParams.WRAP_CONTENT)
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_alert_dialog)
    }

    private fun updateSmsButton(state: RedfruitAccountState) {
        val remaining = state.smsRetryAt - SystemClock.elapsedRealtime()
        val retrySeconds = (remaining.coerceAtLeast(0) + 999) / 1_000
        binding.sendSmsButton.isEnabled = !state.busy && remaining <= 0
        val label = if (retrySeconds > 0) getString(R.string.redfruit_send_delay, retrySeconds)
            else getString(R.string.redfruit_send_sms)
        if (binding.sendSmsButton.text.toString() != label) binding.sendSmsButton.text = label
    }

    private fun showCloud(mode: CloudMode) {
        if (cloudMode == mode) return
        cloudMode = mode
        render(account.status.value)
        binding.cloudLibraryList.scrollToPosition(0)
    }

    private fun renderCloud(state: RedfruitAccountState, mode: CloudMode) {
        val history = mode == CloudMode.HISTORY
        val rows = if (history) state.history?.map(::historyRow) else state.favorites?.map(::favoriteRow)
        val loading = if (history) state.historyLoading else state.favoritesLoading
        val failed = if (history) state.historyFailed else state.favoritesFailed
        val hasRows = !rows.isNullOrEmpty()
        val message = when {
            loading -> getString(R.string.redfruit_cloud_refreshing)
            failed && hasRows -> getString(R.string.redfruit_cloud_stale, rows.orEmpty().size)
            failed -> getString(R.string.redfruit_cloud_failed)
            rows == null -> getString(R.string.redfruit_cloud_pending)
            rows.isEmpty() -> getString(
                if (history) R.string.redfruit_cloud_history_empty else R.string.redfruit_cloud_favorites_empty,
            )
            else -> null
        }
        binding.cloudLibraryList.isVisible = hasRows
        cloudAdapter.submitList(rows.orEmpty())
        binding.cloudLibraryStatus.isVisible = hasRows && message != null
        binding.cloudLibraryStatus.text = message
        binding.cloudEmpty.isVisible = !hasRows && message != null
        binding.cloudEmpty.text = message

        val active = ContextCompat.getColor(this, R.color.text_primary)
        val inactive = ContextCompat.getColor(this, R.color.text_secondary)
        binding.cloudHistoryTab.isSelected = history
        binding.cloudFavoritesTab.isSelected = !history
        binding.cloudHistoryTab.setTextColor(if (history) active else inactive)
        binding.cloudFavoritesTab.setTextColor(if (history) inactive else active)
        binding.cloudHistoryTab.text = cloudLabel(R.string.redfruit_cloud_history_tab, state.history)
        binding.cloudFavoritesTab.text = cloudLabel(R.string.redfruit_cloud_favorites_tab, state.favorites)
        binding.cloudRefreshButton.isEnabled = !state.busy
        binding.cloudRefreshButton.alpha = if (state.busy) 0.55f else 1f
    }

    private fun cloudLabel(label: Int, list: List<*>?): String =
        if (list == null) getString(label)
        else getString(R.string.redfruit_cloud_tab_count, getString(label), list.size)

    private fun historyRow(row: WatchHistoryEntity): RedfruitCloudRow {
        val progress = if (row.durationMs > 0) {
            ((row.positionMs.coerceIn(0, row.durationMs) * 100) / row.durationMs).toInt()
        } else {
            null
        }
        val episode = if (row.episodeNumber > 0) getString(R.string.redfruit_cloud_episode, row.episodeNumber)
            else getString(R.string.redfruit_cloud_history)
        return RedfruitCloudRow(
            seriesId = row.seriesId,
            title = row.seriesTitle,
            cover = row.cover,
            meta = episode,
            source = row.source,
            episodeId = row.episodeId,
            episodeNumber = row.episodeNumber,
            positionMs = container.repository.resumePositionMs(row),
            progressPercent = progress,
        )
    }

    private fun favoriteRow(row: FavoriteEntity): RedfruitCloudRow = RedfruitCloudRow(
        seriesId = row.seriesId,
        title = row.seriesTitle,
        cover = row.cover,
        meta = if (row.episodeCount > 0) "${row.episodeCount}集" else "云端收藏",
        source = row.source,
    )

    private fun openCloudRow(row: RedfruitCloudRow) {
        val intent = if (row.canResume) {
            Intent(this, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_SERIES_ID, row.seriesId)
                putExtra(PlayerActivity.EXTRA_SERIES_TITLE, row.title)
                putExtra(PlayerActivity.EXTRA_COVER, row.cover)
                putExtra(PlayerActivity.EXTRA_SOURCE, row.source)
                putExtra(PlayerActivity.EXTRA_EPISODE_ID, row.episodeId.takeIf { it.isNotBlank() })
                putExtra(PlayerActivity.EXTRA_EPISODE_NUMBER, row.episodeNumber)
                putExtra(PlayerActivity.EXTRA_START_POSITION_MS, row.positionMs)
            }
        } else {
            Intent(this, DetailActivity::class.java).apply {
                putExtra(DetailActivity.EXTRA_SERIES_ID, row.seriesId)
                putExtra(DetailActivity.EXTRA_SERIES_TITLE, row.title)
                putExtra(DetailActivity.EXTRA_COVER, row.cover)
                putExtra(DetailActivity.EXTRA_SOURCE, row.source)
            }
        }
        startActivity(intent)
    }

    private fun showQr(bytes: ByteArray?) {
        if (bytes == null) {
            if (shownQr != null) {
                shownQr = null
                binding.pairQr.setImageDrawable(null)
            }
            return
        }
        if (shownQr?.contentEquals(bytes) == true) return
        shownQr = bytes
        binding.pairQr.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
    }

    private fun resizeQr() {
        val scroll = binding.accountScroll
        if (scroll.width <= 0 || scroll.height <= 0) return
        val margin = resources.getDimensionPixelSize(R.dimen.watch_pad)
        val maxSize = WatchUi.dp(this, if (WatchUi.isCompactWatch(this)) 156f else 196f)
        // Leave space for the panel's inset so the entire code is visible before scrolling.
        val size = minOf(scroll.width - 2 * margin, maxOf(scroll.height - 2 * margin, WatchUi.dp(this, 96f)), maxSize)
            .coerceAtLeast(1)
        val params = binding.pairQr.layoutParams
        if (params.width != size || params.height != size) {
            params.width = size
            params.height = size
            binding.pairQr.layoutParams = params
        }
    }

    private fun hideKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(binding.root.windowToken, 0)
    }

    private fun errorText(error: RedfruitAccountError): Int = when (error) {
        RedfruitAccountError.NETWORK -> R.string.account_sync_network_error
        RedfruitAccountError.SERVICE -> R.string.account_sync_service_error
        RedfruitAccountError.QR_EXPIRED -> R.string.account_sync_pairing_expired
        RedfruitAccountError.PHONE_NUMBER -> R.string.redfruit_phone_number_error
        RedfruitAccountError.PHONE_CODE -> R.string.redfruit_phone_code_error
        RedfruitAccountError.PHONE_EXPIRED -> R.string.redfruit_phone_expired
        RedfruitAccountError.PHONE_WAIT -> R.string.redfruit_phone_wait
        RedfruitAccountError.VERIFY_REQUIRED -> R.string.redfruit_verify_required
        RedfruitAccountError.CLOUD_SESSION -> R.string.redfruit_account_unavailable
        RedfruitAccountError.STORAGE -> R.string.account_sync_storage_error
    }

    private enum class CloudMode { HISTORY, FAVORITES }

    private companion object {
        const val STATE_CLOUD_MODE = "redfruit_cloud_mode"
    }
}
