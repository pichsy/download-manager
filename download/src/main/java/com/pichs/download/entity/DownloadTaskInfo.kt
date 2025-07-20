package com.pichs.download.entity

import android.os.Parcelable
import com.pichs.download.breakpoint.DownloadBreakPointData
import kotlinx.parcelize.Parcelize
import java.io.File


@Parcelize
data class DownloadTaskInfo(
    var taskId: String = "",
    var url: String = "",
    var filePath: String = "",
    var fileName: String = "",
    var currentLength: Long = 0,
    var totalLength: Long = 0,
    var progress: Int = 0,
    var fileMD5: String? = null,
    // 下载任务tag。用于生成taskId, 默认fileMD5,可以自行定义，
    var tag: String? = null,
    //-1:未开始， 0：等待下载，1：下载中，2：暂停，3：完成，4：失败, 5:等待wifi
    var status: Int = DownloadStatus.DEFAULT,
    var info: DownloadBreakPointData? = null,
    // 这个是额外的字段，可以用于存储一些额外的信息
    var speed: Long = 0L,
    var extra: String? = null,
) : Parcelable {

    fun isNotStart() = status == DownloadStatus.DEFAULT

    fun isWait() = status == DownloadStatus.WAITING

    fun isDownloading() = status == DownloadStatus.DOWNLOADING

    fun isPause() = status == DownloadStatus.PAUSE

    fun isComplete() = status == DownloadStatus.COMPLETED

    fun isFail() = status == DownloadStatus.ERROR || status == DownloadStatus.CANCEL

    fun isWaitWifi() = status == DownloadStatus.WAITING_WIFI

    fun isSuccess() = status == DownloadStatus.COMPLETED

    fun getFileAbsolutePath(): String {
        if (filePath.isEmpty() || fileName.isEmpty()) {
            return ""
        }
        return "$filePath${File.separator}$fileName"
    }

    fun getTmpFileAbsolutePath(): String {
        if (filePath.isEmpty() || fileName.isEmpty()) {
            return ""
        }
        return "$filePath${File.separator}${fileName}.tmp"
    }


}

