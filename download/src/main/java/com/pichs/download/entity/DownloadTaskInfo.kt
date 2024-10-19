package com.pichs.download.entity

import android.os.Parcelable
import com.pichs.download.breakpoint.DownloadBreakPointData
import kotlinx.parcelize.Parcelize


@Parcelize
data class DownloadTaskInfo(
    var taskId: String = "",
    var url: String? = "",
    var filePath: String? = null,
    var fileName: String? = null,
    var currentLength: Long? = 0,
    var totalLength: Long? = 0,
    var progress: Int? = 0,
    var fileMD5: String? = null,
    //-1:未开始， 0：等待下载，1：下载中，2：暂停，3：完成，4：失败, 5:等待wifi
    var status: Int = -1,
    var info: DownloadBreakPointData? = null
) : Parcelable {

    fun isWait() = status == 0

    fun isDownloading() = status == 1

    fun isPause() = status == 2

    fun isComplete() = status == 3

    fun isFail() = status == 4

    fun isWaitWifi() = status == 5

    fun isSuccess() = status == 3
}

