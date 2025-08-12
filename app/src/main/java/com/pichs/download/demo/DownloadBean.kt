package com.pichs.download.demo

import com.pichs.download.model.DownloadTask

data class AppListBean(
    var appList: MutableList<DownloadItem>? = null,
)

data class DownloadItem(
    var url: String = "",
    var packageName: String = "",
    var name: String = "",
    var size: Long = 0L,
    var icon: String? = null,
    var versionCode: Long? = null,
    var versionName: String? = null,
    var task: DownloadTask? = null
)
