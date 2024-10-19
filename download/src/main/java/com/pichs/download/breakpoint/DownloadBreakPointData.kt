package com.pichs.download.breakpoint

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.pichs.download.breakpoint.DownloadBreakPointManger.TABLE_NAME_BREAK_POINT
import kotlinx.parcelize.Parcelize


/**
 * 断点续传的缓存
 */
@Entity(tableName = TABLE_NAME_BREAK_POINT)
@Parcelize
data class DownloadBreakPointData(
    @PrimaryKey
    var taskId: String = "",
    var tag: String? = null,
    var url: String? = null,
    // 是否分片,<=1:不分片，n：分片数量
    var chunkCount: Int = 1,
    var filePath: String? = null,
    var fileName: String? = null,
    var currentLength: Long? = 0,
    var totalLength: Long? = 0,
    var fileMD5: String? = null,
    var progress: Int? = 0,
    // 0：等待下载，1：下载中，2：暂停，3：完成，4：失败, 5:等待wifi
    var status: Int = -1,
    var createTime: Long = 0L,
    var updateTime: Long = 0L
) : Parcelable {

    fun isWait() = status == 0

    fun isDownloading() = status == 1

    fun isPause() = status == 2

    fun isComplete() = status == 3

    fun isFail() = status == 4

    fun isWaitWifi() = status == 5

    fun isSuccess() = status == 3

}
