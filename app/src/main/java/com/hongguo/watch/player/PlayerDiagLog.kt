package com.hongguo.watch.player

import android.content.Context
import android.util.Log
import com.hongguo.watch.BuildConfig
import com.hongguo.watch.upstream.LadderEntryDiag
import com.hongguo.watch.upstream.LadderParseResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * HgWatchPlayer 诊断环形缓冲：设备信息 + 播放路径关键日志，供失败时上传。
 */
object PlayerDiagLog {
    private const val CAPACITY = 360
    private val lines = ConcurrentLinkedDeque<String>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile
    var lastVideoDecoder: String = ""
        private set

    @Volatile
    var lastAudioDecoder: String = ""
        private set

    @Volatile
    var lastCodec: String = ""
        private set

    @Volatile
    var lastQuality: String = ""
        private set

    @Volatile
    var lastVideoMode: String = ""
        private set

    @Volatile
    var firstFrameMs: Long = -1L
        private set

    @Volatile
    var playStartedAtMs: Long = 0L
        private set

    fun clearSession() {
        lastVideoDecoder = ""
        lastAudioDecoder = ""
        lastCodec = ""
        lastQuality = ""
        lastVideoMode = ""
        firstFrameMs = -1L
        playStartedAtMs = System.currentTimeMillis()
    }

    fun markPlayStart(codec: String, quality: String, mode: String) {
        playStartedAtMs = System.currentTimeMillis()
        firstFrameMs = -1L
        lastVideoDecoder = ""
        lastAudioDecoder = ""
        lastCodec = codec
        lastQuality = quality
        lastVideoMode = mode
        i("session start codec_id=$codec q=$quality mode=$mode")
    }

    fun markFirstFrame() {
        if (playStartedAtMs > 0L) {
            firstFrameMs = System.currentTimeMillis() - playStartedAtMs
        }
        i("first frame after ${firstFrameMs}ms decoder=$lastVideoDecoder")
    }

    fun markVideoDecoder(name: String) {
        lastVideoDecoder = name
        i("video decoder=$name")
    }

    fun markAudioDecoder(name: String) {
        lastAudioDecoder = name
        i("audio decoder=$name")
    }

    fun logLadder(result: LadderParseResult, maxQuality: Int, preferAvc: Boolean, avcOnly: Boolean) {
        i(result.summaryLine(maxQuality, preferAvc, avcOnly))
        result.diag.forEach { entry: LadderEntryDiag -> i(entry.toLogLine()) }
    }

    fun i(msg: String) = append("I", msg).also { Log.i(TAG, msg) }

    fun w(msg: String) = append("W", msg).also { Log.w(TAG, msg) }

    fun e(msg: String, err: Throwable? = null) {
        append("E", if (err != null) "$msg | ${err.javaClass.simpleName}: ${err.message}" else msg)
        if (err != null) Log.e(TAG, msg, err) else Log.e(TAG, msg)
    }

    fun buildReport(context: Context, reason: String): String {
        val device = WatchDecodePolicy.deviceSnapshot(context)
        val sb = StringBuilder(12_288)
        sb.appendLine("hongguo-watch diagnostic")
        sb.appendLine("version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        sb.appendLine("reason=$reason")
        sb.appendLine("time=${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}")
        sb.appendLine("--- device ---")
        device.forEach { (k, v) -> sb.appendLine("$k=$v") }
        sb.appendLine("--- playback ---")
        sb.appendLine("codec=$lastCodec")
        sb.appendLine("quality=$lastQuality")
        sb.appendLine("videoMode=$lastVideoMode")
        sb.appendLine("videoDecoder=$lastVideoDecoder")
        sb.appendLine("audioDecoder=$lastAudioDecoder")
        sb.appendLine("firstFrameMs=$firstFrameMs")
        sb.appendLine("--- mediacodec avc/hevc ---")
        runCatching { MediaCodecCaps.summaryLines() }
            .getOrElse { listOf("decoder enum failed: ${it.message}") }
            .forEach { sb.appendLine(it) }
        sb.appendLine("--- log ---")
        synchronized(lines) {
            lines.forEach { sb.appendLine(it) }
        }
        return sb.toString()
    }

    private fun append(level: String, msg: String) {
        val line = "${timeFmt.format(Date())} $level/$TAG: $msg"
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > CAPACITY) lines.removeFirst()
        }
    }

    const val TAG = "HgWatchPlayer"
}
