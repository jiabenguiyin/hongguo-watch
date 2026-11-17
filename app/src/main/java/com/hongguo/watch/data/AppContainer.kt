package com.hongguo.watch.data

import android.content.Context
import com.hongguo.watch.data.db.AppDatabase

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val database = AppDatabase.create(appContext)
    val settings = SettingsStore(appContext)
    val repository = ContentRepository(
        appSource = AppSourceClient(appContext),
        official = OfficialWebClient(),
        historyDao = database.watchHistoryDao(),
        settings = settings,
    )
}
