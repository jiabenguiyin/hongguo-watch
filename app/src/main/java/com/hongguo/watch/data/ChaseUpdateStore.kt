package com.hongguo.watch.data

import android.content.Context

/**
 * 本地追更游标，语义对齐官方书架本地列 `last_serial_count`：
 * 记录用户上次确认过的总集数；当前 `episode_cnt` 更高则视为有更新。
 *
 * 云端优先走 `chase_book/list`；本存储仅作未登录 / 接口失败时的降级。
 */
class ChaseUpdateStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 对齐官方 `last_serial_count`。 */
    fun lastSerialCount(seriesId: String): Int {
        val id = seriesId.trim()
        if (id.isEmpty()) return 0
        return prefs.getInt(key(id), UNSET).coerceAtLeast(0)
    }

    fun hasSeed(seriesId: String): Boolean {
        val id = seriesId.trim()
        if (id.isEmpty()) return false
        return prefs.contains(key(id))
    }

    /** 打开详情/播放后对齐游标，清除更新角标。 */
    fun markSeen(seriesId: String, episodeCount: Int) {
        val id = seriesId.trim()
        if (id.isEmpty()) return
        prefs.edit().putInt(key(id), episodeCount.coerceAtLeast(0)).apply()
    }

    /** 首次收藏时种子化，避免立刻算作「有更新」。 */
    fun seedIfAbsent(seriesId: String, episodeCount: Int) {
        val id = seriesId.trim()
        if (id.isEmpty() || prefs.contains(key(id))) return
        prefs.edit().putInt(key(id), episodeCount.coerceAtLeast(0)).apply()
    }

    fun clear(seriesId: String) {
        val id = seriesId.trim()
        if (id.isEmpty()) return
        prefs.edit().remove(key(id)).apply()
    }

    private fun key(seriesId: String): String = "last_serial_$seriesId"

    companion object {
        private const val PREFS = "chase_updates"
        private const val UNSET = 0
    }
}
