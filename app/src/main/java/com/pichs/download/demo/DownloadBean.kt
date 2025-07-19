package com.pichs.download.demo

import com.pichs.download.DownloadTask

data class AppListBean(
    var appList: MutableList<DownloadItem>? = null,
)

data class DownloadItem(
    var url: String = "",
    var packageName: String = "",
    var name: String = "",
    var size: Long = 0L,
    var icon: String? = null,
    var task: DownloadTask? = null
)


data class DownloadBean(
    var url: String = "",
    var name: String = "",
    var packageName: String = "",
    var progress: Int = 0,
    // -1未开始, 0：等待下载，1：下载中，2：暂停，3：完成，4：失败, 5:等待wifi
    var status: Int = -1,
    var task: DownloadTask? = null
)