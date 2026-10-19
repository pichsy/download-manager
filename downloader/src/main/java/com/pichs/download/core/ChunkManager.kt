package com.pichs.download.core

import com.pichs.download.model.ChunkStatus
import com.pichs.download.model.DownloadChunk
import com.pichs.download.store.db.DownloadChunkDao
import com.pichs.download.store.db.DownloadChunkEntity
import com.pichs.download.utils.DownloadLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

internal class ChunkManager(private val chunkDao: DownloadChunkDao) {
    
    // 创建分片
    suspend fun createChunks(taskId: String, totalSize: Long, chunkCount: Int): List<DownloadChunk> {
        val now = System.currentTimeMillis()
        val chunks: List<DownloadChunk> = if (totalSize <= 0) {
            // 未知长度或空文件：单分片。未知长度用 Long.MAX_VALUE 表示开放区间
            listOf(
                DownloadChunk(
                    taskId = taskId,
                    index = 0,
                    startByte = 0,
                    endByte = if (totalSize < 0) Long.MAX_VALUE else -1L,
                    downloaded = 0,
                    status = ChunkStatus.PENDING,
                    updateTime = now
                )
            )
        } else {
            val effectiveCount = chunkCount.coerceAtLeast(1)
                .coerceAtMost(totalSize.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            val chunkSize = totalSize / effectiveCount
            buildList {
                for (i in 0 until effectiveCount) {
                    val startByte = i * chunkSize
                    val endByte = if (i == effectiveCount - 1) totalSize - 1 else (i + 1) * chunkSize - 1
                    add(
                        DownloadChunk(
                            taskId = taskId,
                            index = i,
                            startByte = startByte,
                            endByte = endByte,
                            downloaded = 0,
                            status = ChunkStatus.PENDING,
                            updateTime = now
                        )
                    )
                }
            }
        }

        // 覆盖旧记录，避免分片数量变化后残留脏数据
        withContext(Dispatchers.IO) {
            chunkDao.deleteByTask(taskId)
            chunkDao.insertAll(chunks.map { DownloadChunkEntity.fromModel(it) })
        }

        return chunks
    }
    
    // 获取任务的所有分片
    suspend fun getChunks(taskId: String): List<DownloadChunk> {
        return withContext(Dispatchers.IO) {
            chunkDao.getByTask(taskId).map { it.toModel() }
        }
    }
    
    // 更新分片进度
    suspend fun updateChunkProgress(taskId: String, chunkIndex: Int, downloaded: Long, status: ChunkStatus) {
        withContext(Dispatchers.IO) {
            val chunk = chunkDao.getByTaskAndIndex(taskId, chunkIndex) ?: return@withContext

            val updatedChunk = chunk.copy(
                downloaded = downloaded,
                status = status,
                updateTime = System.currentTimeMillis()
            )
            chunkDao.update(updatedChunk)
        }
    }

    // 兼容旧调用
    suspend fun updateChunkProgress(
        task: com.pichs.download.model.DownloadTask,
        chunkIndex: Int,
        downloaded: Long,
        status: ChunkStatus
    ) {
        updateChunkProgress(task.id, chunkIndex, downloaded, status)
    }
    
    // 触发进度更新
    // 优化：直接传入 task 对象，避免 runBlocking 查询数据库
    suspend fun triggerProgressUpdate(task: com.pichs.download.model.DownloadTask) {
        try {
            val chunks = getChunks(task.id)
            val totalSize = task.totalSize
            
            if (totalSize > 0 && chunks.isNotEmpty()) {
                // 使用ProgressCalculatorManager获取该任务的专用计算器
                val calculator = ProgressCalculatorManager.getCalculator(task.id)
                val (shouldUpdate, updatedTask) = calculator.calculateProgress(
                    task = task,
                    chunks = chunks,
                    totalSize = totalSize,
                    minUpdateInterval = 200L // 分片完成时立即更新
                )
                
                if (shouldUpdate) {
                    DownloadManager.updateTaskInternal(updatedTask)
                    DownloadManager.emitProgress(updatedTask, updatedTask.progress, updatedTask.speed)
                    
                    DownloadLog.d("ChunkManager", 
                        "分片进度更新: ${task.id} - 进度: ${updatedTask.progress}%, 速度: ${updatedTask.speed}bytes/s, 已下载: ${updatedTask.currentSize}/${updatedTask.totalSize}")
                }
            }
        } catch (e: Exception) {
            DownloadLog.e("ChunkManager", "触发进度更新失败: ${task.id}", e)
        }
    }
    
    // 删除任务的所有分片
    suspend fun deleteChunks(taskId: String) {
        withContext(Dispatchers.IO) {
            chunkDao.deleteByTask(taskId)
        }
    }
    
    // 计算总进度
    fun calculateTotalProgress(chunks: List<DownloadChunk>, totalSize: Long): Int {
        if (totalSize <= 0) return 0
        val totalDownloaded = chunks.sumOf { it.downloaded }
        return ((totalDownloaded * 100) / totalSize).toInt()
    }
    
    // 计算总已下载大小
    fun calculateTotalDownloaded(chunks: List<DownloadChunk>): Long {
        return chunks.sumOf { it.downloaded }
    }
}
