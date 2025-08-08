package com.pichs.download.config

data class DownloadConfig(
    var maxConcurrentTasks: Int = 3,
    var connectTimeoutSec: Long = 60,
    var readTimeoutSec: Long = 60,
    var writeTimeoutSec: Long = 60,
    var allowMetered: Boolean = true,
)
