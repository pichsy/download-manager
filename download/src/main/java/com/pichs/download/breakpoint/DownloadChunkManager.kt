package com.pichs.download.breakpoint

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
        chunkDao().upsert(chunk)
    }

    /**
     * 查询chunk数据by taskId
     */
    suspend fun queryChunkByTaskId(taskId: String) = withContext(Dispatchers.IO) { chunkDao().queryByTaskId(taskId) }
    suspend fun deleteChunkByTaskId(taskId: String) = withContext(Dispatchers.IO) { chunkDao().deleteByTaskId(taskId) }
    suspend fun deleteAll() = withContext(Dispatchers.IO) { chunkDao().deleteAll() }
    suspend fun queryAll() = withContext(Dispatchers.IO) { chunkDao().queryAll() }

}
