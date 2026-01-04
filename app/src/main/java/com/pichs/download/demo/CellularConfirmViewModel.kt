package com.pichs.download.demo

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 流量确认弹窗通信 ViewModel（单例）
 * 负责事件通信和待执行操作管理
 */
object CellularConfirmViewModel {
    
    // 确认事件
    private val _confirmEvent = MutableSharedFlow<CellularConfirmEvent>()
    val confirmEvent: SharedFlow<CellularConfirmEvent> = _confirmEvent.asSharedFlow()
    
    // 待执行的下载操作（全局共享）
    var pendingAction: (() -> Unit)? = null
    
    /**
     * 用户确认
     */
    suspend fun confirm() {
        pendingAction?.invoke()
        pendingAction = null
        _confirmEvent.emit(CellularConfirmEvent.Confirmed)
    }
    
    /**
     * 用户拒绝
     */
    suspend fun deny() {
        pendingAction = null
        _confirmEvent.emit(CellularConfirmEvent.Denied)
    }
    
    /**
     * 清理
     */
    fun clear() {
        pendingAction = null
    }
}

/**
 * 确认事件
 */
sealed class CellularConfirmEvent {
    object Confirmed : CellularConfirmEvent()
    object Denied : CellularConfirmEvent()
}
