package com.tvhub.media.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Event-driven USB/SD detection. No polling: the system tells us on mount/unmount.
 * Uses both the classic media broadcasts and the Android 11 storage-volume callback,
 * because cheap TV firmwares sometimes only send one of them.
 */
object UsbMonitor {
    data class Event(val mounted: Boolean, val path: String?, val time: Long)

    private val _events = MutableStateFlow(Event(false, null, 0L))
    val events: StateFlow<Event> = _events

    private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addDataScheme("file")
        }
        ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                emit(intent.action == Intent.ACTION_MEDIA_MOUNTED, intent.data?.path)
            }
        }, filter, ContextCompat.RECEIVER_EXPORTED)

        if (Build.VERSION.SDK_INT >= 30) runCatching {
            val sm = app.getSystemService(StorageManager::class.java)
            sm.registerStorageVolumeCallback(ContextCompat.getMainExecutor(app),
                object : StorageManager.StorageVolumeCallback() {
                    override fun onStateChanged(volume: StorageVolume) {
                        val mounted = volume.state == Environment.MEDIA_MOUNTED ||
                            volume.state == Environment.MEDIA_MOUNTED_READ_ONLY
                        emit(mounted, volume.directory?.path)
                    }
                })
        }
    }

    private fun emit(mounted: Boolean, path: String?) {
        val now = SystemClock.elapsedRealtime()
        val last = _events.value
        // The two sources often fire together; collapse duplicates.
        if (last.mounted == mounted && last.path == path && now - last.time < 1500) return
        _events.value = Event(mounted, path, now)
    }
}
