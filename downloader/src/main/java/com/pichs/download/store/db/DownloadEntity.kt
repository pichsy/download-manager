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
    val estimatedSize: Long = 0L,  // 新增：预估大小
) {
    fun toModel(): DownloadTask = DownloadTask(
        id = id,
        url = url,
        fileName = fileName,
        filePath = filePath,
        status = status,
        progress = progress,
        totalSize = totalSize,
        currentSize = currentSize,
        speed = speed,
        priority = priority,
        createTime = createTime,
        updateTime = updateTime,
        packageName = packageName,
        storeVersionCode = storeVersionCode,
        extras = extras,
        pauseReason = pauseReason,
        estimatedSize = estimatedSize,
    )
    companion object {
        fun fromModel(t: DownloadTask) = DownloadEntity(
            id = t.id,
            url = t.url,
            fileName = t.fileName,
            filePath = t.filePath,
            status = t.status,
            progress = t.progress,
            totalSize = t.totalSize,
            currentSize = t.currentSize,
            speed = t.speed,
            priority = t.priority,
            createTime = t.createTime,
            updateTime = t.updateTime,
            packageName = t.packageName,
            storeVersionCode = t.storeVersionCode,
            extras = t.extras,
            pauseReason = t.pauseReason,
            estimatedSize = t.estimatedSize,
        )
    }
}
