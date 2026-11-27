package com.hongguo.watch.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hongguo.watch.player.DecodePath
import com.hongguo.watch.player.PlayerKernel
import com.hongguo.watch.player.ScreenOffPlaybackPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("settings")

class SettingsStore(private val context: Context) {
    private val wifiOnlyKey = booleanPreferencesKey("wifi_only")
    private val maxQualityKey = intPreferencesKey("max_quality")
    private val playbackSpeedKey = floatPreferencesKey("playback_speed")
    /** 0=跟随检测 · 1=强制开 · 2=强制关 */
    private val roundAdaptModeKey = intPreferencesKey("round_adapt_mode")
    private val playerKernelKey = stringPreferencesKey("player_kernel")
    private val decodePathKey = stringPreferencesKey("decode_path")
    private val playbackRouteKey = stringPreferencesKey("playback_route")
    /** Playback signing proxy base URL; blank = off (local Medusa V5 ladder). */
    private val playbackProxyUrlKey = stringPreferencesKey("playback_proxy_url")
    private val playbackProxyTokenKey = stringPreferencesKey("playback_proxy_token")
    /** 息屏/退后台续听；默认关（省电）。 */
    private val screenOffContinueKey = booleanPreferencesKey("screen_off_continue")
    private val episodeWheelEnabledKey = booleanPreferencesKey("episode_wheel_enabled")
    /**
     * 库页排版：false=官方新样式（三列海报，默认）；true=旧列表排版。
     * 旧代码路径保留，仅由此开关启用。
     */
    private val libraryLayoutClassicKey = booleanPreferencesKey("library_layout_classic")

    val wifiOnly: Flow<Boolean> = context.dataStore.data.map { it[wifiOnlyKey] ?: false }
    /** true = 旧列表；false = 官方新三列（默认）。 */
    val libraryLayoutClassic: Flow<Boolean> = context.dataStore.data.map {
        it[libraryLayoutClassicKey] ?: false
    }
    /** 熄屏续听：关=熄屏暂停（默认）；开=熄屏继续出声。 */
    val screenOffContinue: Flow<Boolean> = context.dataStore.data.map {
        it[screenOffContinueKey] ?: ScreenOffPlaybackPolicy.DEFAULT_CONTINUE_ENABLED
    }
    val episodeWheelEnabled: Flow<Boolean> = context.dataStore.data.map {
        it[episodeWheelEnabledKey] ?: false
    }
    /** 默认 480；可选 720 / 1080（手动高清，低配表可能发热/卡顿）。 */
    val maxQuality: Flow<Int> = context.dataStore.data.map {
        normalizeQuality(it[maxQualityKey] ?: 480)
    }
    /** 默认 1.0x；可选 0.75 / 1.0 / 1.25 / 1.5 / 2.0。 */
    val playbackSpeed: Flow<Float> = context.dataStore.data.map {
        normalizeSpeed(it[playbackSpeedKey] ?: 1.0f)
    }
    /** 圆屏适配模式；缺省 AUTO（跟 isRoundScreen）。 */
    val roundAdaptMode: Flow<Int> = context.dataStore.data.map {
        normalizeRoundAdaptMode(it[roundAdaptModeKey] ?: ROUND_ADAPT_AUTO)
    }
    /** 播放内核：AUTO / MEDIA3 / IJK。 */
    val playerKernel: Flow<PlayerKernel> = context.dataStore.data.map {
        PlayerKernel.fromStorage(it[playerKernelKey])
    }
    /** 解码路径：AUTO / HARDWARE / SOFTWARE。 */
    val decodePath: Flow<DecodePath> = context.dataStore.data.map {
        DecodePath.fromStorage(it[decodePathKey])
    }
    val playbackRoute: Flow<PlaybackRoutePreference> = context.dataStore.data.map {
        PlaybackRoutePreference.fromStorage(it[playbackRouteKey])
    }
    /** Empty → proxy disabled. */
    val playbackProxyUrl: Flow<String> = context.dataStore.data.map {
        normalizeProxyUrl(it[playbackProxyUrlKey].orEmpty())
    }
    val playbackProxyToken: Flow<String> = context.dataStore.data.map {
        it[playbackProxyTokenKey].orEmpty().trim()
    }

    suspend fun setWifiOnly(enabled: Boolean) {
        context.dataStore.edit { it[wifiOnlyKey] = enabled }
    }

    suspend fun setScreenOffContinue(enabled: Boolean) {
        context.dataStore.edit { it[screenOffContinueKey] = enabled }
    }

    suspend fun setEpisodeWheelEnabled(enabled: Boolean) {
        context.dataStore.edit { it[episodeWheelEnabledKey] = enabled }
    }

    suspend fun setLibraryLayoutClassic(classic: Boolean) {
        context.dataStore.edit { it[libraryLayoutClassicKey] = classic }
    }

    suspend fun setMaxQuality(height: Int) {
        context.dataStore.edit { it[maxQualityKey] = normalizeQuality(height) }
    }

    suspend fun setPlaybackSpeed(speed: Float) {
        context.dataStore.edit { it[playbackSpeedKey] = normalizeSpeed(speed) }
    }

    suspend fun setRoundAdaptMode(mode: Int) {
        context.dataStore.edit { it[roundAdaptModeKey] = normalizeRoundAdaptMode(mode) }
    }

    /** 开关式写入：开=强制开，关=强制关（不再停留 AUTO）。 */
    suspend fun setRoundAdaptEnabled(enabled: Boolean) {
        setRoundAdaptMode(if (enabled) ROUND_ADAPT_ON else ROUND_ADAPT_OFF)
    }

    suspend fun setPlayerKernel(kernel: PlayerKernel) {
        context.dataStore.edit { it[playerKernelKey] = kernel.name }
    }

    suspend fun setDecodePath(path: DecodePath) {
        context.dataStore.edit { it[decodePathKey] = path.name }
    }

    suspend fun setPlaybackRoute(preference: PlaybackRoutePreference) {
        context.dataStore.edit { it[playbackRouteKey] = preference.name }
    }

    suspend fun setPlaybackProxyUrl(url: String) {
        context.dataStore.edit { it[playbackProxyUrlKey] = normalizeProxyUrl(url) }
    }

    suspend fun setPlaybackProxyToken(token: String) {
        context.dataStore.edit { it[playbackProxyTokenKey] = token.trim() }
    }

    suspend fun wifiOnlyNow(): Boolean = wifiOnly.first()
    suspend fun screenOffContinueNow(): Boolean = screenOffContinue.first()
    suspend fun libraryLayoutClassicNow(): Boolean = libraryLayoutClassic.first()
    suspend fun maxQualityNow(): Int = maxQuality.first()
    suspend fun playbackSpeedNow(): Float = playbackSpeed.first()
    suspend fun roundAdaptModeNow(): Int = roundAdaptMode.first()
    suspend fun playerKernelNow(): PlayerKernel = playerKernel.first()
    suspend fun decodePathNow(): DecodePath = decodePath.first()
    suspend fun playbackRouteNow(): PlaybackRoutePreference = playbackRoute.first()
    suspend fun playbackProxyUrlNow(): String = playbackProxyUrl.first()
    suspend fun playbackProxyTokenNow(): String = playbackProxyToken.first()

    /** 供 WatchUi 缓存：AUTO→null，ON→true，OFF→false。 */
    suspend fun roundAdaptOverrideNow(): Boolean? = when (roundAdaptModeNow()) {
        ROUND_ADAPT_ON -> true
        ROUND_ADAPT_OFF -> false
        else -> null
    }

    companion object {
        const val ROUND_ADAPT_AUTO = 0
        const val ROUND_ADAPT_ON = 1
        const val ROUND_ADAPT_OFF = 2

        val QUALITY_OPTIONS = listOf(480, 720, 1080)
        val SPEED_OPTIONS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

        fun normalizeRoundAdaptMode(mode: Int): Int = when (mode) {
            ROUND_ADAPT_ON, ROUND_ADAPT_OFF -> mode
            else -> ROUND_ADAPT_AUTO
        }

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

        /** Allow http(s) origins only; strip trailing slash. Blank disables proxy. */
        fun normalizeProxyUrl(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return ""
            val noSlash = trimmed.trimEnd('/')
            val ok = noSlash.startsWith("http://") || noSlash.startsWith("https://")
            return if (ok) noSlash else ""
        }
    }
}
