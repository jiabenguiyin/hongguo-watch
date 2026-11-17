package com.hongguo.watch.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("settings")

class SettingsStore(private val context: Context) {
    private val wifiOnlyKey = booleanPreferencesKey("wifi_only")
    private val maxQualityKey = intPreferencesKey("max_quality")

    val wifiOnly: Flow<Boolean> = context.dataStore.data.map { it[wifiOnlyKey] ?: false }
    val maxQuality: Flow<Int> = context.dataStore.data.map { it[maxQualityKey] ?: 480 }

    suspend fun setWifiOnly(enabled: Boolean) {
        context.dataStore.edit { it[wifiOnlyKey] = enabled }
    }

    suspend fun setMaxQuality(height: Int) {
        context.dataStore.edit { it[maxQualityKey] = height }
    }

    suspend fun wifiOnlyNow(): Boolean = wifiOnly.first()
    suspend fun maxQualityNow(): Int = maxQuality.first()
}
