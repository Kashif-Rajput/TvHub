package com.tvhub.media

import android.app.ActivityManager
import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.tvhub.media.core.Prefs
import com.tvhub.media.core.UsbMonitor
import com.tvhub.media.data.PlaybackStore
import com.tvhub.media.data.ThumbFetcher
import com.tvhub.media.data.ThumbKeyer
import com.tvhub.media.data.YouTubeApi

class App : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        PlaybackStore.init(this)
        UsbMonitor.start(this)
        YouTubeApi.init(this)
    }

    override fun newImageLoader(): ImageLoader {
        val am = getSystemService(ActivityManager::class.java)
        val lowRam = am.isLowRamDevice || am.memoryClass <= 128
        return ImageLoader.Builder(this)
            .components {
                add(ThumbKeyer())
                add(ThumbFetcher.Factory(this@App))
            }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(if (lowRam) 0.12 else 0.20)
                    .build()
            }
            // Only online thumbnails (YouTube) use this; local media thumbnails don't touch it.
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("img"))
                    .maxSizeBytes(40L * 1024 * 1024)
                    .build()
            }
            .crossfade(false)
            .allowRgb565(true)
            .build()
    }
}
