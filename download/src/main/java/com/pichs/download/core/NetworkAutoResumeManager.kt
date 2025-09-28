package com.pichs.download.core

import android.content.Context
import com.pichs.download.model.DownloadStatus
import com.pichs.download.model.PauseReason
import com.pichs.download.utils.DownloadLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 网络自动恢复管理器
 * 监听网络状态变化，自动恢复因网络异常暂停的任务
 */
class NetworkAutoResumeManager(
    private val downloadManager: DownloadManager,
    private val context: Context? = null
) {
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var isNetworkAvailable = true
    private var networkStateListener: NetworkStateListener? = null
    
    /**
     * 初始化网络监听
     */
    fun init() {
        DownloadLog.d("NetworkAutoResumeManager", "初始化网络自动恢复管理器")
        
        // 初始化网络状态监听器
        context?.let { ctx ->
            networkStateListener = NetworkStateListener(ctx, this)
            networkStateListener?.startListening()
            
            // 检查当前网络状态
            isNetworkAvailable = networkStateListener?.isNetworkAvailable() ?: true
            DownloadLog.d("NetworkAutoResumeManager", "当前网络状态: $isNetworkAvailable")
        }
    }
    
    /**
     * 网络状态变化回调
     * @param isAvailable 网络是否可用
     */
    fun onNetworkStateChanged(isAvailable: Boolean) {
        val wasAvailable = isNetworkAvailable
        isNetworkAvailable = isAvailable
        
        DownloadLog.d("NetworkAutoResumeManager", "网络状态变化: $wasAvailable -> $isAvailable")
        
        // 网络从不可用变为可用时，自动恢复网络异常暂停的任务
        if (!wasAvailable && isAvailable) {
            autoResumeNetworkPausedTasks()
        }
    }

    internal fun isNetworkAvailable(): Boolean = isNetworkAvailable
    
    /**
     * 自动恢复因网络异常暂停的任务
     */
    private fun autoResumeNetworkPausedTasks() {
        scope.launch {
            try {
                val allTasks = downloadManager.getAllTasks()
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
        if (isNetworkAvailable) {
            autoResumeNetworkPausedTasks()
        }
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        DownloadLog.d("NetworkAutoResumeManager", "清理网络自动恢复管理器")
        networkStateListener?.stopListening()
        networkStateListener = null
        // scope.coroutineContext.cancel() // 注释掉，避免编译错误
    }
}
