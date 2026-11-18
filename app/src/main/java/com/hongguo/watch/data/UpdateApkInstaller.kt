package com.hongguo.watch.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.hongguo.watch.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 将 GitHub Release APK 下载到应用私有目录，再经 FileProvider 调起系统安装。
 */
object UpdateApkInstaller {
    private const val DIR_NAME = "updates"
    private const val AUTHORITY_SUFFIX = ".fileprovider"

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

    /**
     * 删除 [updatesDir] 内残留安装包（含 `.part`）。
     * 文件已不存在或目录为空时安全返回 0。
     */
    fun clearDownloadedUpdates(context: Context): Int =
        clearUpdatesDirectory(updatesDir(context))

    /** 纯文件逻辑，便于单测；只删本目录下的 apk / part，不递归。 */
    fun clearUpdatesDirectory(dir: File): Int {
        if (!dir.exists() || !dir.isDirectory) return 0
        var removed = 0
        val children = dir.listFiles() ?: return 0
        for (file in children) {
            if (!file.isFile) continue
            val name = file.name
            val isApkOrPart = name.endsWith(".apk", ignoreCase = true) ||
                name.endsWith(".part", ignoreCase = true)
            if (!isApkOrPart) continue
            if (file.delete() || !file.exists()) {
                removed++
            }
        }
        return removed
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
     */
    fun download(
        context: Context,
        url: String,
        apkName: String?,
        versionName: String,
        cancel: AtomicBoolean = AtomicBoolean(false),
        onProgress: (percent: Int) -> Unit = {},
    ): File {
        val target = resolveLocalFile(context, apkName, versionName)
        if (target.exists() && target.length() > 0L) {
            onProgress(100)
            return target
        }
        val partial = File(target.parentFile, "${target.name}.part")
        if (partial.exists()) partial.delete()

        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/octet-stream")
            .header("User-Agent", "HongguoWatch/${BuildConfig.VERSION_NAME}")
            .get()
            .build()

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
    }
}
