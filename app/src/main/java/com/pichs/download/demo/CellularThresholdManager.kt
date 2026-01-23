package com.pichs.download.demo

import com.pichs.download.core.DownloadManager

/**
 * 使用端流量阈值配置管理器
 * 用于批量下载前的智能拦截判断
 */
object CellularThresholdManager {
    /**
     * 是否启用智能提醒模式
     */
    fun smartModeEnabled(): Boolean {
        return DownloadManager.getNetworkConfig().cellularThreshold in 1 until Long.MAX_VALUE
    }

    /**
     * 获取阈值（字节）
     */
    val thresholdBytes: Long
        get() = DownloadManager.getNetworkConfig().cellularThreshold

    /**
     * 判断给定大小是否需要提醒
     * @param totalSizeBytes 总大小（字节）
     * @return true: 需要提醒，false: 不需要提醒（静默下载）
     */
    fun shouldPrompt(totalSizeBytes: Long): Boolean {
        if (!smartModeEnabled()) {
            // 智能模式未启用，使用框架的配置
            return true
        }
        // 智能模式：超过阈值才提醒
        return totalSizeBytes >= thresholdBytes
    }
}
