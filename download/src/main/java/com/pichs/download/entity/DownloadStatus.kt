package com.pichs.download.entity

/**
 * 下载状态
 */

object DownloadStatus {

    const val DEFAULT = -1

    // 等待下载
    const val WAITING = 0

    // 下载中
    const val DOWNLOADING = 1

    // 已暂停
    const val PAUSE = 2

    // 已完成
    const val COMPLETED = 3

    // 下载失败
    const val ERROR = 4

    // 已取消
    const val CANCEL = 5

    const val WAITING_WIFI = 6
}
