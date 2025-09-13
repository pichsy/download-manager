package com.pichs.download.core

enum class DownloadPriority(val value: Int) {
    LOW(0),      // 后台下载
    NORMAL(1),   // 普通下载
    HIGH(2),     // 用户主动下载
    URGENT(3)    // 系统关键下载
}

data class TaskContext(
    val taskId: String,
    val priority: DownloadPriority,
    val isUserInitiated: Boolean = false,
    val isForeground: Boolean = false,
    val networkType: NetworkType = NetworkType.UNKNOWN,
    val isCharging: Boolean = false,
    val batteryLevel: Int = 100,
    val tag: String? = null,
    val createTime: Long = System.currentTimeMillis()
)

enum class NetworkType {
    WIFI,
    CELLULAR_4G,
    CELLULAR_5G,
    CELLULAR_3G,
    CELLULAR_2G,
    ETHERNET,
    UNKNOWN
}

data class SchedulerConfig(
    val maxConcurrentTasks: Int = 3,
    val maxConcurrentOnWifi: Int = 5,
    val maxConcurrentOnCellular: Int = 2,
    val maxConcurrentOnLowBattery: Int = 1,
    val enableBatteryOptimization: Boolean = true,
    val enableNetworkOptimization: Boolean = true,
    val enableTaskPreemption: Boolean = true,
    val urgentTaskTimeout: Long = 30_000L // 30秒
)
