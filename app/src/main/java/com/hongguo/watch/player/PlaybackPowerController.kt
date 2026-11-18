// SPDX-License-Identifier: GPL-3.0-only
package com.hongguo.watch.player

import android.app.Activity
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.view.WindowManager
import com.hongguo.watch.BuildConfig

/**
 * 播放电源策略：前台 KEEP_SCREEN_ON、息屏续听 PARTIAL_WAKE_LOCK、音频焦点。
 * 不改变 IJK/Media3 解码路径；锁与手势仍由 PlayerActivity 管理。
 */
class PlaybackPowerController(
    private val activity: Activity,
    private val onAudioFocusLost: () -> Unit,
) {
    private val audioManager =
        activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val powerManager =
        activity.getSystemService(Context.POWER_SERVICE) as PowerManager

    private var wakeLock: PowerManager.WakeLock? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    private var continueEnabled = ScreenOffPlaybackPolicy.DEFAULT_CONTINUE_ENABLED
    private var foreground = true
    private var playing = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            -> {
                // 被其他应用抢焦点：暂停，避免后台无声耗电
                onAudioFocusLost()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> Unit
            AudioManager.AUDIOFOCUS_GAIN -> Unit
        }
    }

    fun setContinueEnabled(enabled: Boolean) {
        continueEnabled = enabled
        apply()
    }

    fun sync(foreground: Boolean, playing: Boolean) {
        this.foreground = foreground
        this.playing = playing
        apply()
    }

    fun release() {
        playing = false
        foreground = false
        apply()
        releaseWakeLock()
        abandonAudioFocus()
    }

    private fun apply() {
        val keepScreen = ScreenOffPlaybackPolicy.shouldKeepScreenOn(foreground, playing)
        val holdWake = ScreenOffPlaybackPolicy.shouldHoldPartialWakeLock(
            continueEnabled = continueEnabled,
            foreground = foreground,
            isActivelyPlaying = playing,
        )
        setKeepScreenOn(keepScreen)
        if (holdWake) acquireWakeLock() else releaseWakeLock()
        if (playing) requestAudioFocus() else abandonAudioFocus()
    }

    private fun setKeepScreenOn(enabled: Boolean) {
        val window = activity.window ?: return
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireWakeLock() {
        val existing = wakeLock
        if (existing?.isHeld == true) return
        val lock = existing ?: powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "${BuildConfig.APPLICATION_ID}:playback",
        ).also {
            it.setReferenceCounted(false)
            wakeLock = it
        }
        runCatching { lock.acquire(4 * 60 * 60 * 1000L) } // 最长 4h，防泄漏
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        runCatching {
            if (lock.isHeld) lock.release()
        }
        wakeLock = null
    }

    private fun requestAudioFocus() {
        if (hasAudioFocus) return
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build(),
                )
                .setOnAudioFocusChangeListener(focusListener)
                .build()
                .also { audioFocusRequest = it }
            audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                focusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN,
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        hasAudioFocus = granted
    }

    private fun abandonAudioFocus() {
        if (!hasAudioFocus && audioFocusRequest == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
        } else {
            @Suppress("DEPRECATION")
            runCatching { audioManager.abandonAudioFocus(focusListener) }
        }
        hasAudioFocus = false
    }
}
