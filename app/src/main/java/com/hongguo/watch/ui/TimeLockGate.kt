package com.hongguo.watch.ui

import android.app.Activity
import android.text.InputFilter
import android.text.InputType
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.hongguo.watch.R
import com.hongguo.watch.data.TimeLockStore
import kotlinx.coroutines.launch

/**
 * 时间锁门禁（防沉迷）：每日观看额度用尽后锁定应用，直到输入 4 位 PIN。
 */
object TimeLockGate {
    @Volatile
    private var dialogShowing = false

    fun resetDialogFlag() {
        dialogShowing = false
    }

    /** 若已超额则弹出锁定对话框。 */
    fun enforceIfBlocked(
        activity: Activity,
        timeLock: TimeLockStore,
        onUnlocked: (() -> Unit)? = null,
        finishIfCancel: Boolean = false,
    ) {
        if (dialogShowing) return
        val owner = activity as? LifecycleOwner ?: return
        owner.lifecycleScope.launch {
            if (!timeLock.isBlocked()) return@launch
            if (dialogShowing) return@launch
            showLockDialog(
                activity = activity,
                timeLock = timeLock,
                onUnlocked = onUnlocked,
                onCancel = {
                    if (finishIfCancel) activity.finish()
                },
            )
        }
    }

    fun showLockDialog(
        activity: Activity,
        timeLock: TimeLockStore,
        onUnlocked: (() -> Unit)? = null,
        onCancel: (() -> Unit)? = null,
    ) {
        if (dialogShowing) return
        val owner = activity as? LifecycleOwner ?: return
        dialogShowing = true
        owner.lifecycleScope.launch {
            val limitLabel = TimeLockStore.formatLimitLabel(timeLock.dailyLimitMinutesNow())
            if (activity.isFinishing) {
                dialogShowing = false
                return@launch
            }
            val pad = (12 * activity.resources.displayMetrics.density).toInt()
            val input = EditText(activity).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                filters = arrayOf(InputFilter.LengthFilter(4))
                setHint(R.string.time_lock_set_pin_hint)
                setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
                setHintTextColor(ContextCompat.getColor(activity, R.color.text_secondary))
                setPadding(pad, pad, pad, pad)
            }
            val column = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(input)
            }
            val dialog = AlertDialog.Builder(activity, R.style.Theme_HongguoWatch_AlertDialog)
                .setTitle(R.string.time_lock_limit_reached_title)
                .setMessage(activity.getString(R.string.time_lock_limit_reached_body, limitLabel))
                .setView(column)
                .setCancelable(false)
                .setNegativeButton(R.string.update_later) { _, _ ->
                    dialogShowing = false
                    onCancel?.invoke()
                }
                .setPositiveButton(R.string.time_lock_unlock_today) { _, _ ->
                    val pin = input.text?.toString().orEmpty()
                    owner.lifecycleScope.launch {
                        if (timeLock.unlockToday(pin)) {
                            dialogShowing = false
                            Toast.makeText(activity, R.string.time_lock_unlocked_toast, Toast.LENGTH_SHORT).show()
                            onUnlocked?.invoke()
                        } else {
                            Toast.makeText(activity, R.string.time_lock_pin_wrong, Toast.LENGTH_SHORT).show()
                            dialogShowing = false
                            showLockDialog(activity, timeLock, onUnlocked, onCancel)
                        }
                    }
                }
                .create()
            dialog.setOnDismissListener {
                // 仅当未通过 PIN 解锁时，允许下次再弹
            }
            dialog.show()
            dialog.window?.let { window ->
                val width = WatchUi.dialogContentWidthPx(activity)
                window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
                window.setBackgroundDrawableResource(R.drawable.bg_alert_dialog)
            }
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
            input.requestFocus()
        }
    }
}
