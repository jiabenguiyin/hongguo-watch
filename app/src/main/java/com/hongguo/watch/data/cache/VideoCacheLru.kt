package com.hongguo.watch.data.cache

/**
 * 纯逻辑 LRU：按 [VideoCacheEntry.lastAccessMs] 升序淘汰，直到占用 ≤ 预算且能容纳 [neededBytes]。
 * 不碰磁盘；由 [VideoCacheManager] 执行删除。
 */
object VideoCacheLru {
    /**
     * @return 需要删除的条目（最旧优先）；若即使清空仍无法腾出 [neededBytes] 则返回全部 ready 条目。
     */
    fun pickVictims(
        entries: List<VideoCacheEntry>,
        usedBytes: Long,
        budgetBytes: Long,
        neededBytes: Long,
    ): List<VideoCacheEntry> {
        if (neededBytes < 0L) return emptyList()
        val ready = entries.filter { it.ready && it.sizeBytes > 0L }
            .sortedBy { it.lastAccessMs }
        var used = usedBytes.coerceAtLeast(0L)
        val target = (budgetBytes - neededBytes).coerceAtLeast(0L)
        if (used <= target) return emptyList()
        val victims = ArrayList<VideoCacheEntry>()
        for (entry in ready) {
            if (used <= target) break
            victims += entry
            used -= entry.sizeBytes
        }
        return victims
    }

    fun totalReadyBytes(entries: List<VideoCacheEntry>): Long =
        entries.asSequence().filter { it.ready }.sumOf { it.sizeBytes.coerceAtLeast(0L) }
}
