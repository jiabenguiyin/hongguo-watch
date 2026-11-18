package com.hongguo.watch

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import com.hongguo.watch.data.AppContainer
import com.hongguo.watch.data.cache.VideoCacheManager
import com.hongguo.watch.ui.WatchUi
import com.hongguo.watch.upstream.VendorConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class HongguoWatchApp : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        appScope.launch {
            WatchUi.setRoundAdaptOverride(container.settings.roundAdaptOverrideNow())
            container.videoCacheBudgetBytes = container.settings.videoCacheBudgetBytesNow()
            container.settings.videoCacheBudgetMb.collect { mb ->
                container.videoCacheBudgetBytes = VideoCacheManager.budgetBytesForMb(mb)
            }
        }
    }

    override fun newImageLoader(): ImageLoader {
        // 封面 CDN 常校验 Referer；手表 ROM 对无头请求更苛刻
        val imageHttp = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("User-Agent", VendorConstants.VIDEO_UA)
                    .header("Referer", "https://novel.snssdk.com/")
                    .header("Accept", "image/webp,image/avif,image/*,*/*;q=0.8")
                    .build()
                chain.proceed(req)
            }
            .build()
        return ImageLoader.Builder(this)
            .okHttpClient(imageHttp)
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
            .respectCacheHeaders(false)
            .diskCachePolicy(CachePolicy.ENABLED)
            .crossfade(false)
            .build()
    }
}
