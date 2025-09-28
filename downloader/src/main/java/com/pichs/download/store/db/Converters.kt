package com.pichs.download.store.db

import androidx.room.TypeConverter
import com.pichs.download.model.DownloadStatus

internal object Converters {
    @TypeConverter
    @JvmStatic
    fun toStatus(value: String?): DownloadStatus = runCatching { DownloadStatus.valueOf(value ?: "PENDING") }.getOrDefault(DownloadStatus.PENDING)

    @TypeConverter
    @JvmStatic
    fun fromStatus(status: DownloadStatus?): String = status?.name ?: DownloadStatus.PENDING.name
}
