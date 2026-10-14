package com.tvhub.media

import android.app.ActivityManager
import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.memory.MemoryCache
import com.tvhub.media.core.Prefs
import com.tvhub.media.core.UsbMonitor
import com.tvhub.media.data.PlaybackStore
import com.tvhub.media.data.ThumbFetcher
import com.tvhub.media.data.ThumbKeyer

class App : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        PlaybackStore.init(this)
        UsbMonitor.start(this)
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
            // All media is local, so no disk cache is needed.
            .diskCache(null)
            .crossfade(false)
            .allowRgb565(true)
            .build()
    }
}
