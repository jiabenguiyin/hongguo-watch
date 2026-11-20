package com.hongguo.watch.player

import com.hongguo.watch.upstream.RemoteVideo
import tv.danmaku.ijk.media.player.misc.IMediaDataSource
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/** Streams remote media into IJK, sharing an HTTP range across sequential reads. */
class IjkRemoteDataSource(private val video: RemoteVideo) : IMediaDataSource {
    private var reader: InputStream? = null
    private var nextPosition = 0L
    private var rangeEnd = 0L
    private var closed = false

    @Synchronized
    @Throws(IOException::class)
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        // IJK also calls readAt with size zero to probe a seek target.
        if (size <= 0) return 0
        if (position < 0) throw IOException("invalid position")
        if (closed) throw IOException("播放请求已取消")
        if (position != nextPosition) closeRange()
        if (position >= video.total) return -1

        val want = minOf(size.toLong(), video.total - position, 64 * 1024L).toInt()
        val cached = video.cachedBytes(position)
        if (cached > 0) {
            val bytes = video.read(position, minOf(want, cached))
            bytes.copyInto(buffer, offset)
            nextPosition = position + bytes.size
            return bytes.size
        }

        try {
            val input = reader ?: run {
                val length = minOf(RANGE_BYTES, video.total - position).toInt()
                rangeEnd = position + length
                video.openRead(position, length).also { reader = it }
            }
            val count = input.read(buffer, offset, minOf(want.toLong(), rangeEnd - position).toInt())
            if (count <= 0) throw EOFException("视频分段意外结束")
            nextPosition = position + count
            if (nextPosition == rangeEnd) closeRange()
            return count
        } catch (error: Exception) {
            runCatching { closeRange() }
            throw error
        }
    }

    @Throws(IOException::class)
    override fun getSize(): Long = video.total

    @Synchronized
    @Throws(IOException::class)
    override fun close() {
        closed = true
        closeRange()
    }

    private fun closeRange() {
        val input = reader
        reader = null
        input?.close()
    }

    private companion object {
        const val RANGE_BYTES = 512 * 1024L
    }
}
