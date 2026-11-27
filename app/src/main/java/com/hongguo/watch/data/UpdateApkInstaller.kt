package com.hongguo.watch.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.hongguo.watch.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 将 GitHub Release APK 下载到应用私有目录，再经 FileProvider 调起系统安装。
 * 安装成功启动后会清理残留；冷启动 / 首页恢复也会再扫一遍。
 */
object UpdateApkInstaller {
    private const val TAG = "UpdateApk"
    private const val DIR_NAME = "updates"
    private const val AUTHORITY_SUFFIX = ".fileprovider"
    private const val PREFS = "update_apk_installer"
    private const val KEY_PENDING_CLEANUP = "pending_cleanup"
    private const val KEY_LAST_CLEANED_VERSION = "last_cleaned_version_code"
    private const val KEY_PENDING_DELETED_TOAST = "pending_deleted_toast"

    private val cleanupExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "update-apk-cleanup").apply { isDaemon = true }
    }

    /** 串行化清盘，避免 Application 异步与首页 onResume 竞态丢掉 toast 标记。 */
    private val cleanupLock = Any()

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun updatesDir(context: Context): File {
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 所有可能落下更新包的目录（files / external-files / cache）。 */
    fun candidateUpdateDirs(context: Context): List<File> {
        val app = context.applicationContext
        val dirs = ArrayList<File>(4)
        dirs.add(File(app.filesDir, DIR_NAME))
        app.getExternalFilesDir(null)?.let { dirs.add(File(it, DIR_NAME)) }
        dirs.add(File(app.cacheDir, DIR_NAME))
        app.externalCacheDir?.let { dirs.add(File(it, DIR_NAME)) }
        return dirs
    }

    /**
     * 删除各 updates 目录内残留安装包（含 `.part`），以及 cache 根下的孤立 apk/part。
     * 永不抛错；删失败只打日志。
     */
    fun clearDownloadedUpdates(context: Context): Int {
        synchronized(cleanupLock) {
            var removed = 0
            for (dir in candidateUpdateDirs(context)) {
                removed += clearUpdatesDirectory(dir)
            }
            removed += clearOrphanUpdateFiles(context.applicationContext.cacheDir)
            context.applicationContext.externalCacheDir?.let {
                removed += clearOrphanUpdateFiles(it)
            }
            if (removed > 0) {
                Log.i(TAG, "cleared $removed update artifact(s)")
                markPendingDeletedToast(context)
            }
            markCleaned(context, stillHasArtifacts = hasAnyUpdateArtifacts(context))
            return removed
        }
    }

    /** 任一候选目录里是否还剩 apk/part。 */
    fun hasAnyUpdateArtifacts(context: Context): Boolean {
        for (dir in candidateUpdateDirs(context)) {
            if (!dir.exists() || !dir.isDirectory) continue
            val children = dir.listFiles() ?: continue
            if (children.any { it.isFile && isUpdateArtifactName(it.name) }) return true
        }
        val cache = context.applicationContext.cacheDir
        if (orphanUpdateCount(cache) > 0) return true
        context.applicationContext.externalCacheDir?.let {
            if (orphanUpdateCount(it) > 0) return true
        }
        return false
    }

    private fun orphanUpdateCount(dir: File?): Int {
        if (dir == null || !dir.exists() || !dir.isDirectory) return 0
        val children = dir.listFiles() ?: return 0
        return children.count { file ->
            file.isFile && isUpdateArtifactName(file.name) &&
                (file.name.lowercase().contains("hongguo") ||
                    file.name.lowercase().startsWith("update"))
        }
    }

    /** 纯文件逻辑，便于单测；只删本目录下的 apk / part，不递归。 */
    fun clearUpdatesDirectory(dir: File): Int {
        if (!dir.exists() || !dir.isDirectory) return 0
        var removed = 0
        val children = dir.listFiles() ?: return 0
        for (file in children) {
            if (!file.isFile) continue
            if (!isUpdateArtifactName(file.name)) continue
            if (deleteQuietly(file)) {
                removed++
            }
        }
        return removed
    }

    /** cache 根目录里误落的更新包（非 updates/ 子目录）。 */
    fun clearOrphanUpdateFiles(dir: File?): Int {
        if (dir == null || !dir.exists() || !dir.isDirectory) return 0
        var removed = 0
        val children = dir.listFiles() ?: return 0
        for (file in children) {
            if (!file.isFile) continue
            if (!isUpdateArtifactName(file.name)) continue
            // 只清看起来像本应用更新包的名字，避免误删无关缓存
            val lower = file.name.lowercase()
            if (!lower.contains("hongguo") && !lower.startsWith("update")) continue
            if (deleteQuietly(file)) {
                removed++
            }
        }
        return removed
    }

    fun isUpdateArtifactName(name: String): Boolean {
        return name.endsWith(".apk", ignoreCase = true) ||
            name.endsWith(".part", ignoreCase = true)
    }

    private fun deleteQuietly(file: File): Boolean {
        return try {
            if (!file.exists()) return true
            if (file.delete()) return true
            // 部分手表 ROM 上刚被安装器打开过的文件需可写后再删
            file.setWritable(true)
            if (file.delete()) return true
            if (!file.exists()) return true
            Log.w(TAG, "failed to delete ${file.absolutePath}")
            false
        } catch (t: Throwable) {
            Log.w(TAG, "delete error ${file.absolutePath}: ${t.message}")
            false
        }
    }

    fun resolveLocalFile(context: Context, apkName: String?, versionName: String): File {
        val safe = sanitizeFileName(
            apkName?.takeIf { it.endsWith(".apk", ignoreCase = true) }
                ?: "hongguo-watch-$versionName.apk",
        )
        return File(updatesDir(context), safe)
    }

    fun sanitizeFileName(name: String): String {
        val base = name.substringAfterLast('/').ifBlank { "update.apk" }
        return base.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }

    /**
     * 同步下载。返回目标文件；[cancel] 为 true 时中止并删除半成品。
     * [onProgress] 进度 0–100；未知总长时按已下字节估算上限 99。
     * [urls] 可含镜像与官方候选；按序尝试，取消时立即停止。
     */
    fun download(
        context: Context,
        url: String,
        apkName: String?,
        versionName: String,
        cancel: AtomicBoolean = AtomicBoolean(false),
        urls: List<String> = listOf(url),
        onProgress: (percent: Int) -> Unit = {},
    ): File {
        val target = resolveLocalFile(context, apkName, versionName)
        if (target.exists() && target.length() > 0L) {
            onProgress(100)
            return target
        }
        val candidates = urls.map { it.trim() }.filter { it.isNotEmpty() }
            .ifEmpty { listOf(url).map { it.trim() }.filter { it.isNotEmpty() } }
        if (candidates.isEmpty()) throw IllegalStateException("空下载地址")

        var lastError: Exception? = null
        for (candidate in candidates) {
            if (cancel.get()) throw InterruptedException("cancelled")
            try {
                return downloadOnce(
                    target = target,
                    url = candidate,
                    cancel = cancel,
                    onProgress = onProgress,
                )
            } catch (cancelled: InterruptedException) {
                throw cancelled
            } catch (error: Exception) {
                lastError = error
                Log.w(TAG, "download candidate failed: ${error.message}")
            }
        }
        throw lastError ?: IllegalStateException("下载失败")
    }

    private fun downloadOnce(
        target: File,
        url: String,
        cancel: AtomicBoolean,
        onProgress: (percent: Int) -> Unit,
    ): File {
        val partial = File(target.parentFile, "${target.name}.part")
        if (partial.exists()) partial.delete()
        onProgress(0)

        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/octet-stream")
            .header("User-Agent", "HongguoWatch/${BuildConfig.VERSION_NAME}")
            .get()
            .build()

        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("HTTP ${response.code}")
                }
                val body = response.body ?: throw IllegalStateException("空响应")
                val total = body.contentLength()
                body.byteStream().use { input ->
                    FileOutputStream(partial).use { output ->
                        val buf = ByteArray(16 * 1024)
                        var complete = 0L
                        var lastPercent = -1
                        while (true) {
                            if (cancel.get()) {
                                throw InterruptedException("cancelled")
                            }
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            complete += n
                            val percent = when {
                                total > 0L -> ((complete * 100L) / total).toInt().coerceIn(0, 100)
                                else -> (complete / (256L * 1024L)).toInt().coerceIn(0, 99)
                            }
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                        output.flush()
                    }
                }
            }
        } catch (error: Exception) {
            partial.delete()
            throw error
        }

        if (cancel.get()) {
            partial.delete()
            throw InterruptedException("cancelled")
        }
        if (target.exists()) target.delete()
        if (!partial.renameTo(target)) {
            partial.copyTo(target, overwrite = true)
            partial.delete()
        }
        if (!target.exists() || target.length() <= 0L) {
            throw IllegalStateException("文件写入失败")
        }
        onProgress(100)
        return target
    }

    fun canRequestInstall(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun openUnknownSourcesSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun install(context: Context, apkFile: File) {
        if (!apkFile.exists() || apkFile.length() <= 0L) {
            throw IllegalStateException("安装包不存在")
        }
        if (!canRequestInstall(context)) {
            openUnknownSourcesSettings(context)
            throw SecurityException("need_install_permission")
        }
        val uri = FileProvider.getUriForFile(
            context,
            context.packageName + AUTHORITY_SUFFIX,
            apkFile,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        // 安装器已拿到 URI：立刻记待清理，并尽快删盘（再加冷启动兜底）
        markPendingCleanup(context)
        scheduleCleanupAfterInstall(context)
    }

    /** 安装 Intent 已发出：后台立刻清一次（失败不抛）。 */
    fun scheduleCleanupAfterInstall(context: Context) {
        val app = context.applicationContext
        markPendingCleanup(app)
        cleanupExecutor.execute {
            try {
                // 稍等：给安装器一点打开流的时间，再删文件
                Thread.sleep(1_500L)
                val n = clearDownloadedUpdates(app)
                Log.i(TAG, "post-install cleanup removed=$n")
            } catch (t: Throwable) {
                Log.w(TAG, "post-install cleanup failed: ${t.message}")
            }
        }
    }

    /**
     * 冷启动：无条件扫盘（覆盖安装后旧进程可能没来得及删）。
     * 若实际删了文件，会置 [consumeDeletedToast] 标记供首页提示。
     */
    fun cleanupOnAppStart(context: Context): Int {
        return try {
            clearDownloadedUpdates(context.applicationContext)
        } catch (t: Throwable) {
            Log.w(TAG, "cleanupOnAppStart failed: ${t.message}")
            0
        }
    }

    /**
     * 是否有「已删除更新包」待提示。消费即清零；无删除时不会置位，避免每次冷启动 toast。
     */
    fun consumeDeletedToast(context: Context): Boolean {
        return try {
            // 等正在进行的清盘写完标记，避免与 Application 异步竞态
            synchronized(cleanupLock) {
                val p = prefs(context.applicationContext)
                if (!p.getBoolean(KEY_PENDING_DELETED_TOAST, false)) return false
                p.edit().putBoolean(KEY_PENDING_DELETED_TOAST, false).apply()
                true
            }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 首页恢复：仅在「待清理」或 versionCode 相对上次清理已变化时扫盘，
     * 避免每次 onResume 都打盘。
     */
    fun cleanupAfterVersionBumpIfNeeded(context: Context): Int {
        return try {
            val app = context.applicationContext
            val prefs = prefs(app)
            val pending = prefs.getBoolean(KEY_PENDING_CLEANUP, true) // 缺省 true：旧安装无标记时也扫一次
            val last = prefs.getInt(KEY_LAST_CLEANED_VERSION, -1)
            val current = BuildConfig.VERSION_CODE
            if (!pending && last == current) return 0
            clearDownloadedUpdates(app)
        } catch (t: Throwable) {
            Log.w(TAG, "cleanupAfterVersionBumpIfNeeded failed: ${t.message}")
            0
        }
    }

    fun hasPendingCleanup(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PENDING_CLEANUP, false)

    private fun markPendingCleanup(context: Context) {
        runCatching {
            prefs(context).edit().putBoolean(KEY_PENDING_CLEANUP, true).apply()
        }
    }

    private fun markPendingDeletedToast(context: Context) {
        runCatching {
            prefs(context).edit().putBoolean(KEY_PENDING_DELETED_TOAST, true).apply()
        }
    }

    private fun markCleaned(context: Context, stillHasArtifacts: Boolean) {
        runCatching {
            val editor = prefs(context).edit()
            if (stillHasArtifacts) {
                // 仍有残留：保持待清理，下次冷启动 / onResume 再试
                editor.putBoolean(KEY_PENDING_CLEANUP, true)
                Log.w(TAG, "update artifacts still present; pending cleanup kept")
            } else {
                editor.putBoolean(KEY_PENDING_CLEANUP, false)
                    .putInt(KEY_LAST_CLEANED_VERSION, BuildConfig.VERSION_CODE)
                Log.d(TAG, "update dirs clean @${BuildConfig.VERSION_NAME}")
            }
            editor.apply()
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
