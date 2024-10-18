package com.pichs.download.callback

import android.app.TaskInfo

interface IDownloadListener {

    fun onStart(taskInfo: TaskInfo?)
    fun onProgress(taskInfo: TaskInfo?, progress: Int, speed: Long)
    fun onFinish(taskInfo: TaskInfo?)
    fun onError(taskInfo: TaskInfo?, e: Throwable?)
    fun onCancel(taskInfo: TaskInfo?)

}