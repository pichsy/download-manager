package com.pichs.download.breakpoint


/**
 * 断点缓存
 */
object DownloadChunkManager {
    const val TABLE_NAME_CHUNK = "xp_download_chunk_info"
    private fun chunkDao() = DownloadBreakPointDatabase.database.chunkDao()
    /**
     * 插入 分块数据
     */
    fun upsert(chunk: DownloadChunk) = chunkDao().upsert(chunk)
    /**
     * 查询chunk数据by taskId
     */
    fun queryChunkByTaskId(taskId: String) = chunkDao().queryByTaskId(taskId)
    fun deleteChunkByTaskId(taskId: String) = chunkDao().deleteByTaskId(taskId)
    fun deleteAll() = chunkDao().deleteAll()
    fun queryAll() = chunkDao().queryAll()

}
