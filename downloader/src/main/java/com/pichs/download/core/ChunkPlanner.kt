package com.pichs.download.core

/**
 * 按文件大小规划分片数量。
 * 分片数随文件增大而增加，同时受最小分片大小和最大分片数约束，
 * 避免小文件开过多连接、大文件只开固定 4 个连接。
 */
internal object ChunkPlanner {

    const val DEFAULT_MIN_CHUNK_SIZE = 2L * 1024 * 1024
    const val DEFAULT_MAX_CHUNKS = 6
    private const val HARD_MAX_CHUNKS = 32

    fun calculateChunkCount(
        totalBytes: Long,
        minChunkSizeBytes: Long = DEFAULT_MIN_CHUNK_SIZE,
        maxChunks: Int = DEFAULT_MAX_CHUNKS
    ): Int {
        if (totalBytes <= 0) return 1
        val minChunkSize = minChunkSizeBytes.coerceAtLeast(1L)
        val maxCount = maxChunks.coerceIn(1, HARD_MAX_CHUNKS)
        // 文件不足两个最小分片时单线程，避免分片过小带来的额外请求开销
        if (totalBytes < minChunkSize * 2) return 1
        return (totalBytes / minChunkSize).toInt().coerceIn(1, maxCount)
    }
}
