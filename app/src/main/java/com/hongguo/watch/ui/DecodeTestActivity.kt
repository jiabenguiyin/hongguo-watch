package com.hongguo.watch.ui

import android.graphics.SurfaceTexture
import android.os.Bundle
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.hongguo.watch.R
import com.hongguo.watch.databinding.ActivityDecodeTestBinding
import com.hongguo.watch.player.IjkPlayerEngine
import com.hongguo.watch.player.MediaCodecCaps
import com.hongguo.watch.player.PlayerDiagLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.danmaku.ijk.media.player.IMediaPlayer
import tv.danmaku.ijk.media.player.IjkMediaPlayer
import java.io.File
import java.io.FileOutputStream

/**
 * 本地合成片解码自检：不依赖红果片源，验证本机 H.264 / HEVC 软解能力。
 */
class DecodeTestActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDecodeTestBinding
    private var player: IjkMediaPlayer? = null
    private var surface: Surface? = null
    private var watchJob: Job? = null
    private var currentClip = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDecodeTestBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (WatchUi.isCompactWatch(this)) WatchUi.applyImmersive(this)
        WatchUi.applySafePadding(binding.root)

        binding.backButton.setOnClickListener { finish() }
        binding.capsLabel.text = MediaCodecCaps.summaryLines().joinToString("\n")
        binding.btnH264360.setOnClickListener { runClip("h264_360p.mp4", soft = true) }
        binding.btnH264480.setOnClickListener { runClip("h264_480p.mp4", soft = true) }
        binding.btnHevc360.setOnClickListener { runClip("hevc_360p.mp4", soft = true) }
        binding.btnHevc480.setOnClickListener { runClip("hevc_480p.mp4", soft = true) }
        binding.btnHevcHw.setOnClickListener { runClip("hevc_360p.mp4", soft = false) }

        binding.texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                surface?.release()
                surface = Surface(st)
                player?.setSurface(surface)
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                player?.setSurface(null)
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
        }
    }

    private fun runClip(assetName: String, soft: Boolean) {
        watchJob?.cancel()
        releasePlayer()
        currentClip = assetName
        binding.statusLabel.text = getString(R.string.decode_test_running, assetName)
        lifecycleScope.launch {
            val outcome = runCatching {
                val file = withContext(Dispatchers.IO) { copyAsset(assetName) }
                startIjk(file, soft)
            }
            outcome.onFailure { err ->
                binding.statusLabel.text =
                    getString(R.string.decode_test_failed, err.message ?: "?")
                PlayerDiagLog.e("decode-test failed $assetName", err)
            }
        }
    }

    private fun startIjk(file: File, soft: Boolean) {
        if (!IjkPlayerEngine.isAbiSupported()) {
            throw IllegalStateException("当前 ABI 无 IJK 原生库")
        }
        IjkPlayerEngine.ensureLibs()
        val ijk = IjkMediaPlayer()
        player = ijk
        IjkPlayerEngine.applyOptions(ijk, softDecode = soft)
        ijk.setOnPreparedListener {
            binding.statusLabel.text = getString(
                R.string.decode_test_prepared,
                currentClip,
                if (soft) "soft" else "hw",
            )
            ijk.start()
        }
        ijk.setOnInfoListener { _, what, _ ->
            if (what == IMediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                binding.statusLabel.text = getString(R.string.decode_test_ok, currentClip)
                PlayerDiagLog.i("decode-test OK clip=$currentClip soft=$soft")
            }
            false
        }
        ijk.setOnErrorListener { _, what, extra ->
            binding.statusLabel.text =
                getString(R.string.decode_test_failed, "what=$what extra=$extra")
            PlayerDiagLog.e("decode-test error clip=$currentClip what=$what extra=$extra")
            true
        }
        surface?.let { ijk.setSurface(it) }
        ijk.setDataSource(file.absolutePath)
        ijk.prepareAsync()
        watchJob = lifecycleScope.launch {
            delay(6_000)
            if (binding.statusLabel.text?.contains("OK") != true) {
                binding.statusLabel.text = getString(R.string.decode_test_timeout, currentClip)
                Toast.makeText(this@DecodeTestActivity, R.string.decode_test_timeout_toast, Toast.LENGTH_LONG)
                    .show()
            }
        }
    }

    private fun copyAsset(name: String): File {
        val out = File(cacheDir, "decode_test_$name")
        if (out.exists() && out.length() > 0L) return out
        assets.open("decode_test/$name").use { input ->
            FileOutputStream(out).use { output -> input.copyTo(output) }
        }
        return out
    }

    private fun releasePlayer() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
    }

    override fun onDestroy() {
        watchJob?.cancel()
        releasePlayer()
        surface?.release()
        surface = null
        super.onDestroy()
    }
}
