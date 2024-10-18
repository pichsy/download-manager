package com.pichs.download.breakpoint

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize

/**
 * 分块下载的缓存，断点续传
 */
@Entity(
    tableName = DownloadChunkManager.TABLE_NAME_CHUNK,
    // 请以taskId，chunkIndex为唯一索引
    primaryKeys = ["taskId", "chunkIndex"]

)
@Parcelize
data class DownloadChunk(
    var taskId: String = "",
    var chunkIndex: Int = 0,
    var start: Long = 0L,
    var end: Long = 0L,
    var downloadedBytes: Long
) : Parcelable