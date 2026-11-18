package com.hongguo.watch.data.cache

/**
 * 按设备存储动态计算视频缓存预算（纯逻辑，可单测）。
 *
 * 公式（手表安全：始终预留系统/应用头寸）：
 * ```
 * headroom = max(128MB, total × 5%)
 * usable   = max(0, free + usedCache − headroom)
 * raw      = min(usable × 40%, total × 20%)
 * budget   = min(usable, clamp(raw, floor=min(256MB, usable), ceiling=8GB))
 * ```
 *
 * - [freeBytes]：StatFs 当前可用（不含已占用的缓存文件）
 * - [usedCacheBytes]：已落盘缓存；加回后避免「缓存越多、预算越虚低」
 * - [totalBytes]：分区总容量，限制占比以免占满整盘
 */
object VideoCacheBudget {
    const val HEADROOM_MIN_BYTES = 128L * 1024L * 1024L
    const val HEADROOM_TOTAL_FRACTION = 0.05
    const val USABLE_FRACTION = 0.40
    const val TOTAL_FRACTION = 0.20
    const val FLOOR_BYTES = 256L * 1024L * 1024L
    const val CEILING_BYTES = 8L * 1024L * 1024L * 1024L

    fun compute(freeBytes: Long, totalBytes: Long, usedCacheBytes: Long): Long {
        val free = freeBytes.coerceAtLeast(0L)
        val total = totalBytes.coerceAtLeast(0L)
        val used = usedCacheBytes.coerceAtLeast(0L)
        val headroom = maxOf(
            HEADROOM_MIN_BYTES,
            if (total > 0L) (total * HEADROOM_TOTAL_FRACTION).toLong() else HEADROOM_MIN_BYTES,
        )
        val usable = (free + used - headroom).coerceAtLeast(0L)
        if (usable <= 0L) return 0L

        val fromUsable = (usable * USABLE_FRACTION).toLong()
        val fromTotal =
            if (total > 0L) (total * TOTAL_FRACTION).toLong() else Long.MAX_VALUE
        val raw = minOf(fromUsable, fromTotal)
        val floor = minOf(FLOOR_BYTES, usable)
        val clamped = raw.coerceIn(floor, CEILING_BYTES)
        return minOf(clamped, usable)
    }
}
