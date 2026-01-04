package com.pichs.download.store.db

import androidx.annotation.Keep
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.pichs.download.model.ChunkStatus
import com.pichs.download.model.DownloadChunk

@Keep
@Entity(
    tableName = "download_chunks",
    indices = [Index(value = ["taskId", "index"], unique = true)]
)
internal data class DownloadChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: String,
    val index: Int,
    val startByte: Long,
    val endByte: Long,
    val downloaded: Long,
    val status: ChunkStatus,
    val updateTime: Long,
) {
    fun toModel(): DownloadChunk = DownloadChunk(
        taskId, index, startByte, endByte, downloaded, status, updateTime
    )
    
    companion object {
        fun fromModel(chunk: DownloadChunk) = DownloadChunkEntity(
            taskId = chunk.taskId,
            index = chunk.index,
            startByte = chunk.startByte,
            endByte = chunk.endByte,
            downloaded = chunk.downloaded,
            status = chunk.status,
            updateTime = chunk.updateTime
        )
    }
}


