package com.pichs.download.core

import android.os.Handler
import android.os.Looper
import com.pichs.download.model.DownloadStatus
import com.pichs.download.model.DownloadTask
import com.pichs.download.model.NetworkDownloadConfig
import com.pichs.download.model.PauseReason
import com.pichs.download.model.CellularThreshold
import com.pichs.download.utils.DownloadLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 网络自动恢复管理器
 * 统一处理所有网络状态变化的场景，由接入者调用
 * 
 * 设计原则：
 * - 接入者只需在 NetworkMonitor.onNetworkChanged 时调用 onNetworkRestored()
 * - 本类内部会自动判断当前网络类型（WiFi/流量/无网络）并做对应处理
 */
class NetworkAutoResumeManager(
    private val downloadManager: DownloadManager
) {
    
    companion object {
        private const val TAG = "NetworkAutoResumeManager"
    }
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    
    /**
     * 网络恢复/变化时调用
     * 统一入口，内部会判断当前网络类型并做对应处理：
     * - WiFi 可用：恢复所有网络相关暂停的任务
     * - 仅流量可用：恢复已确认流量的任务，未确认的触发确认流程
     * - 无网络：不做任何处理
     */
    fun onNetworkRestored() {
        scope.launch {
            try {
                val isWifi = downloadManager.isWifiAvailable()
                val isCellular = downloadManager.isCellularAvailable()
                
                DownloadLog.d(TAG, "网络状态变化，WiFi=$isWifi, Cellular=$isCellular")
                
                when {
                    isWifi -> handleWifiAvailable()
                    isCellular -> handleCellularOnly()
                    else -> handleNoNetwork()
                }
            } catch (e: Exception) {
                DownloadLog.e(TAG, "处理网络恢复时发生异常", e)
            }
        }
    }
    
    /**
     * WiFi 可用时的处理
     * 恢复所有因网络原因暂停的任务
     */
    private suspend fun handleWifiAvailable() {
        DownloadLog.d(TAG, "WiFi 可用，恢复所有网络相关暂停的任务")
        
        val allTasks = downloadManager.getAllTasks()
        val networkPausedTasks = allTasks.filter { task ->
            task.status == DownloadStatus.PAUSED &&
            (task.pauseReason == PauseReason.NETWORK_ERROR ||
             task.pauseReason == PauseReason.WIFI_UNAVAILABLE ||
             task.pauseReason == PauseReason.CELLULAR_PENDING)
        }.sortedWith(
            compareByDescending<DownloadTask> { it.priority }
                .thenBy { it.createTime }
        )
        
        if (networkPausedTasks.isEmpty()) {
            DownloadLog.d(TAG, "没有需要恢复的网络暂停任务")
            return
        }
        
        DownloadLog.d(TAG, "恢复 ${networkPausedTasks.size} 个任务")
        downloadManager.resumeTasks(networkPausedTasks)
    }
    
    /**
     * 仅流量可用时的处理
     * - 正在下载的未确认任务：主动暂停，防止消耗流量
     * - 已暂停的已确认任务：直接恢复
     * - 已暂停的未确认任务：保持暂停，不做处理
     */
    private suspend fun handleCellularOnly() {
        DownloadLog.d(TAG, "仅流量可用，检查待处理任务")
        
        val config = downloadManager.getNetworkConfig()
        
        // 如果是仅 WiFi 模式，暂停所有活跃任务
        if (config.wifiOnly) {
            DownloadLog.d(TAG, "仅 WiFi 模式，暂停所有活跃任务")
            val activeTasks = downloadManager.getAllTasks().filter { task ->
                task.status == DownloadStatus.DOWNLOADING ||
                task.status == DownloadStatus.WAITING ||
                task.status == DownloadStatus.PENDING
            }
            activeTasks.forEach { task ->
                downloadManager.pauseTask(task.id, PauseReason.WIFI_UNAVAILABLE)
            }
            return
        }
        
        val allTasks = downloadManager.getAllTasks()
        
        // 第一步：主动暂停所有正在下载的未确认流量任务（防止消耗流量）
        val activeTasks = allTasks.filter { task ->
            (task.status == DownloadStatus.DOWNLOADING ||
             task.status == DownloadStatus.WAITING ||
             task.status == DownloadStatus.PENDING) &&
            !task.cellularConfirmed
        }
        
        if (activeTasks.isNotEmpty()) {
            DownloadLog.d(TAG, "主动暂停 ${activeTasks.size} 个正在下载的未确认流量任务")
            activeTasks.forEach { task ->
                downloadManager.pauseTask(task.id, PauseReason.CELLULAR_PENDING)
                DownloadLog.d(TAG, "暂停任务: ${task.id} - ${task.fileName}")
            }
        }
        
        // 第二步：恢复已暂停的已确认流量任务
        val networkPausedTasks = allTasks.filter { task ->
            task.status == DownloadStatus.PAUSED &&
            (task.pauseReason == PauseReason.NETWORK_ERROR ||
             task.pauseReason == PauseReason.WIFI_UNAVAILABLE ||
             task.pauseReason == PauseReason.CELLULAR_PENDING)
        }.sortedWith(
            compareByDescending<DownloadTask> { it.priority }
                .thenBy { it.createTime }
        )
        
        if (networkPausedTasks.isEmpty()) {
            DownloadLog.d(TAG, "没有需要恢复的网络暂停任务")
            return
        }
        
        // 只恢复已确认流量的任务
        val confirmedTasks = networkPausedTasks.filter { it.cellularConfirmed }
        val unconfirmedCount = networkPausedTasks.count { !it.cellularConfirmed }
        
        DownloadLog.d(TAG, "已确认流量任务: ${confirmedTasks.size}, 未确认(保持暂停): $unconfirmedCount")
        
        // 恢复已确认流量的任务
        if (confirmedTasks.isNotEmpty()) {
            confirmedTasks.forEach { task ->
                downloadManager.resume(task.id)
                DownloadLog.d(TAG, "流量恢复任务(已确认): ${task.id} - ${task.fileName}")
            }
        }
    }
    
    /**
     * 无网络时的处理
     */
    private fun handleNoNetwork() {
        DownloadLog.d(TAG, "无网络，不做处理（下载中的任务会自然因网络失败而暂停）")
        // 不需要做任何处理，正在下载的任务会因为网络失败而自动暂停
    }
    

    
    // ==================== 兼容旧 API ====================
    
    /**
     * 检查是否有因网络异常暂停的任务
     * @return 因网络异常暂停的任务数量
     */
    suspend fun getNetworkPausedTaskCount(): Int {
        return try {
            val allTasks = downloadManager.getAllTasks()
            allTasks.count { task ->
                task.status == DownloadStatus.PAUSED && 
                (task.pauseReason == PauseReason.NETWORK_ERROR ||
                 task.pauseReason == PauseReason.WIFI_UNAVAILABLE ||
                 task.pauseReason == PauseReason.CELLULAR_PENDING)
            }
        } catch (e: Exception) {
            DownloadLog.e(TAG, "获取网络暂停任务数量失败", e)
            0
        }
    }
    
    /**
     * 获取因网络异常暂停的任务列表
     * @return 因网络异常暂停的任务列表
     */
    suspend fun getNetworkPausedTasks(): List<DownloadTask> {
        return try {
            val allTasks = downloadManager.getAllTasks()
            allTasks.filter { task ->
                task.status == DownloadStatus.PAUSED && 
                (task.pauseReason == PauseReason.NETWORK_ERROR ||
                 task.pauseReason == PauseReason.WIFI_UNAVAILABLE ||
                 task.pauseReason == PauseReason.CELLULAR_PENDING)
            }
        } catch (e: Exception) {
            DownloadLog.e(TAG, "获取网络暂停任务列表失败", e)
            emptyList()
        }
    }
    
    /**
     * 手动触发自动恢复检查
     * 用于测试或特殊情况
     */
    fun triggerAutoResumeCheck() {
        onNetworkRestored()
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        DownloadLog.d(TAG, "清理网络自动恢复管理器")
    }
}
