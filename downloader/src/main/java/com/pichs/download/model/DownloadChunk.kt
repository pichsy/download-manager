package com.pichs.download.model

import android.os.Parcelable
import androidx.annotation.Keep
import kotlinx.parcelize.Parcelize

@Parcelize
@Keep
data class DownloadChunk(
    val taskId: String,
    val index: Int,
    val startByte: Long,
    val endByte: Long,
    val downloaded: Long,
    val status: ChunkStatus,
    val updateTime: Long
) : Parcelable

@Keep
enum class ChunkStatus {
    PENDING,    // 等待下载
    DOWNLOADING, // 下载中
    COMPLETED,   // 完成
    FAILED,      // 失败
    CANCELLED    // 取消
}
