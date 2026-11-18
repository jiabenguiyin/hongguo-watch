package com.hongguo.watch.data.cache

import android.content.Context
import android.os.StatFs
import android.util.Log
import com.hongguo.watch.BuildConfig
import com.hongguo.watch.data.model.StreamInfo
import com.hongguo.watch.upstream.RemoteVideo
import com.hongguo.watch.upstream.StreamInfo as UpstreamStreamInfo
import com.hongguo.watch.upstream.VendorConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 短剧集本地缓存：解密落盘 → 本地文件播放；LRU + 磁盘预算；空间不足时软失败。
 *
 * 目录：`filesDir/video_cache/`（与 Coil / 更新包隔离）。
 * [listReady] 留给 T-57 离线浏览 UI。
 */
class VideoCacheManager(
    context: Context,
    private val http: OkHttpClient = defaultHttp,
    private val budgetProvider: () -> Long = { DEFAULT_BUDGET_BYTES },
) {
    private val appContext = context.applicationContext
    private val rootDir: File = File(appContext.filesDir, DIR_NAME).also { it.mkdirs() }
    private val indexFile = File(rootDir, INDEX_NAME)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val indexMutex = Mutex()
    private val jobs = ConcurrentHashMap<String, Job>()

    @Volatile
    private var memoryIndex: MutableList<VideoCacheEntry> = loadIndexUnlocked()

    fun rootDirectory(): File = rootDir

    fun budgetBytes(): Long = budgetProvider().coerceIn(MIN_BUDGET_BYTES, MAX_BUDGET_BYTES)

    fun usedBytes(): Long = VideoCacheLru.totalReadyBytes(snapshot())

    fun freeSpaceBytes(): Long = runCatching { availableBytes(rootDir) }.getOrDefault(0L)

    fun formatUsedLabel(): String = formatBytes(usedBytes())

    fun snapshot(): List<VideoCacheEntry> = memoryIndex.toList()

    /** T-57 钩子：已就绪缓存列表（按最近访问倒序）。 */
    fun listReady(): List<VideoCacheEntry> =
        snapshot().filter { it.ready }.sortedByDescending { it.lastAccessMs }

    fun isReady(seriesId: String, episodeId: String): Boolean =
        findReady(seriesId, episodeId) != null

    fun isCaching(seriesId: String, episodeId: String): Boolean {
        val id = VideoCacheEntry.cacheId(seriesId, episodeId)
        return jobs[id]?.isActive == true
    }

    /**
     * 返回可播本地文件；命中时刷新 LRU。未就绪返回 null（播放器回落网络）。
     */
    suspend fun resolvePlayFile(seriesId: String, episodeId: String): File? = withContext(Dispatchers.IO) {
        indexMutex.withLock {
            val entry = memoryIndex.firstOrNull {
                it.seriesId == seriesId && it.episodeId == episodeId && it.ready
            } ?: return@withLock null
            val file = File(rootDir, entry.fileName)
            if (!file.isFile || file.length() <= 0L) {
                memoryIndex.removeAll { it.id == entry.id }
                persistIndexUnlocked()
                return@withLock null
            }
            val touched = entry.copy(lastAccessMs = System.currentTimeMillis())
            memoryIndex.replaceAll { if (it.id == entry.id) touched else it }
            persistIndexUnlocked()
            file
        }
    }

    fun findReady(seriesId: String, episodeId: String): VideoCacheEntry? =
        snapshot().firstOrNull { it.seriesId == seriesId && it.episodeId == episodeId && it.ready }

    /**
     * 后台缓存选中流。已就绪则跳过；同集进行中不重复。
     * 空间不足 / 写盘失败只记日志，不抛到调用方。
     */
    fun enqueue(
        request: VideoCacheRequest,
        stream: StreamInfo,
        priority: VideoCachePriority = VideoCachePriority.CURRENT,
    ) {
        val id = VideoCacheEntry.cacheId(request.seriesId, request.episodeId)
        if (isReady(request.seriesId, request.episodeId)) return
        val existing = jobs[id]
        if (existing?.isActive == true) {
            if (priority == VideoCachePriority.CURRENT) {
                // 已有任务；CURRENT 不取消重开，避免抖
            }
            return
        }
        val job = scope.launch {
            try {
                materialize(request, stream)
            } catch (oom: OutOfMemoryError) {
                Log.w(TAG, "cache OOM $id", oom)
            } catch (err: Throwable) {
                if (err is kotlinx.coroutines.CancellationException) throw err
                Log.w(TAG, "cache failed $id: ${err.message}")
            } finally {
                jobs.remove(id)
            }
        }
        jobs[id] = job
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        indexMutex.withLock {
            memoryIndex.toList().forEach { entry ->
                runCatching { File(rootDir, entry.fileName).delete() }
                runCatching { File(rootDir, "${entry.fileName}.part").delete() }
            }
            memoryIndex.clear()
            persistIndexUnlocked()
            rootDir.listFiles()?.forEach { child ->
                if (child.name != INDEX_NAME) runCatching { child.deleteRecursively() }
            }
        }
    }

    private suspend fun materialize(request: VideoCacheRequest, stream: StreamInfo) {
        val id = VideoCacheEntry.cacheId(request.seriesId, request.episodeId)
        val fileName = sanitizeFileName("${id.hashCode().toUInt().toString(16)}.mp4")
        val target = File(rootDir, fileName)
        val part = File(rootDir, "$fileName.part")
        if (part.exists()) runCatching { part.delete() }

        val key = stream.decryptionKey
        if (key != null) {
            writeEncryptedStream(stream, key, part)
        } else {
            writePlainHttp(stream, part)
        }

        if (!part.isFile || part.length() <= 0L) {
            runCatching { part.delete() }
            throw IOException("缓存文件为空")
        }

        val size = part.length()
        ensureRoomFor(size, keepId = id)
        if (target.exists()) runCatching { target.delete() }
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }

        val entry = VideoCacheEntry(
            seriesId = request.seriesId,
            episodeId = request.episodeId,
            seriesTitle = request.seriesTitle,
            episodeNumber = request.episodeNumber,
            quality = stream.quality,
            codec = stream.codec,
            fileName = fileName,
            sizeBytes = size,
            lastAccessMs = System.currentTimeMillis(),
            ready = true,
        )
        indexMutex.withLock {
            memoryIndex.removeAll { it.id == id }
            memoryIndex.add(entry)
            persistIndexUnlocked()
        }
        // 落盘后再压一次预算（替换旧集时）
        ensureRoomFor(0L, keepId = id)
        Log.i(TAG, "cached $id size=${formatBytes(size)} q=${stream.quality}")
    }

    private fun writeEncryptedStream(stream: StreamInfo, key: ByteArray, part: File) {
        RemoteVideo(http, UpstreamStreamInfo(stream.url, key, stream.quality)).use { remote ->
            remote.prepare()
            val total = remote.total
            if (total <= 0L) throw IOException("视频长度为 0")
            if (!canFit(total)) throw IOException(ERR_LOW_STORAGE)
            FileOutputStream(part).use { out ->
                var offset = 0L
                while (offset < total) {
                    val chunk = minOf(CHUNK_BYTES.toLong(), total - offset).toInt()
                    val bytes = try {
                        remote.read(offset, chunk)
                    } catch (err: IOException) {
                        runCatching { part.delete() }
                        if (isNoSpace(err)) throw IOException(ERR_LOW_STORAGE, err)
                        throw err
                    }
                    if (bytes.isEmpty()) break
                    try {
                        out.write(bytes)
                    } catch (err: IOException) {
                        runCatching { part.delete() }
                        if (isNoSpace(err)) throw IOException(ERR_LOW_STORAGE, err)
                        throw err
                    }
                    offset += bytes.size
                }
                out.flush()
            }
        }
    }

    private fun writePlainHttp(stream: StreamInfo, part: File) {
        val request = Request.Builder()
            .url(stream.url)
            .header("User-Agent", stream.userAgent ?: VendorConstants.VIDEO_UA)
            .apply {
                stream.referer?.takeIf { it.isNotBlank() }?.let { header("Referer", it) }
            }
            .header("Accept-Encoding", "identity")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("空响应")
            val declared = body.contentLength()
            if (declared > 0 && !canFit(declared)) throw IOException(ERR_LOW_STORAGE)
            FileOutputStream(part).use { out ->
                try {
                    body.byteStream().use { input ->
                        val buf = ByteArray(CHUNK_BYTES)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                        }
                    }
                    out.flush()
                } catch (err: IOException) {
                    runCatching { part.delete() }
                    if (isNoSpace(err)) throw IOException(ERR_LOW_STORAGE, err)
                    throw err
                }
            }
        }
        if (part.length() <= 0L) throw IOException("缓存文件为空")
    }

    private suspend fun ensureRoomFor(neededBytes: Long, keepId: String) {
        indexMutex.withLock {
            val budget = budgetBytes()
            var used = VideoCacheLru.totalReadyBytes(memoryIndex)
            val victims = VideoCacheLru.pickVictims(
                entries = memoryIndex.filter { it.id != keepId },
                usedBytes = used,
                budgetBytes = budget,
                neededBytes = neededBytes,
            )
            for (victim in victims) {
                runCatching { File(rootDir, victim.fileName).delete() }
                runCatching { File(rootDir, "${victim.fileName}.part").delete() }
                memoryIndex.removeAll { it.id == victim.id }
                used -= victim.sizeBytes
            }
            if (victims.isNotEmpty()) persistIndexUnlocked()
            if (neededBytes > 0L && used + neededBytes > budget) {
                throw IOException(ERR_LOW_STORAGE)
            }
            if (neededBytes > 0L && !canFit(neededBytes)) {
                throw IOException(ERR_LOW_STORAGE)
            }
        }
    }

    private fun canFit(neededBytes: Long): Boolean {
        if (neededBytes <= 0L) return true
        val free = runCatching { availableBytes(rootDir) }.getOrDefault(0L)
        return free >= neededBytes + STORAGE_HEADROOM_BYTES
    }

    private fun loadIndexUnlocked(): MutableList<VideoCacheEntry> {
        if (!indexFile.isFile) return mutableListOf()
        return runCatching {
            val arr = JSONArray(indexFile.readText())
            val out = ArrayList<VideoCacheEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val entry = VideoCacheEntry(
                    seriesId = o.optString("seriesId"),
                    episodeId = o.optString("episodeId"),
                    seriesTitle = o.optString("seriesTitle"),
                    episodeNumber = o.optInt("episodeNumber"),
                    quality = o.optString("quality"),
                    codec = o.optString("codec"),
                    fileName = o.optString("fileName"),
                    sizeBytes = o.optLong("sizeBytes"),
                    lastAccessMs = o.optLong("lastAccessMs"),
                    ready = o.optBoolean("ready", true),
                )
                if (entry.seriesId.isBlank() || entry.episodeId.isBlank() || entry.fileName.isBlank()) {
                    continue
                }
                val file = File(rootDir, entry.fileName)
                if (entry.ready && (!file.isFile || file.length() <= 0L)) continue
                out += if (file.isFile) entry.copy(sizeBytes = file.length()) else entry
            }
            out
        }.getOrElse {
            Log.w(TAG, "index load failed: ${it.message}")
            mutableListOf()
        }
    }

    private fun persistIndexUnlocked() {
        val arr = JSONArray()
        for (entry in memoryIndex) {
            arr.put(
                JSONObject()
                    .put("seriesId", entry.seriesId)
                    .put("episodeId", entry.episodeId)
                    .put("seriesTitle", entry.seriesTitle)
                    .put("episodeNumber", entry.episodeNumber)
                    .put("quality", entry.quality)
                    .put("codec", entry.codec)
                    .put("fileName", entry.fileName)
                    .put("sizeBytes", entry.sizeBytes)
                    .put("lastAccessMs", entry.lastAccessMs)
                    .put("ready", entry.ready),
            )
        }
        val tmp = File(rootDir, "$INDEX_NAME.tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(indexFile)) {
            tmp.copyTo(indexFile, overwrite = true)
            tmp.delete()
        }
    }

    companion object {
        private const val TAG = "HgVideoCache"
        const val DIR_NAME = "video_cache"
        private const val INDEX_NAME = "index.json"
        const val DEFAULT_BUDGET_BYTES = 512L * 1024L * 1024L
        const val MIN_BUDGET_BYTES = 256L * 1024L * 1024L
        const val MAX_BUDGET_BYTES = 1024L * 1024L * 1024L
        /** 预留空间，避免把手表内部存储写满导致系统异常。 */
        const val STORAGE_HEADROOM_BYTES = 64L * 1024L * 1024L
        private const val CHUNK_BYTES = 256 * 1024
        const val ERR_LOW_STORAGE = "存储空间不足"

        private val defaultHttp: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        fun sanitizeFileName(name: String): String =
            name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "clip.mp4" }

        fun availableBytes(dir: File): Long {
            val path = if (dir.exists()) dir else dir.parentFile ?: dir
            val stat = StatFs(path.absolutePath)
            return stat.availableBlocksLong * stat.blockSizeLong
        }

        fun isNoSpace(err: Throwable): Boolean {
            var cur: Throwable? = err
            while (cur != null) {
                val msg = cur.message.orEmpty()
                if (msg.contains("ENOSPC", ignoreCase = true) ||
                    msg.contains("No space", ignoreCase = true) ||
                    msg.contains(ERR_LOW_STORAGE)
                ) {
                    return true
                }
                cur = cur.cause
            }
            return false
        }

        fun formatBytes(bytes: Long): String {
            val n = bytes.coerceAtLeast(0L).toDouble()
            return when {
                n >= 1024.0 * 1024.0 * 1024.0 ->
                    String.format("%.1f GB", n / (1024.0 * 1024.0 * 1024.0))
                n >= 1024.0 * 1024.0 ->
                    String.format("%.0f MB", n / (1024.0 * 1024.0))
                n >= 1024.0 ->
                    String.format("%.0f KB", n / 1024.0)
                else -> "${bytes.coerceAtLeast(0L)} B"
            }
        }

        fun normalizeBudgetMb(mb: Int): Int = when {
            mb >= 1024 -> 1024
            mb >= 512 -> 512
            else -> 256
        }

        fun budgetBytesForMb(mb: Int): Long =
            normalizeBudgetMb(mb).toLong() * 1024L * 1024L

        @Suppress("UNUSED_PARAMETER")
        fun userAgent(): String = "HongguoWatch/${BuildConfig.VERSION_NAME}"
    }
}
