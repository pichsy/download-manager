package com.pichs.download.core

import com.pichs.download.model.DownloadTask

/**
 * 网络场景枚举
 * 使用端根据此枚举决定如何展示 UI（Toast、弹窗、或不处理）
 */
enum class NetworkScenario {
    /** 正常流量确认（WiFi断开，有流量，需要用户确认是否使用流量） */
    CELLULAR_CONFIRMATION,
    
    /** 仅WiFi模式（用户设置了仅WiFi下载，但当前无WiFi） */
    WIFI_ONLY_MODE,
    
    /** 网络完全不可用（WiFi和流量都无） */
    NO_NETWORK
}

/**
 * 下载决策回调接口
 * 使用端实现此接口以自定义 UI 展示
 */
interface CheckAfterCallback {
    
    /**
     * 网络状态变化时的确认请求
     * 使用端根据 scenario 参数决定如何展示（Toast、弹窗、或不处理）
     * 
     * @param scenario 网络场景，使用端根据此判断如何展示 UI
     * @param pendingTasks 等待确认的任务列表
     * @param totalSize 待下载总大小（字节）
     * @param onConnectWifi 用户选择"连接 WiFi"时调用（可选）
     * @param onUseCellular 用户选择"使用流量下载"或"等待网络"时调用（可选）
     */
    fun requestConfirmation(
        scenario: NetworkScenario,
        pendingTasks: List<DownloadTask>,
        totalSize: Long,
        onConnectWifi: () -> Unit,
        onUseCellular: () -> Unit
    )
    
    /**
     * 请求用户确认是否使用流量下载（兼容旧接口）
     */
    fun requestCellularConfirmation(
        pendingTasks: List<DownloadTask>,
        totalSize: Long,
        onConnectWifi: () -> Unit,
        onUseCellular: () -> Unit
    ) {
        // 默认实现：转发到新接口
        requestConfirmation(NetworkScenario.CELLULAR_CONFIRMATION, pendingTasks, totalSize, onConnectWifi, onUseCellular)
    }
    
    /**
     * 请求用户确认批量下载（前置检查）
     */
    fun requestBatchCellularConfirmation(
        totalSize: Long,
        taskCount: Int,
        onConnectWifi: () -> Unit,
        onUseCellular: () -> Unit
    ) {
        requestCellularConfirmation(emptyList(), totalSize, onConnectWifi, onUseCellular)
    }
    
    /**
     * 显示仅 WiFi 模式下的提示
     */
    fun showWifiOnlyHint(task: DownloadTask?)
    
    /**
     * WiFi 断开，任务被暂停时的提示（可选实现）
     */
    fun showWifiDisconnectedHint(pausedCount: Int) {}
}

