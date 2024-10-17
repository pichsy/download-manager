package com.pichs.download.demo

import com.liulishuo.okdownload.DownloadTask

data class DownloadItem(
    var url: String = "",
    var name: String = "",
    var size: Long = 0L,
    var icon: String? = null,
    var task: DownloadTask? = null
)


data class DownloadBean(
    var url: String = "",
    var name: String = "",
    var progress: Int = 0,
    var status: String = "Pending",
    var task: DownloadTask? = null
)