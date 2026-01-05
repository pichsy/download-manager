package com.pichs.download.demo

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 全局下载 UI 事件管理器（单例）
 * 用于统一管理下载相关的 UI 事件（弹窗、Toast 等）
 * 在 MainActivity 中统一监听和处理
 */
object DownloadUiEventManager {

    private val _uiEvent = MutableSharedFlow<DownloadUiEvent>(extraBufferCapacity = 10)
    val uiEvent: SharedFlow<DownloadUiEvent> = _uiEvent.asSharedFlow()

    /**
     * 发送 UI 事件
     */
    fun emit(event: DownloadUiEvent) {
        _uiEvent.tryEmit(event)
    }

    /**
     * 发送 UI 事件（挂起函数）
     */
    suspend fun emitSuspend(event: DownloadUiEvent) {
        _uiEvent.emit(event)
    }
}

/**
 * 下载相关 UI 事件
 */
sealed class DownloadUiEvent {
    
    /** 显示 Toast */
    data class ShowToast(val message: String) : DownloadUiEvent()

    /**
     * 显示流量确认弹窗
     * @param totalSize 总大小
     * @param count 任务数量
     * @param onConfirm 用户确认后的回调
     */
    data class ShowCellularConfirmDialog(
        val totalSize: Long,
        val count: Int,
        val onConfirm: () -> Unit
    ) : DownloadUiEvent()

    /**
     * 显示仅 WiFi 模式弹窗
     * @param totalSize 总大小
     * @param count 任务数量
     * @param onConfirm 用户选择"等待WiFi下载"后的回调
     */
    data class ShowWifiOnlyDialog(
        val totalSize: Long,
        val count: Int,
        val onConfirm: () -> Unit
    ) : DownloadUiEvent()

    /**
     * 显示无网络弹窗
     * @param totalSize 总大小
     * @param count 任务数量
     * @param onConfirm 用户选择"等待网络下载"后的回调
     */
    data class ShowNoNetworkDialog(
        val totalSize: Long,
        val count: Int,
        val onConfirm: () -> Unit
    ) : DownloadUiEvent()
}
