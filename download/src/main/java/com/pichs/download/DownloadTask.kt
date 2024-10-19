package com.pichs.download

import com.pichs.download.callback.IDownloadListener
import com.pichs.download.entity.DownloadTaskInfo
import com.pichs.download.utils.TaskIdUtils

class DownloadTask(var downloadInfo: DownloadTaskInfo? = null) {

    companion object {
        fun build(block: DownloadTaskInfo.() -> Unit): DownloadTask {
            val info = DownloadTaskInfo()
            block.invoke(info)
            if (info.taskId.isEmpty()) {
                info.taskId = TaskIdUtils.generateTaskId(info.url, info.filePath, info.fileName, info.tag ?: "")
            }
            return DownloadTask(info)
        }
    }

    fun addToQueue(): DownloadTask {
        Downloader.with().addTask(this)
        return this
    }

    fun addListener(listener: IDownloadListener?): DownloadTask {
        Downloader.with().addListener(getTaskId(), listener)
        return this
    }

    fun getTaskId(): String {
        return downloadInfo?.taskId ?: ""
    }

    fun getUrl(): String {
        return downloadInfo?.url ?: ""
    }

    fun getFilePath(): String {
        return downloadInfo?.filePath ?: ""
    }

    fun getFileName(): String {
        return downloadInfo?.fileName ?: ""
    }

    fun getFileTotalSize(): Long {
        return downloadInfo?.totalLength ?: 0L
    }


    fun getProgress(): Int {
        return downloadInfo?.progress ?: 0
    }

    fun getStatus(): Int {
        return downloadInfo?.status ?: 0
    }

    fun isWait() = getStatus() == 0

    fun isDownloading() = getStatus() == 1

    fun isPause() = getStatus() == 2

    fun isComplete() = getStatus() == 3

    fun isFail() = getStatus() == 4

    fun isWaitWifi() = getStatus() == 5

    fun isSuccess() = getStatus() == 3

    override fun toString(): String {
        return downloadInfo?.toString() ?: ""
    }


}