package com.pichs.download.dispatcher

import com.pichs.download.DownloadTask
import com.pichs.download.call.DownloadMultiCall

interface DispatcherListener {
    fun onStart(call: DownloadMultiCall, task: DownloadTask?, totalLength: Long)
    fun onPause(call: DownloadMultiCall, task: DownloadTask?)
    fun onProgress(call: DownloadMultiCall, task: DownloadTask?, currentLength: Long, totalLength: Long, progress: Int, speed: Long)
    fun onComplete(call: DownloadMultiCall, task: DownloadTask?)
    fun onError(call: DownloadMultiCall, task: DownloadTask?, e: Throwable?)
    fun onCancel(call: DownloadMultiCall, task: DownloadTask?)
}