package com.hongguo.watch.player

import com.hongguo.watch.upstream.RemoteVideo
import tv.danmaku.ijk.media.player.misc.IMediaDataSource
import java.io.IOException

/** 把 App 族加密流（RemoteVideo / MediaCrypto）接到 IJK 的随机读接口。 */
class IjkRemoteDataSource(private val video: RemoteVideo) : IMediaDataSource {
    @Throws(IOException::class)
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size <= 0) return 0
        if (position < 0) throw IOException("invalid position")
        if (position >= video.total) return -1
        val want = minOf(size.toLong(), video.total - position, 64 * 1024L).toInt()
        val bytes = video.read(position, want)
        if (bytes.isEmpty()) return -1
        bytes.copyInto(buffer, offset)
        return bytes.size
    }

    @Throws(IOException::class)
    override fun getSize(): Long = video.total

    @Throws(IOException::class)
    override fun close() {
        // RemoteVideo 生命周期由 PlayerActivity 统一 close
    }
}
