package com.pichs.download.callback

import com.pichs.download.DownloadTask

interface IDownloadListener {
    fun onPrepare(task: DownloadTask?)
    fun onStart(task: DownloadTask?, totalLength: Long)
    fun onPause(task: DownloadTask?)
    fun onProgress(task: DownloadTask?, currentLength: Long, totalLength: Long, progress: Int, speed: Long)
    fun onComplete(task: DownloadTask?)
    fun onError(task: DownloadTask?, e: Throwable?)
    fun onCancel(task: DownloadTask?)
}