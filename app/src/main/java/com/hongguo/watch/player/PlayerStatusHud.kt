// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.watch.player

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PorterDuff
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.LayerDrawable
import android.os.BatteryManager
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.hongguo.watch.R
import com.hongguo.watch.ui.WatchStatusFormat
import com.hongguo.watch.ui.WatchToolbarClock

/**
 * 播放器手表状态条：当前时间 + 电量%（充电时着色）。
 * 挂在控件叠层内，随 chrome 同显隐（含锁定藏栏）；勿单独常驻画面。
 * TIME_TICK / BATTERY_CHANGED 生命周期内注册、离开注销。
 */
class PlayerStatusHud(
    private val activity: Activity,
    private val strip: View,
    private val timeView: TextView,
    private val batteryIcon: ImageView,
    private val batteryText: TextView,
) : DefaultLifecycleObserver {
    private val clock = WatchToolbarClock(activity, timeView)
    private val batteryFill = ClipDrawable(
        ColorDrawable(ContextCompat.getColor(activity, R.color.text_primary)),
        Gravity.START,
        ClipDrawable.HORIZONTAL,
    )
    private var batteryRegistered = false
    private var lastPercent = -1
    private var lastCharging = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            applyBatteryIntent(intent)
        }
    }

    init {
        batteryIcon.setImageDrawable(
            LayerDrawable(
                arrayOf(
                    ContextCompat.getDrawable(activity, R.drawable.ic_battery)!!,
                    batteryFill,
                ),
            ).apply {
                val density = activity.resources.displayMetrics.density
                setLayerInset(
                    1,
                    (density * 2.2f).toInt(),
                    (density * 2.8f).toInt(),
                    (density * 4.4f).toInt(),
                    (density * 2.8f).toInt(),
                )
            },
        )
        (activity as LifecycleOwner).lifecycle.addObserver(this)
        refreshBatterySticky()
    }

    /** 与控件栏同步显隐；锁定态应由控件叠层一并隐藏。 */
    fun setVisible(visible: Boolean) {
        strip.visibility = if (visible) View.VISIBLE else View.GONE
    }

    override fun onStart(owner: LifecycleOwner) {
        registerBattery()
        refreshBatterySticky()
        clock.refresh()
    }

    override fun onStop(owner: LifecycleOwner) {
        unregisterBattery()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        unregisterBattery()
        owner.lifecycle.removeObserver(this)
    }

    private fun registerBattery() {
        if (batteryRegistered) return
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        ContextCompat.registerReceiver(
            activity,
            batteryReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        batteryRegistered = true
    }

    private fun unregisterBattery() {
        if (!batteryRegistered) return
        runCatching { activity.unregisterReceiver(batteryReceiver) }
        batteryRegistered = false
    }

    private fun refreshBatterySticky() {
        val sticky = runCatching {
            ContextCompat.registerReceiver(
                activity,
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.getOrNull()
        if (sticky != null) {
            applyBatteryIntent(sticky)
        } else {
            // 极少设备无 sticky：退回 BatteryManager 容量
            val bm = activity.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            if (pct in 0..100) {
                bindBattery(pct, charging = false)
            }
        }
    }

    private fun applyBatteryIntent(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0) return
        val percent = WatchStatusFormat.batteryPercent(level, scale)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL ||
            plugged != 0
        bindBattery(percent, charging)
    }

    private fun bindBattery(percent: Int, charging: Boolean) {
        if (percent == lastPercent && charging == lastCharging) return
        lastPercent = percent
        lastCharging = charging
        batteryFill.level = WatchStatusFormat.batteryDrawableLevel(percent)
        batteryText.text = WatchStatusFormat.formatBatteryPercent(percent)
        val colorRes = when {
            charging -> R.color.rating_good
            percent <= 20 -> R.color.accent
            else -> R.color.text_primary
        }
        val color = ContextCompat.getColor(activity, colorRes)
        batteryText.setTextColor(color)
        batteryIcon.setColorFilter(color, PorterDuff.Mode.SRC_IN)
        batteryIcon.contentDescription = if (charging) {
            activity.getString(R.string.cd_battery_charging, percent)
        } else {
            activity.getString(R.string.cd_battery, percent)
        }
        batteryText.contentDescription = batteryIcon.contentDescription
    }
}
