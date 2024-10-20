package com.pichs.download.breakpoint

import com.pichs.download.utils.DownloadLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


/**
 * 断点缓存
 */
object DownloadChunkManager {
    const val TABLE_NAME_CHUNK = "xp_download_chunk_info"
    private fun chunkDao() = DownloadBreakPointDatabase.database.chunkDao()

    /**
     *
     * 插入 分块数据
     */
    suspend fun upsert(chunk: DownloadChunk) = withContext(Dispatchers.IO) {
        try {
            chunkDao().upsert(chunk)
        } catch (e: Exception) {
            DownloadLog.e(e) { "Exception upsert:${chunk}" }
            -1L
        }
    }

    /**
     * 查询chunk数据by taskId
     */
    suspend fun queryChunkByTaskId(taskId: String) = withContext(Dispatchers.IO) {
        try {
            chunkDao().queryByTaskId(taskId)
        } catch (e: Exception) {
            DownloadLog.e(e) { "Exception queryChunkByTaskId:${taskId}" }
            mutableListOf()
        }
    }

    suspend fun deleteChunkByTaskId(taskId: String) = withContext(Dispatchers.IO) {
        try {
            chunkDao().deleteByTaskId(taskId)
        } catch (e: Exception) {
            DownloadLog.e(e) { "Exception deleteChunkByTaskId:${taskId}" }
        }
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        try {
            chunkDao().deleteAll()
        } catch (e: Exception) {
            DownloadLog.e(e) { "Exception deleteAll" }
        }
    }

    suspend fun queryAll() = withContext(Dispatchers.IO) {
        try {
            chunkDao().queryAll()
        } catch (e: Exception) {
            DownloadLog.e(e) { "Exception queryAll" }
            mutableListOf()
        }
    }

}
