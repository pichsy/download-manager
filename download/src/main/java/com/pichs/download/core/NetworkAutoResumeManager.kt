package com.pichs.download.core

import com.pichs.download.model.DownloadStatus
import com.pichs.download.model.PauseReason
import com.pichs.download.utils.DownloadLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 网络自动恢复管理器
 * 提供网络恢复相关的API，由接入者调用
 * 不再内部监听网络状态，将网络监听责任交给接入者
 */
class NetworkAutoResumeManager(
    private val downloadManager: DownloadManager
) {
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    /**
     * 手动触发网络恢复检查
     * 由接入者在网络恢复时调用
     */
    fun onNetworkRestored() {
        DownloadLog.d("NetworkAutoResumeManager", "网络恢复，开始检查需要恢复的任务")
        autoResumeNetworkPausedTasks()
    }
    
    /**
     * 检查是否有因网络异常暂停的任务
     * @return 因网络异常暂停的任务数量
     */
    suspend fun getNetworkPausedTaskCount(): Int {
        return try {
            val allTasks = downloadManager.getAllTasks()
            allTasks.count { task ->
                task.status == DownloadStatus.PAUSED && 
                task.pauseReason == PauseReason.NETWORK_ERROR
            }
        } catch (e: Exception) {
            DownloadLog.e("NetworkAutoResumeManager", "获取网络暂停任务数量失败", e)
            0
        }
    }
    
    /**
     * 获取因网络异常暂停的任务列表
     * @return 因网络异常暂停的任务列表
     */
    suspend fun getNetworkPausedTasks(): List<com.pichs.download.model.DownloadTask> {
        return try {
            val allTasks = downloadManager.getAllTasks()
            allTasks.filter { task ->
                task.status == DownloadStatus.PAUSED && 
                task.pauseReason == PauseReason.NETWORK_ERROR
            }
        } catch (e: Exception) {
            DownloadLog.e("NetworkAutoResumeManager", "获取网络暂停任务列表失败", e)
            emptyList()
        }
    }
    
    /**
     * 自动恢复因网络异常暂停的任务
     */
    private fun autoResumeNetworkPausedTasks() {
        scope.launch {
            try {
                val allTasks = downloadManager.getAllTasks()
                DownloadLog.d("NetworkAutoResumeManager", "检查所有任务: ${allTasks.size} 个")
                
                val pausedTasks = allTasks.filter { it.status == DownloadStatus.PAUSED }
                DownloadLog.d("NetworkAutoResumeManager", "暂停的任务: ${pausedTasks.size} 个")
                
                pausedTasks.forEach { task ->
                    DownloadLog.d("NetworkAutoResumeManager", "暂停任务: ${task.id} - ${task.fileName}, 原因: ${task.pauseReason}")
                }
                
                val networkPausedTasks = allTasks.filter { task ->
                    task.status == DownloadStatus.PAUSED && 
                    task.pauseReason == PauseReason.NETWORK_ERROR
                }
                
                if (networkPausedTasks.isNotEmpty()) {
                    DownloadLog.d("NetworkAutoResumeManager", 
                        "网络恢复，自动恢复 ${networkPausedTasks.size} 个网络异常暂停的任务")
                    
                    networkPausedTasks.forEach { task ->
                        try {
                            downloadManager.resume(task.id)
                            DownloadLog.d("NetworkAutoResumeManager", 
                                "自动恢复任务成功: ${task.id} - ${task.fileName}")
                        } catch (e: Exception) {
                            DownloadLog.e("NetworkAutoResumeManager", 
                                "自动恢复任务失败: ${task.id}", e)
                        }
                    }
                } else {
                    DownloadLog.d("NetworkAutoResumeManager", "没有需要自动恢复的网络异常任务")
                }
            } catch (e: Exception) {
                DownloadLog.e("NetworkAutoResumeManager", "自动恢复任务时发生异常", e)
            }
        }
    }
    
    /**
     * 手动触发自动恢复检查
     * 用于测试或特殊情况
     */
    fun triggerAutoResumeCheck() {
        autoResumeNetworkPausedTasks()
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        DownloadLog.d("NetworkAutoResumeManager", "清理网络自动恢复管理器")
        // 不再需要清理网络监听器
    }
}
