package com.pichs.download.core

import com.pichs.download.model.DownloadTask

/**
 * 下载决策回调接口
 * 使用端实现此接口以自定义 UI 展示
 */
interface DownloadDecisionCallback {
    
    /**
     * 请求用户确认是否使用流量下载（单任务/多任务）
     * 
     * @param pendingTasks 等待确认的任务列表
     * @param totalSize 待下载总大小（字节）
     * @param onConnectWifi 用户选择"连接 WiFi"时调用
     * @param onUseCellular 用户选择"使用流量下载"时调用
     */
    fun requestCellularConfirmation(
        pendingTasks: List<DownloadTask>,
        totalSize: Long,
        onConnectWifi: () -> Unit,
        onUseCellular: () -> Unit
    )
    
    /**
     * 请求用户确认批量下载（前置检查）
     * 用于 checkBatchDownloadPermission 方法
     * 
     * @param totalSize 待下载总大小（字节）
     * @param taskCount 任务数量
     * @param onConnectWifi 用户选择"连接 WiFi"时调用
     * @param onUseCellular 用户选择"使用流量下载"时调用
     */
    fun requestBatchCellularConfirmation(
        totalSize: Long,
        taskCount: Int,
        onConnectWifi: () -> Unit,
        onUseCellular: () -> Unit
    ) {
        // 默认实现：转发到单任务确认
        requestCellularConfirmation(emptyList(), totalSize, onConnectWifi, onUseCellular)
    }
    
    /**
     * 显示仅 WiFi 模式下的提示
     * 
     * @param task 被拦截的任务（批量检查时为 null）
     */
    fun showWifiOnlyHint(task: DownloadTask?)
    
    /**
     * WiFi 断开，任务被暂停时的提示（可选实现）
     * 
     * @param pausedCount 被暂停的任务数量
     */
    fun showWifiDisconnectedHint(pausedCount: Int) {}
}
