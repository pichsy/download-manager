package com.pichs.download.config

import androidx.annotation.Keep

@Keep
data class DownloadConfig(
    var maxConcurrentTasks: Int = 1,
    var maxConcurrentOnWifi: Int = 1,
    var maxConcurrentOnCellular: Int = 1,
    var maxConcurrentOnLowBattery: Int = 1,
    var connectTimeoutSec: Long = 60,
    var readTimeoutSec: Long = 60,
    var writeTimeoutSec: Long = 60,
    var allowMetered: Boolean = true,
    // 回调线程语义：是否强制在主线程派发监听
    var callbackOnMain: Boolean = true,
    // 预留：校验相关（当前不启用实际校验逻辑）
    var checksum: Checksum? = null,
    // 预留：保留策略（用于自动清理已完成任务）
    var retention: Retention = Retention()
)
@Keep
data class Checksum(
    val type: Type,
    val value: String,
    val onFail: OnFail = OnFail.Ignore
) {
    enum class Type { MD5, SHA256 }
    enum class OnFail { Ignore, Fail, Retry }
}
@Keep
data class Retention(
    // 保留天数；<=0 表示不按时间自动清理
    val keepDays: Int = 0,
    // 仅保留最近 N 条完成任务；<=0 表示不限制
    val keepLatestCompleted: Int = 0
)
