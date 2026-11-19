// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.watch.ui

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * 顶栏紧凑时钟：HH:mm，每分钟 [Intent.ACTION_TIME_TICK] 刷新；
 * 兼听 TIME_CHANGED / TIMEZONE_CHANGED；onStart 注册、onStop 注销；resume 时立即刷新。
 */
class WatchToolbarClock(
    private val activity: Activity,
    private val timeView: TextView,
) : DefaultLifecycleObserver {
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refresh()
        }
    }

    init {
        (activity as LifecycleOwner).lifecycle.addObserver(this)
        refresh()
    }

    fun refresh() {
        timeView.text = WatchStatusFormat.formatClock(System.currentTimeMillis())
    }

    override fun onStart(owner: LifecycleOwner) {
        register()
        refresh()
    }

    override fun onStop(owner: LifecycleOwner) {
        unregister()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        unregister()
        owner.lifecycle.removeObserver(this)
    }

    private fun register() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        // TIME_TICK 等为系统广播，不可由第三方发送 → NOT_EXPORTED
        ContextCompat.registerReceiver(
            activity,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        registered = true
    }

    private fun unregister() {
        if (!registered) return
        runCatching { activity.unregisterReceiver(receiver) }
        registered = false
    }
}
