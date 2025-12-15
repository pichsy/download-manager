package com.pichs.download.core

import com.pichs.download.utils.DownloadLog

/**
 * 流量会话管理器
 * 管理用户临时允许流量下载的状态
 */
object CellularSessionManager {
    
    private const val TAG = "CellularSessionManager"
    
    /** 当前是否处于"临时允许流量下载"状态 */
    @Volatile
    private var cellularDownloadAllowed = false
    
    /** 本次放行的起始时间 */
    private var sessionStartTime: Long = 0L
    
    /** 已确认过流量下载的任务 ID 集合（本次会话内） */
    private val confirmedTaskIds = mutableSetOf<String>()
    
    /**
     * 允许本次会话使用流量下载
     * 用户点击"使用流量下载"后调用
     */
    fun allowCellularDownload() {
        cellularDownloadAllowed = true
        sessionStartTime = System.currentTimeMillis()
        DownloadLog.d(TAG, "用户允许本次会话使用流量下载")
    }
    
    /**
     * 检查是否允许流量下载
     */
    fun isCellularDownloadAllowed(): Boolean = cellularDownloadAllowed
    
    /**
     * 标记任务已确认（用于智能提醒累计计算）
     */
    fun markTaskConfirmed(taskId: String) {
        confirmedTaskIds.add(taskId)
    }
    
    /**
     * 检查任务是否已确认
     */
    fun isTaskConfirmed(taskId: String): Boolean = confirmedTaskIds.contains(taskId)
    
    /**
     * 获取会话持续时间（毫秒）
     */
    fun getSessionDuration(): Long {
        return if (cellularDownloadAllowed) {
            System.currentTimeMillis() - sessionStartTime
        } else 0L
    }
    
    /**
     * 重置状态
     * 调用时机：WiFi 连接时 / App 重启时
     */
    fun reset() {
        cellularDownloadAllowed = false
        sessionStartTime = 0L
        confirmedTaskIds.clear()
        DownloadLog.d(TAG, "流量会话状态已重置")
    }
    
    /**
     * 获取当前状态摘要（用于调试）
     */
    fun getStatusSummary(): String {
        return "allowed=$cellularDownloadAllowed, " +
               "duration=${getSessionDuration()}ms, " +
               "confirmedTasks=${confirmedTaskIds.size}"
    }
}
