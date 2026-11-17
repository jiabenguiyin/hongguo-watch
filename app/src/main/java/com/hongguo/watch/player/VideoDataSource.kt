// SPDX-License-Identifier: GPL-3.0-only
// Adapted from N3urda/hongguoTV VideoDataSource.kt
package com.hongguo.watch.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import com.hongguo.watch.upstream.RemoteVideo
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

@OptIn(UnstableApi::class)
class VideoDataSource(private val video: RemoteVideo) : BaseDataSource(true) {
    private var position = 0L
    private var remaining = 0L
    private var reader: InputStream? = null
    private var rangeRemaining = 0
    private var opened = false
    private var uri: Uri? = null

    override fun open(spec: DataSpec): Long {
        reader?.close()
        reader = null
        rangeRemaining = 0
        transferInitializing(spec)
        uri = spec.uri
        position = spec.position
        if (position > video.total) throw IOException("读取位置超出视频长度")
        remaining =
            if (spec.length == C.LENGTH_UNSET.toLong()) video.total - position
            else minOf(spec.length, video.total - position)
        opened = true
        transferStarted(spec)
        return remaining
    }

    override fun read(target: ByteArray, targetOffset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val cached = if (reader == null) video.cachedBytes(position) else 0
        val count =
            if (cached > 0) {
                val bytes = video.read(
                    position,
                    minOf(length.toLong(), cached.toLong(), remaining, 32 * 1024L).toInt(),
                )
                bytes.copyInto(target, targetOffset)
                bytes.size
            } else {
                if (reader == null) {
                    rangeRemaining = minOf(512 * 1024L, remaining).toInt()
                    reader = video.openRead(position, rangeRemaining)
                }
                val n = reader!!.read(
                    target,
                    targetOffset,
                    minOf(length.toLong(), remaining, rangeRemaining.toLong()).toInt(),
                )
                if (n <= 0) throw EOFException("视频分段意外结束")
                rangeRemaining -= n
                if (rangeRemaining == 0) {
                    reader?.close()
                    reader = null
                }
                n
            }
        position += count
        remaining -= count
        bytesTransferred(count)
        return count
    }

    override fun getUri() = uri

    override fun close() {
        try {
            reader?.close()
        } finally {
            reader = null
            uri = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }
}
