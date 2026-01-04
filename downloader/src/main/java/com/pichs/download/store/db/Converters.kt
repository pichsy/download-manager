package com.pichs.download.store.db

import androidx.annotation.Keep
import androidx.room.TypeConverter
import com.pichs.download.model.DownloadStatus

@Keep
internal object Converters {
    @TypeConverter
    @JvmStatic
    fun toStatus(value: String?): DownloadStatus = runCatching { DownloadStatus.valueOf(value ?: "PENDING") }.getOrDefault(DownloadStatus.PENDING)

    @TypeConverter
    @JvmStatic
    fun fromStatus(status: DownloadStatus?): String = status?.name ?: DownloadStatus.PENDING.name
}
