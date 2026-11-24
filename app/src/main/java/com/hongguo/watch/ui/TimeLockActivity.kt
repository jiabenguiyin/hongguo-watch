package com.hongguo.watch.ui

import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.hongguo.watch.HongguoWatchApp
import com.hongguo.watch.R
import com.hongguo.watch.data.TimeLockStore
import com.hongguo.watch.databinding.ActivityTimeLockBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 手表深色「时间管理」：时间锁开关、单日时长、PIN 确认。 */
class TimeLockActivity : WatchDisplayActivity() {
    private lateinit var binding: ActivityTimeLockBinding
    private lateinit var toolbarClock: WatchToolbarClock
    private val timeLock get() = (application as HongguoWatchApp).container.timeLock
    private var suppressSwitch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTimeLockBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (WatchUi.isCompactWatch(this)) WatchUi.applyImmersive(this)
        WatchUi.applySafePadding(binding.timeLockRoot, topChrome = binding.timeLockToolbar)
        toolbarClock = WatchToolbarClock(this, binding.toolbarClock)

        binding.backButton.setOnClickListener { finish() }
        binding.dailyLimitButton.setOnClickListener { showLimitDialog() }
        binding.timeLockSwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressSwitch) return@setOnCheckedChangeListener
            if (checked) {
                // 先收回开关，设好 PIN 再打开
                setSwitchQuiet(false)
                promptEnableWithPin()
            } else {
                setSwitchQuiet(true)
                promptDisableWithPin()
            }
        }
        binding.restReminderSwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressSwitch) return@setOnCheckedChangeListener
            lifecycleScope.launch { timeLock.setRestReminder(checked) }
        }
        binding.sleepReminderSwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressSwitch) return@setOnCheckedChangeListener
            lifecycleScope.launch { timeLock.setSleepReminder(checked) }
        }
        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        if (recreateForDisplaySizeChange()) return
        if (WatchUi.isCompactWatch(this)) WatchUi.applyImmersive(this)
        WatchUi.applySafePadding(binding.timeLockRoot, topChrome = binding.timeLockToolbar)
        refreshUi()
    }

    private fun setSwitchQuiet(checked: Boolean) {
        suppressSwitch = true
        binding.timeLockSwitch.isChecked = checked
        suppressSwitch = false
    }

    private fun refreshUi() {
        lifecycleScope.launch {
            val enabled = timeLock.enabledNow()
            val limit = timeLock.dailyLimitMinutesNow()
            val used = timeLock.usedMsToday()
            val rest = timeLock.restReminder.first()
            val sleep = timeLock.sleepReminder.first()
            suppressSwitch = true
            binding.timeLockSwitch.isChecked = enabled
            binding.restReminderSwitch.isChecked = rest
            binding.sleepReminderSwitch.isChecked = sleep
            suppressSwitch = false
            binding.dailyLimitStatus.text = TimeLockStore.formatLimitLabel(limit)
            val usedMin = (used / 60_000L).toInt()
            binding.usageLabel.text = getString(
                R.string.time_lock_usage_today,
                usedMin,
                TimeLockStore.formatLimitLabel(limit),
            )
        }
    }

    private fun promptEnableWithPin() {
        askPin(
            title = R.string.time_lock_set_pin_title,
            hint = R.string.time_lock_set_pin_hint,
            positive = R.string.update_ok,
        ) { firstPin ->
            confirmEnablePin(firstPin)
        }
    }

    private fun confirmEnablePin(firstPin: String) {
        askPin(
            title = R.string.time_lock_confirm_pin_title,
            hint = R.string.time_lock_confirm_pin_hint,
            positive = R.string.time_lock_enable_action,
        ) { secondPin ->
            if (firstPin != secondPin) {
                Toast.makeText(this, R.string.time_lock_pin_mismatch, Toast.LENGTH_SHORT).show()
                return@askPin
            }
            lifecycleScope.launch {
                timeLock.enableWithPin(firstPin)
                Toast.makeText(this@TimeLockActivity, R.string.time_lock_enabled_toast, Toast.LENGTH_SHORT)
                    .show()
                refreshUi()
            }
        }
    }

    private fun promptDisableWithPin() {
        askPin(
            title = R.string.time_lock_enter_pin_title,
            hint = R.string.time_lock_set_pin_hint,
            positive = android.R.string.ok,
            onCancel = { refreshUi() },
        ) { pin ->
            lifecycleScope.launch {
                if (timeLock.disable(pin)) {
                    Toast.makeText(this@TimeLockActivity, R.string.time_lock_disabled_toast, Toast.LENGTH_SHORT)
                        .show()
                    refreshUi()
                } else {
                    Toast.makeText(this@TimeLockActivity, R.string.time_lock_pin_wrong, Toast.LENGTH_SHORT).show()
                    refreshUi()
                }
            }
        }
    }

    private fun showLimitDialog() {
        val options = TimeLockStore.LIMIT_OPTIONS_MINUTES
        val labels = options.map { TimeLockStore.formatLimitLabel(it) }.toTypedArray()
        lifecycleScope.launch {
            val current = timeLock.dailyLimitMinutesNow()
            val checked = options.indexOf(TimeLockStore.normalizeLimit(current)).coerceAtLeast(0)
            val dialog = AlertDialog.Builder(this@TimeLockActivity, R.style.Theme_HongguoWatch_AlertDialog)
                .setTitle(R.string.time_lock_daily_limit)
                .setSingleChoiceItems(labels, checked) { chooser, index ->
                    chooser.dismiss()
                    lifecycleScope.launch {
                        timeLock.setDailyLimitMinutes(options[index])
                        refreshUi()
                    }
                }
                .setNegativeButton(R.string.update_cancel, null)
                .create()
            showWatchDialog(dialog)
        }
    }

    private fun askPin(
        title: Int,
        hint: Int,
        positive: Int,
        onCancel: (() -> Unit)? = null,
        onOk: (String) -> Unit,
    ) {
        val pad = (12 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(4))
            setHint(hint)
            setTextColor(ContextCompat.getColor(this@TimeLockActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@TimeLockActivity, R.color.text_secondary))
            setPadding(pad, pad, pad, pad)
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(input)
        }
        val dialog = AlertDialog.Builder(this, R.style.Theme_HongguoWatch_AlertDialog)
            .setTitle(title)
            .setView(column)
            .setNegativeButton(R.string.update_cancel) { _, _ -> onCancel?.invoke() }
            .setPositiveButton(positive) { _, _ ->
                val pin = input.text?.toString().orEmpty()
                if (!TimeLockStore.isValidPin(pin)) {
                    Toast.makeText(this, R.string.time_lock_set_pin_hint, Toast.LENGTH_SHORT).show()
                    onCancel?.invoke()
                    return@setPositiveButton
                }
                onOk(pin)
            }
            .setOnCancelListener { onCancel?.invoke() }
            .create()
        showWatchDialog(dialog)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        input.requestFocus()
    }

    private fun showWatchDialog(dialog: AlertDialog): AlertDialog {
        dialog.show()
        dialog.window?.let { window ->
            val width = WatchUi.dialogContentWidthPx(this)
            window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
            window.setBackgroundDrawableResource(R.drawable.bg_alert_dialog)
        }
        return dialog
    }
}
