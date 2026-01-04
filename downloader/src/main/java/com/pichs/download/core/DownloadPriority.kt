package com.pichs.download.core

import androidx.annotation.Keep

@Keep
enum class DownloadPriority(val value: Int) {
    LOW(0),      // 后台下载
    NORMAL(1),   // 普通下载
    HIGH(2),     // 用户主动下载
    URGENT(3)    // 系统关键下载
}

@Keep
enum class NetworkType {
    WIFI,
    CELLULAR_4G,
    CELLULAR_5G,
    CELLULAR_3G,
    CELLULAR_2G,
    ETHERNET,
    UNKNOWN
}

@Keep
data class SchedulerConfig(
    val maxConcurrentTasks: Int = 3,
    val enableTaskPreemption: Boolean = true,
)
