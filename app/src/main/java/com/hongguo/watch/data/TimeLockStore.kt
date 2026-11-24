package com.hongguo.watch.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.MessageDigest
import java.util.Calendar

private val Context.timeLockStore by preferencesDataStore("time_lock")

/**
 * 时间锁：每日观看时长上限 + 4 位 PIN。
 * 密码只存 SHA-256(salt|pin)，明文不落盘。
 */
class TimeLockStore(context: Context) {
    private val appContext = context.applicationContext

    private val enabledKey = booleanPreferencesKey("enabled")
    private val pinHashKey = stringPreferencesKey("pin_hash")
    private val pinSaltKey = stringPreferencesKey("pin_salt")
    private val dailyLimitMinutesKey = intPreferencesKey("daily_limit_minutes")
    private val usedMsKey = longPreferencesKey("used_ms_today")
    private val dayKey = stringPreferencesKey("day_key")
    private val unlockedTodayKey = booleanPreferencesKey("unlocked_today")
    private val restReminderKey = booleanPreferencesKey("rest_reminder")
    private val sleepReminderKey = booleanPreferencesKey("sleep_reminder")

    val enabled: Flow<Boolean> = appContext.timeLockStore.data.map { it[enabledKey] ?: false }
    val dailyLimitMinutes: Flow<Int> = appContext.timeLockStore.data.map {
        normalizeLimit(it[dailyLimitMinutesKey] ?: DEFAULT_LIMIT_MINUTES)
    }
    val restReminder: Flow<Boolean> = appContext.timeLockStore.data.map { it[restReminderKey] ?: false }
    val sleepReminder: Flow<Boolean> = appContext.timeLockStore.data.map { it[sleepReminderKey] ?: false }

    suspend fun enabledNow(): Boolean = enabled.first()
    suspend fun dailyLimitMinutesNow(): Int = dailyLimitMinutes.first()
    suspend fun hasPin(): Boolean = appContext.timeLockStore.data.first()[pinHashKey].orEmpty().isNotBlank()

    suspend fun setRestReminder(enabled: Boolean) {
        appContext.timeLockStore.edit { it[restReminderKey] = enabled }
    }

    suspend fun setSleepReminder(enabled: Boolean) {
        appContext.timeLockStore.edit { it[sleepReminderKey] = enabled }
    }

    suspend fun setDailyLimitMinutes(minutes: Int) {
        appContext.timeLockStore.edit { it[dailyLimitMinutesKey] = normalizeLimit(minutes) }
    }

    /** 开启：写入 PIN 并打开开关。 */
    suspend fun enableWithPin(pin: String) {
        require(isValidPin(pin)) { "pin must be 4 digits" }
        val salt = randomSalt()
        appContext.timeLockStore.edit {
            it[pinSaltKey] = salt
            it[pinHashKey] = hashPin(salt, pin)
            it[enabledKey] = true
            it[unlockedTodayKey] = false
        }
    }

    suspend fun disable(pin: String): Boolean {
        if (!verifyPin(pin)) return false
        appContext.timeLockStore.edit {
            it[enabledKey] = false
            it[unlockedTodayKey] = false
        }
        return true
    }

    suspend fun verifyPin(pin: String): Boolean {
        if (!isValidPin(pin)) return false
        val prefs = appContext.timeLockStore.data.first()
        val salt = prefs[pinSaltKey].orEmpty()
        val expected = prefs[pinHashKey].orEmpty()
        if (salt.isBlank() || expected.isBlank()) return false
        return hashPin(salt, pin) == expected
    }

    /** 今日超时后凭 PIN 临时放行到次日。 */
    suspend fun unlockToday(pin: String): Boolean {
        if (!verifyPin(pin)) return false
        ensureTodayBucket()
        appContext.timeLockStore.edit { it[unlockedTodayKey] = true }
        return true
    }

    suspend fun isBlocked(): Boolean {
        if (!enabledNow()) return false
        ensureTodayBucket()
        val prefs = appContext.timeLockStore.data.first()
        if (prefs[unlockedTodayKey] == true) return false
        val used = prefs[usedMsKey] ?: 0L
        val limitMs = dailyLimitMinutesNow() * 60_000L
        return used >= limitMs
    }

    suspend fun usedMsToday(): Long {
        ensureTodayBucket()
        return appContext.timeLockStore.data.first()[usedMsKey] ?: 0L
    }

    suspend fun addUsage(deltaMs: Long) {
        if (deltaMs <= 0L) return
        if (!enabledNow()) return
        ensureTodayBucket()
        appContext.timeLockStore.edit {
            val current = it[usedMsKey] ?: 0L
            it[usedMsKey] = current + deltaMs
        }
    }

    private suspend fun ensureTodayBucket() {
        val today = todayKey()
        appContext.timeLockStore.edit {
            if (it[dayKey] != today) {
                it[dayKey] = today
                it[usedMsKey] = 0L
                it[unlockedTodayKey] = false
            }
        }
    }

    companion object {
        const val DEFAULT_LIMIT_MINUTES = 60
        val LIMIT_OPTIONS_MINUTES = listOf(30, 60, 120, 180, 240)

        fun isValidPin(pin: String): Boolean = pin.length == 4 && pin.all { it.isDigit() }

        fun normalizeLimit(minutes: Int): Int =
            LIMIT_OPTIONS_MINUTES.minByOrNull { kotlin.math.abs(it - minutes) } ?: DEFAULT_LIMIT_MINUTES

        fun formatLimitLabel(minutes: Int): String = when (normalizeLimit(minutes)) {
            30 -> "30 分钟"
            60 -> "1 小时"
            120 -> "2 小时"
            180 -> "3 小时"
            240 -> "4 小时"
            else -> "${normalizeLimit(minutes)} 分钟"
        }

        private fun todayKey(): String {
            val cal = Calendar.getInstance()
            return "%04d-%02d-%02d".format(
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH) + 1,
                cal.get(Calendar.DAY_OF_MONTH),
            )
        }

        private fun randomSalt(): String {
            val bytes = ByteArray(16)
            java.security.SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        private fun hashPin(salt: String, pin: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = digest.digest("$salt|$pin".toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
