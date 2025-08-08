package com.pichs.download.model

import com.pichs.download.core.DownloadManager
import com.pichs.download.listener.DownloadListener

fun DownloadTask.pause(): DownloadTask {
    DownloadManager.pause(this.id)
    return DownloadManager.getTask(this.id) ?: this
}

fun DownloadTask.resume(): DownloadTask {
    DownloadManager.resume(this.id)
    return DownloadManager.getTask(this.id) ?: this
}

fun DownloadTask.cancel(): DownloadTask {
    DownloadManager.cancel(this.id)
    return DownloadManager.getTask(this.id) ?: this
}

fun DownloadTask.addListener(listener: DownloadListener): DownloadTask {
    DownloadManager.addTaskListener(this.id, listener)
    return this
}

fun DownloadTask.removeListener(listener: DownloadListener): DownloadTask {
    DownloadManager.removeTaskListener(this.id, listener)
    return this
}
