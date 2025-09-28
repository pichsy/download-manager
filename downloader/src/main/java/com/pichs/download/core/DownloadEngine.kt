package com.pichs.download.core

import com.pichs.download.model.DownloadTask

/**
 * 下载引擎占位：后续接入 OkHttp + 协程 + 断点续传
 */
internal interface DownloadEngine {
    suspend fun start(task: DownloadTask)
    fun pause(taskId: String)
    fun resume(taskId: String)
    fun cancel(taskId: String)
}
