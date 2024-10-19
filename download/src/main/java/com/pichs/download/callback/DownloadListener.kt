package com.pichs.download.callback

import com.pichs.download.DownloadTask

/**
 * Created by pichs on 2017/8/3.
 * 进度监听
 */
abstract class DownloadListener : IDownloadListener {
    override fun onStart(task: DownloadTask?, totalLength: Long) {
    }

    override fun onPause(task: DownloadTask?) {

    }

    override fun onProgress(task: DownloadTask?, currentLength: Long, totalLength: Long, progress: Int, speed: Long) {
    }

    override fun onComplete(task: DownloadTask?) {
    }

    override fun onError(task: DownloadTask?, e: Throwable?) {
    }

    override fun onCancel(task: DownloadTask?) {
    }

}