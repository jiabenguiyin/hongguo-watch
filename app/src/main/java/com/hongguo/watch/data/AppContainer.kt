package com.hongguo.watch.data

import android.content.Context
import com.hongguo.watch.BuildConfig
import com.hongguo.watch.data.cache.VideoCacheManager
import com.hongguo.watch.data.db.AppDatabase
import com.hongguo.watch.data.redfruit.RedfruitAccountManager
import com.hongguo.watch.data.sync.AccountSyncManager
import com.hongguo.watch.data.sync.SyncTransport

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val guestLibrary = LocalLibrary(AppDatabase.create(appContext))
    internal val accountSync = AccountSyncManager(appContext, guestLibrary, SyncTransport(BuildConfig.SYNC_URL))
    internal val redfruitAccount = RedfruitAccountManager(appContext, accountSync::library)
    val settings = SettingsStore(appContext)
    val timeLock = TimeLockStore(appContext)
    val chaseUpdates = ChaseUpdateStore(appContext)
    /** 预算按本机存储动态计算，见 [com.hongguo.watch.data.cache.VideoCacheBudget]。 */
    val videoCache = VideoCacheManager(context = appContext)
    val repository = ContentRepository(
        appSource = AppSourceClient(appContext),
        currentLibrary = {
            if (redfruitAccount.isLoggedIn()) redfruitAccount.library() else accountSync.library()
        },
        settings = settings,
        onProgressSaved = redfruitAccount::recordProgress,
        onFavoriteChanged = redfruitAccount::recordFavoriteChange,
    )
}
