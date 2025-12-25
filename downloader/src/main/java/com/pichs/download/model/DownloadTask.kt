package com.pichs.download.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class DownloadTask(
    val id: String,
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
    /** 预估大小（字节），用于网络规则判断和弹窗显示 */
    val estimatedSize: Long = 0L,
    /** 是否已确认使用流量下载（任务级别，前置确认后设置） */
    val cellularConfirmed: Boolean = false,
) : Parcelable
