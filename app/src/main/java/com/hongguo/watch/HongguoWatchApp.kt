package com.hongguo.watch

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.hongguo.watch.data.AppContainer
import com.hongguo.watch.ui.WatchUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class HongguoWatchApp : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        appScope.launch {
            WatchUi.setRoundAdaptOverride(container.settings.roundAdaptOverrideNow())
        }
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizeBytes(12 * 1024 * 1024)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("coil_disk"))
                    .maxSizeBytes(40L * 1024 * 1024)
                    .build()
            }
            .crossfade(false)
            .build()
    }
}
