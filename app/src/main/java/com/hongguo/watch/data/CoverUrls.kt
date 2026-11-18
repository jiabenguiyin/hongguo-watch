package com.hongguo.watch.data

import com.hongguo.watch.upstream.cleanUrl
import java.net.URI

/**
 * 封面 URL 归一化。App 族常返回 HEIC（fqnovelpic / byteimg），
 * 华强北手表 ROM 多数无 HEIC 解码 → Coil 只剩灰占位。
 * 对照公开仓 catalogCover：改写为 JPEG 对象变体。
 */
object CoverUrls {
    private val HEIC_PATH = Regex("""\.heic$""", RegexOption.IGNORE_CASE)
    private val COVER_HOST = Regex(
        """(^|\.)(fqnovelpic\.com|byteimg\.com|pstatp\.com)$""",
        RegexOption.IGNORE_CASE,
    )

    fun normalize(raw: String): String {
        val cleaned = cleanUrl(raw)
        if (cleaned.isBlank()) return ""
        val withScheme = when {
            cleaned.startsWith("https://", ignoreCase = true) -> cleaned
            cleaned.startsWith("http://", ignoreCase = true) ->
                "https://" + cleaned.substring(7)
            cleaned.startsWith("//") -> "https:$cleaned"
            else -> return ""
        }
        return rewriteHeic(withScheme) ?: withScheme.takeUnless { isHeic(it) }.orEmpty()
    }

    private fun isHeic(url: String): Boolean {
        val path = runCatching { URI(url).path }.getOrNull().orEmpty()
        return HEIC_PATH.containsMatchIn(path) ||
            url.contains(".heic", ignoreCase = true)
    }

    /**
     * `…/novel-pic/…~….heic` → `https://p3-novel.byteimg.com{base}~tplv-shrink:640:0.image`
     * 非该形态的 HEIC 无法安全改写时返回 null（调用方再丢弃）。
     */
    private fun rewriteHeic(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val path = uri.path.orEmpty()
        if (!HEIC_PATH.containsMatchIn(path)) return null
        val host = uri.host.orEmpty()
        if (host.isNotBlank() && !COVER_HOST.containsMatchIn(host)) return null
        if (!path.startsWith("/novel-pic/") || !path.contains('~')) return null
        val base = path.substringBefore('~')
        if (base.isBlank()) return null
        return "https://p3-novel.byteimg.com$base~tplv-shrink:640:0.image"
    }
}
