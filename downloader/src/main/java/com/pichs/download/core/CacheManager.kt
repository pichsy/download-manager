package com.pichs.download.core

import com.pichs.download.model.DownloadTask
import com.pichs.download.store.TaskRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

internal class CacheManager(
    private val repository: TaskRepository
) {
    
    // 热数据缓存：最近访问的任务
    private val hotCache = ConcurrentHashMap<String, DownloadTask>()
    private val accessTimes = ConcurrentHashMap<String, Long>()
    
    // 冷热数据配置
    private val config = CacheConfig()
    
    // 缓存统计
    private val _cacheStats = MutableStateFlow(CacheStats())
    val cacheStats: StateFlow<CacheStats> = _cacheStats.asStateFlow()
    
    suspend fun getTask(taskId: String): DownloadTask? {
        // 先查热缓存
        hotCache[taskId]?.let { task ->
            accessTimes[taskId] = System.currentTimeMillis()
            return task
        }
        
        // 查数据库
        val task = repository.getById(taskId)
        if (task != null) {
            // 加入热缓存
            addToHotCache(task)
        }
        
        return task
    }
    
    suspend fun getAllTasks(): List<DownloadTask> {
        val allTasks = repository.getAll()
        
        // 更新热缓存
        allTasks.forEach { task ->
            if (hotCache.containsKey(task.id)) {
                hotCache[task.id] = task
            }
        }
        
        return allTasks
    }
    
    suspend fun putTask(task: DownloadTask) {
        // 更新数据库
        repository.save(task)
        
        // 更新热缓存
        addToHotCache(task)
    }
    
    suspend fun removeTask(taskId: String) {
        // 从热缓存移除
        hotCache.remove(taskId)
        accessTimes.remove(taskId)
        
        // 从数据库移除
        repository.delete(taskId)
    }
    
    private suspend fun addToHotCache(task: DownloadTask) {
        // 如果缓存已满，移除最久未访问的
        if (hotCache.size >= config.maxHotCacheSize) {
            evictOldestFromHotCache()
        }
        
        hotCache[task.id] = task
        accessTimes[task.id] = System.currentTimeMillis()
        
        updateCacheStats()
    }
    
    private fun evictOldestFromHotCache() {
        val oldestEntry = accessTimes.minByOrNull { it.value }
        oldestEntry?.let { (taskId, _) ->
            hotCache.remove(taskId)
            accessTimes.remove(taskId)
        }
    }
    
    suspend fun cleanupColdData() {
        val now = System.currentTimeMillis()
        val coldThreshold = now - config.coldDataThresholdMs
        
        // 移除长时间未访问的热数据
        val coldTasks = accessTimes.filter { it.value < coldThreshold }
        coldTasks.forEach { (taskId, _) ->
            hotCache.remove(taskId)
            accessTimes.remove(taskId)
        }
        
        updateCacheStats()
    }
    
    fun getHotTasks(): List<DownloadTask> {
        return hotCache.values.toList()
    }
    
    suspend fun getColdTasks(): List<DownloadTask> {
        val allTasks = repository.getAll()
        val hotTaskIds = hotCache.keys.toSet()
        return allTasks.filter { it.id !in hotTaskIds }
    }
    
    suspend fun clearCache() {
        hotCache.clear()
        accessTimes.clear()
        updateCacheStats()
    }
    
    private suspend fun updateCacheStats() {
        val stats = CacheStats(
            hotCacheSize = hotCache.size,
            totalTasks = repository.getAll().size,
            cacheHitRate = calculateHitRate()
        )
        _cacheStats.value = stats
    }
    
    private fun calculateHitRate(): Float {
        // 简化的命中率计算
        val totalAccess = accessTimes.size
        val hotAccess = hotCache.size
        return if (totalAccess > 0) hotAccess.toFloat() / totalAccess else 0f
    }
}

data class CacheConfig(
    val maxHotCacheSize: Int = 50,
    val coldDataThresholdMs: Long = 30 * 60 * 1000L // 30分钟
)

data class CacheStats(
    val hotCacheSize: Int = 0,
    val totalTasks: Int = 0,
    val cacheHitRate: Float = 0f
)
