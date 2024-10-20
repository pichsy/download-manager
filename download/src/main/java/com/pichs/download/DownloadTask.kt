package com.pichs.download

import com.pichs.download.callback.IDownloadListener
import com.pichs.download.entity.DownloadTaskInfo
import com.pichs.download.utils.TaskIdUtils
import java.io.File

class DownloadTask(val downloadInfo: DownloadTaskInfo) {

    companion object {
        fun create(block: DownloadTaskInfo.() -> Unit): DownloadTask {
            val info = DownloadTaskInfo()
            info.block()
            if (info.taskId.isNullOrEmpty()) {
                info.taskId = TaskIdUtils.generateTaskId(
                    info.url,
                    info.fileName,
                    info.filePath,
                    info.tag ?: info.fileMD5 ?: ""
                )
            }
            return DownloadTask(info)
        }
    }

    fun pushTask(): DownloadTask {
        Downloader.with().downloadQueueDispatcher.pushTask(this)
        return this
    }

    fun addListener(listener: IDownloadListener?): DownloadTask {
        Downloader.with().downloadQueueDispatcher.addListener(getTaskId(), listener)
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

    /**
     * 获取文件绝对路径
     */
    fun getFileAbsolutePath(): String? {
        return downloadInfo.getFileAbsolutePath()
    }

    /**
     * 获取临时文件绝对路径
     */
    fun getTmpFileAbsolutePath(): String? {
        return downloadInfo.getTmpFileAbsolutePath()
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