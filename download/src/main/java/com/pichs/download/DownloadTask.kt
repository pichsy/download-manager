package com.pichs.download

import com.pichs.download.callback.IDownloadListener
import com.pichs.download.entity.DownloadStatus
import com.pichs.download.entity.DownloadTaskInfo
import com.pichs.download.utils.TaskIdUtils
import java.io.File

class DownloadTask(val downloadInfo: DownloadTaskInfo) {

    companion object {
        fun create(block: DownloadTaskInfo.() -> Unit): DownloadTask {
            val info = DownloadTaskInfo()
            info.block()
            if (info.taskId.isEmpty()) {
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
        return downloadInfo.status
    }

    fun isNotStart() = getStatus() == DownloadStatus.DEFAULT

    fun isWait() = getStatus() == DownloadStatus.WAITING

    fun isDownloading() = getStatus() == DownloadStatus.DOWNLOADING

    fun isPause() = getStatus() == DownloadStatus.PAUSE

    fun isComplete() = getStatus() == DownloadStatus.COMPLETED

    fun isFail() = getStatus() == DownloadStatus.ERROR || getStatus() == DownloadStatus.CANCEL

    fun isWaitWifi() = getStatus() == DownloadStatus.WAITING_WIFI

    override fun toString(): String {
        return downloadInfo?.toString() ?: ""
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        return getTaskId() == (other as? DownloadTask)?.getTaskId()
    }

    override fun hashCode(): Int {
        return downloadInfo.hashCode()
    }

}