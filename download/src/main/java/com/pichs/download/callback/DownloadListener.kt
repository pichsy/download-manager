package com.pichs.download.callback

import android.app.TaskInfo

class DownloadListener : IDownloadListener {
    override fun onStart(taskInfo: TaskInfo?) {
    }

    override fun onProgress(taskInfo: TaskInfo?, progress: Int, currentLength: Long, totalLength: Long, speed: Long) {
    }

    override fun onComplete(taskInfo: TaskInfo?) {
    }

    override fun onError(taskInfo: TaskInfo?, e: Throwable?) {
    }

    override fun onCancel(taskInfo: TaskInfo?) {
    }

}