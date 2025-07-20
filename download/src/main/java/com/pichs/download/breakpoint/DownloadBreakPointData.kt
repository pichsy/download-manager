package com.pichs.download.breakpoint

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.pichs.download.breakpoint.DownloadBreakPointManger.TABLE_NAME_BREAK_POINT
import com.pichs.download.entity.DownloadStatus
import kotlinx.parcelize.Parcelize


/**
 * 断点续传的缓存
 */
@Entity(tableName = TABLE_NAME_BREAK_POINT)
@Parcelize
data class DownloadBreakPointData(
    @PrimaryKey
    var taskId: String = "",
    var tag: String = "",
    var url: String = "",
    // 是否分片,<=1:不分片，n：分片数量
    var chunkCount: Int = 1,
    var filePath: String = "",
    var fileName: String = "",
    var currentLength: Long = 0,
    var totalLength: Long = 0,
    var fileMD5: String? = null,
    var progress: Int = 0,
    // 0：等待下载，1：下载中，2：暂停，3：完成，4：失败, 5:等待wifi
    var status: Int = -1,
    var createTime: Long = 0L,
    var updateTime: Long = 0L,
    var extra: String? = null,
) : Parcelable {

    fun isNotStart() = status == DownloadStatus.DEFAULT

    fun isWait() = status == DownloadStatus.WAITING

    fun isDownloading() = status == DownloadStatus.DOWNLOADING

    fun isPause() = status == DownloadStatus.PAUSE

    fun isComplete() = status == DownloadStatus.COMPLETED

    fun isFail() = status == DownloadStatus.ERROR || status == DownloadStatus.CANCEL

    fun isWaitWifi() = status == DownloadStatus.WAITING_WIFI
}
