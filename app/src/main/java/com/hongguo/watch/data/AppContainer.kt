package com.hongguo.watch.data

import android.content.Context
import com.hongguo.watch.data.cache.VideoCacheManager
import com.hongguo.watch.data.db.AppDatabase

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val database = AppDatabase.create(appContext)
    val settings = SettingsStore(appContext)

    /** 由 Application 在设置变更时刷新；默认 512MB。 */
    @Volatile
    var videoCacheBudgetBytes: Long = VideoCacheManager.DEFAULT_BUDGET_BYTES

    val videoCache = VideoCacheManager(
        context = appContext,
        budgetProvider = { videoCacheBudgetBytes },
    )
    val repository = ContentRepository(
        appSource = AppSourceClient(appContext),
        historyDao = database.watchHistoryDao(),
        favoriteDao = database.favoriteDao(),
        settings = settings,
    )
}
