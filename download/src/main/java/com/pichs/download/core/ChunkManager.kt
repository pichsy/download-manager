package com.pichs.download.core

import com.pichs.download.model.ChunkStatus
import com.pichs.download.model.DownloadChunk
import com.pichs.download.store.db.DownloadChunkDao
import com.pichs.download.store.db.DownloadChunkEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class ChunkManager(private val chunkDao: DownloadChunkDao) {
    
    // 创建分片
    suspend fun createChunks(taskId: String, totalSize: Long, chunkCount: Int): List<DownloadChunk> {
        if (totalSize <= 0 || chunkCount <= 0) {
            // 单线程下载，创建一个完整分片
            return listOf(
                DownloadChunk(
                    taskId = taskId,
                    index = 0,
                    startByte = 0,
                    endByte = totalSize - 1,
                    downloaded = 0,
                    status = ChunkStatus.PENDING,
                    updateTime = System.currentTimeMillis()
                )
            )
        }
        
        val chunkSize = totalSize / chunkCount
        val chunks = mutableListOf<DownloadChunk>()
        
        for (i in 0 until chunkCount) {
            val startByte = i * chunkSize
            val endByte = if (i == chunkCount - 1) totalSize - 1 else (i + 1) * chunkSize - 1
            
            chunks.add(
                DownloadChunk(
                    taskId = taskId,
                    index = i,
                    startByte = startByte,
                    endByte = endByte,
                    downloaded = 0,
                    status = ChunkStatus.PENDING,
                    updateTime = System.currentTimeMillis()
                )
            )
        }
        
        // 保存到数据库
        withContext(Dispatchers.IO) {
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
            val chunks = chunkDao.getByTask(taskId)
            val chunk = chunks.find { it.index == chunkIndex } ?: return@withContext
            
            val updatedChunk = chunk.copy(
                downloaded = downloaded,
                status = status,
                updateTime = System.currentTimeMillis()
            )
            chunkDao.update(updatedChunk)
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
