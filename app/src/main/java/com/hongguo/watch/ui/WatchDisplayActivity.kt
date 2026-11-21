package com.hongguo.watch.ui

import android.content.Context
import androidx.appcompat.app.AppCompatActivity

abstract class WatchDisplayActivity : AppCompatActivity() {
    private lateinit var appliedDisplay: WatchDisplaySettings
    protected open val useCustomDisplaySize: Boolean = true

    override fun attachBaseContext(newBase: Context) {
        appliedDisplay = WatchDisplaySettings.load(newBase)
        super.attachBaseContext(if (useCustomDisplaySize) appliedDisplay.withDisplayResources(newBase) else newBase)
    }

    protected fun recreateForDisplaySizeChange(): Boolean {
        val current = WatchDisplaySettings.load(this)
        if (current.scalePercent == appliedDisplay.scalePercent &&
            current.textPercent == appliedDisplay.textPercent
        ) return false
        recreate()
        return true
    }
}
