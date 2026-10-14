package com.tvhub.media.data

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import java.io.File

data class VolumeInfo(
    val id: String,
    val label: String,
    val dir: File?,
    val removable: Boolean,
    val primary: Boolean,
    val mounted: Boolean,
    val mediaStoreName: String?,
    val totalBytes: Long,
    val freeBytes: Long,
    val volume: StorageVolume
)

object Volumes {
    /** Cheap: no disk scanning, just mount table + free space. Call off the main thread anyway. */
    fun list(context: Context): List<VolumeInfo> {
        val sm = context.getSystemService(StorageManager::class.java) ?: return emptyList()
        return sm.storageVolumes.map { v ->
            val dir = v.directory
            val mounted = v.state == Environment.MEDIA_MOUNTED ||
                v.state == Environment.MEDIA_MOUNTED_READ_ONLY
            var total = 0L
            var free = 0L
            if (mounted && dir != null) {
                runCatching {
                    val st = StatFs(dir.path)
                    total = st.totalBytes
                    free = st.availableBytes
                }
            }
            VolumeInfo(
                id = v.uuid ?: "primary",
                label = v.getDescription(context) ?: "Storage",
                dir = dir,
                removable = v.isRemovable,
                primary = v.isPrimary,
                mounted = mounted,
                mediaStoreName = v.mediaStoreVolumeName,
                totalBytes = total,
                freeBytes = free,
                volume = v
            )
        }
    }

    fun removable(context: Context): List<VolumeInfo> = list(context).filter { it.removable && it.mounted }
}
