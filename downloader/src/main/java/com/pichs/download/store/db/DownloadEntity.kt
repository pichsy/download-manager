package com.pichs.download.store.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.pichs.download.model.DownloadStatus
import com.pichs.download.model.DownloadTask
import com.pichs.download.model.PauseReason

@Entity(tableName = "downloads")
internal data class DownloadEntity(
    @PrimaryKey val id: String,
    val url: String,
    val fileName: String,
    val filePath: String,
    val status: DownloadStatus,
    val progress: Int,
    val totalSize: Long,
    val currentSize: Long,
    val speed: Long,
    val priority: Int,
    val createTime: Long,
    val updateTime: Long,
    val packageName: String? = null,
    val storeVersionCode: Long? = null,
    val extras: String? = null,
    val pauseReason: PauseReason? = null,
) {
    fun toModel(): DownloadTask = DownloadTask(
        id, url, fileName, filePath, status, progress, totalSize, currentSize, speed, priority, createTime, updateTime, packageName, storeVersionCode, extras, pauseReason
    )
    companion object {
        fun fromModel(t: DownloadTask) = DownloadEntity(
            t.id, t.url, t.fileName, t.filePath, t.status, t.progress, t.totalSize, t.currentSize, t.speed, t.priority, t.createTime, t.updateTime, t.packageName, t.storeVersionCode, t.extras, t.pauseReason
        )
    }
}
