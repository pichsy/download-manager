package com.pichs.download.model

import androidx.annotation.Keep
import com.pichs.download.core.DownloadManager
import kotlinx.coroutines.runBlocking
@Keep
suspend fun DownloadTask.pause(): DownloadTask {
    DownloadManager.pause(this.id)
    return DownloadManager.getTask(this.id) ?: this
}
@Keep
suspend fun DownloadTask.resume(): DownloadTask {
    DownloadManager.resume(this.id)
    return DownloadManager.getTask(this.id) ?: this
}
@Keep
suspend fun DownloadTask.cancel(): DownloadTask {
    DownloadManager.cancel(this.id)
    return DownloadManager.getTask(this.id) ?: this
}

// 旧的监听器API已移除，请使用 DownloadManager.flowListener 进行响应式监听
