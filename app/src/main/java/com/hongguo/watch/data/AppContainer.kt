package com.hongguo.watch.data

import android.content.Context
import com.hongguo.watch.data.cache.VideoCacheManager
import com.hongguo.watch.data.db.AppDatabase

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val database = AppDatabase.create(appContext)
    val settings = SettingsStore(appContext)
    /** 预算按本机存储动态计算，见 [com.hongguo.watch.data.cache.VideoCacheBudget]。 */
    val videoCache = VideoCacheManager(context = appContext)
    val repository = ContentRepository(
        appSource = AppSourceClient(appContext),
        historyDao = database.watchHistoryDao(),
        favoriteDao = database.favoriteDao(),
        settings = settings,
    )
}
