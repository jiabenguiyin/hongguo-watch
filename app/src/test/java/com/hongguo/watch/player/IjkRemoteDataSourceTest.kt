package com.hongguo.watch.player

import com.hongguo.watch.upstream.MediaRangeHttpException
import com.hongguo.watch.upstream.RemoteVideo
import com.hongguo.watch.upstream.StreamInfo
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.Closeable
import java.io.IOException

class IjkRemoteDataSourceTest {
    @Test
    fun smallSequentialReadsShareAnHttpRangeUntilItsEnd() {
        Fixture().use { fixture ->
            val buffer = ByteArray(4 * 1024)
            var position = 0L
            while (true) {
                val count = fixture.source.readAt(position, buffer, 0, buffer.size)
                if (count == -1) break
                val start = position.toInt()
                assertArrayEquals(fixture.media.copyOfRange(start, start + count), buffer.copyOf(count))
                position += count
            }
            assertEquals(fixture.media.size.toLong(), position)
            assertEquals(
                listOf(0L..262143L, 262144L..786431L, 786432L..786444L),
                fixture.ranges,
            )
        }
    }

    @Test
    fun seekProbesKeepTheCurrentRangeAndRealSeeksOpenAtTheRequestedOffset() {
        Fixture().use { fixture ->
            val buffer = ByteArray(12)
            fun read(position: Long) {
                assertEquals(8, fixture.source.readAt(position, buffer, 2, 8))
                val start = position.toInt()
                assertArrayEquals(fixture.media.copyOfRange(start, start + 8), buffer.copyOfRange(2, 10))
            }
            read(300000)
            assertEquals(0, fixture.source.readAt(400000, buffer, 0, 0))
            read(300008)
            read(24)
            read(400000)
            assertEquals(listOf(0L, 300000L, 400000L), fixture.ranges.map { it.first })
            fixture.source.close()
            assertThrows(IOException::class.java) { fixture.source.readAt(400008, buffer, 0, 8) }
        }
    }

    @Test
    fun httpFailureAfterASeekKeepsItsStatusForFailover() {
        Fixture(rejectStart = 400000).use { fixture ->
            val buffer = ByteArray(8)
            assertEquals(8, fixture.source.readAt(300000, buffer, 0, 8))
            val error = assertThrows(MediaRangeHttpException::class.java) {
                fixture.source.readAt(400000, buffer, 0, 8)
            }
            assertEquals(503, error.statusCode)
        }
    }

    private class Fixture(rejectStart: Long? = null) : Closeable {
        val media = ByteArray(768 * 1024 + 13) { index -> (index xor (index ushr 8)).toByte() }
        val ranges = mutableListOf<LongRange>()
        private val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val (from, to) = requireNotNull(request.header("Range")).removePrefix("bytes=").split('-')
            val start = from.toLong()
            val end = minOf(to.toLong(), media.lastIndex.toLong())
            ranges += start..end
            val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("fixture")
            if (start == rejectStart) {
                response.code(503).body(byteArrayOf().toResponseBody()).build()
            } else {
                response.code(206).header("Content-Range", "bytes $start-$end/${media.size}")
                    .body(media.copyOfRange(start.toInt(), end.toInt() + 1).toResponseBody()).build()
            }
        }.build()
        private val video = RemoteVideo(
            client,
            StreamInfo("https://v1.qznovelvod.com/fixture.mp4", key = null, quality = "480p", isCdnBackup = true),
        ).prepare()
        val source = IjkRemoteDataSource(video)

        override fun close() {
            video.close()
            source.close()
        }
    }
}
