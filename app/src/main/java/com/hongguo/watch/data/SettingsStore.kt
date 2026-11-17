package com.hongguo.watch.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("settings")

class SettingsStore(private val context: Context) {
    private val wifiOnlyKey = booleanPreferencesKey("wifi_only")
    private val maxQualityKey = intPreferencesKey("max_quality")
    private val showDemoKey = booleanPreferencesKey("show_demo")
    private val playbackSpeedKey = floatPreferencesKey("playback_speed")

    val wifiOnly: Flow<Boolean> = context.dataStore.data.map { it[wifiOnlyKey] ?: false }
    /** 默认 480；可选 720 / 1080（手动高清，低配表可能发热/卡顿）。 */
    val maxQuality: Flow<Int> = context.dataStore.data.map {
        normalizeQuality(it[maxQualityKey] ?: 480)
    }
    val showDemo: Flow<Boolean> = context.dataStore.data.map { it[showDemoKey] ?: true }
    /** 默认 1.0x；可选 0.75 / 1.0 / 1.25 / 1.5 / 2.0。 */
    val playbackSpeed: Flow<Float> = context.dataStore.data.map {
        normalizeSpeed(it[playbackSpeedKey] ?: 1.0f)
    }

    suspend fun setWifiOnly(enabled: Boolean) {
        context.dataStore.edit { it[wifiOnlyKey] = enabled }
    }

    suspend fun setMaxQuality(height: Int) {
        context.dataStore.edit { it[maxQualityKey] = normalizeQuality(height) }
    }

    suspend fun setShowDemo(enabled: Boolean) {
        context.dataStore.edit { it[showDemoKey] = enabled }
    }

    suspend fun setPlaybackSpeed(speed: Float) {
        context.dataStore.edit { it[playbackSpeedKey] = normalizeSpeed(speed) }
    }

    suspend fun wifiOnlyNow(): Boolean = wifiOnly.first()
    suspend fun maxQualityNow(): Int = maxQuality.first()
    suspend fun showDemoNow(): Boolean = showDemo.first()
    suspend fun playbackSpeedNow(): Float = playbackSpeed.first()

    companion object {
        val QUALITY_OPTIONS = listOf(480, 720, 1080)
        val SPEED_OPTIONS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

        fun normalizeQuality(height: Int): Int = when {
            height >= 1080 -> 1080
            height >= 720 -> 720
            else -> 480
        }

        fun normalizeSpeed(speed: Float): Float {
            return SPEED_OPTIONS.minByOrNull { kotlin.math.abs(it - speed) } ?: 1.0f
        }

        fun formatSpeed(speed: Float): String {
            val normalized = normalizeSpeed(speed)
            return if (normalized == normalized.toInt().toFloat()) {
                "${normalized.toInt()}.0x"
            } else {
                "${normalized}x"
            }
        }
    }
}
