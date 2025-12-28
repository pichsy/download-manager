package com.pichs.shanhai.base.api.entity

import com.pichs.download.model.DownloadTask

data class UpdateAppInfo(
    var app_name: String? = null,
    var package_name: String? = null,
    var is_allow_notification: Int? = 0,
    var activity_name: String? = null,
    var version_name: String? = null,
    var version_code: Long? = 0L,
    var app_icon: String? = null,
    var developer: String? = null,
    var size: Long? = 0L,
    var apk_url: String? = null,
    // 0:所有，1:桌面应用 2：Dpc应用，3：除了1和2
    var type: Int? = 0,
    var bind_activity_name: String? = null,
    var home_activity_name: String? = null,
    var task: DownloadTask? = null
) 