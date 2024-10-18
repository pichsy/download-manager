package com.pichs.download.entity

import java.util.concurrent.PriorityBlockingQueue

/**
 * 下载状态
 */

object DownloadStatus {

    // 等待下载
    const val WAIT = 0

    // 下载中
    const val DOWNLOADING = 1

    // 已暂停
    const val PAUSE = 2

    // 已完成
    const val COMPLETED = 3

    // 下载失败
    const val FAIL = 4
}
