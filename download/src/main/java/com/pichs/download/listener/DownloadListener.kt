package com.pichs.download.listener

import com.pichs.download.model.DownloadTask
import java.io.File

interface DownloadListener {
    fun onTaskStart(task: DownloadTask) {}
    fun onTaskProgress(task: DownloadTask, progress: Int, speed: Long) {}
    fun onTaskComplete(task: DownloadTask, file: File) {}
    fun onTaskError(task: DownloadTask, error: Throwable) {}
    fun onTaskPause(task: DownloadTask) {}
    fun onTaskResume(task: DownloadTask) {}
    fun onTaskCancel(task: DownloadTask) {}
}

interface ProgressListener {
    fun onProgress(taskId: String, progress: Int, speed: Long) {}
    fun onComplete(taskId: String, file: File) {}
    fun onError(taskId: String, error: Throwable) {}
}

interface StatusListener {
    fun onStatusChanged(taskId: String, oldStatus: com.pichs.download.model.DownloadStatus, newStatus: com.pichs.download.model.DownloadStatus) {}
}
