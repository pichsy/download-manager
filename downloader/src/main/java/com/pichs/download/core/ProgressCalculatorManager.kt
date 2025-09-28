package com.pichs.download.core

import com.pichs.download.utils.DownloadLog
import java.util.concurrent.ConcurrentHashMap

/**
 * ProgressCalculator管理器
 * 按文件ID管理ProgressCalculator实例，确保同一文件的所有分片共享同一个进度计算器
 * 解决多线程竞争导致的速度计算为0的问题
 */
object ProgressCalculatorManager {
    
    // 存储每个任务的ProgressCalculator实例
    private val calculatorMap = ConcurrentHashMap<String, ProgressCalculator>()
    
    /**
     * 获取指定任务的ProgressCalculator实例
     * 如果不存在则创建新的实例
     * 
     * @param taskId 任务ID
     * @return ProgressCalculator实例
     */
    fun getCalculator(taskId: String): ProgressCalculator {
        return calculatorMap.getOrPut(taskId) { 
            ProgressCalculator().also {
                DownloadLog.d("ProgressCalculatorManager", "为任务创建新的ProgressCalculator: $taskId")
            }
        }
    }
    
    /**
     * 清理指定任务的ProgressCalculator实例
     * 在任务完成或失败时调用
     * 
     * @param taskId 任务ID
     */
    fun clearCalculator(taskId: String) {
        calculatorMap.remove(taskId)?.let {
            it.clearTaskProgress(taskId)
            DownloadLog.d("ProgressCalculatorManager", "清理任务的ProgressCalculator: $taskId")
        }
    }
    
    /**
     * 清理所有ProgressCalculator实例
     * 在应用退出时调用
     */
    fun clearAllCalculators() {
        calculatorMap.values.forEach { it.clearAllProgress() }
        calculatorMap.clear()
        DownloadLog.d("ProgressCalculatorManager", "清理所有ProgressCalculator实例")
    }
    
    /**
     * 获取当前活跃的ProgressCalculator数量
     * 用于监控和调试
     */
    fun getActiveCalculatorCount(): Int {
        return calculatorMap.size
    }
}
