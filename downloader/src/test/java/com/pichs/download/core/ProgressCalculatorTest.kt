package com.pichs.download.core

import com.pichs.download.model.ChunkStatus
import com.pichs.download.model.DownloadChunk
import com.pichs.download.model.DownloadStatus
import com.pichs.download.model.DownloadTask
import org.junit.Assert.assertEquals
import org.junit.Test

class ProgressCalculatorTest {

    private val calculator = ProgressCalculator()

    private fun task() = DownloadTask(
        id = "task-1",
        url = "https://example.com/file.bin",
        fileName = "file.bin",
        filePath = "/tmp",
        status = DownloadStatus.DOWNLOADING,
        progress = 0,
        totalSize = 100,
        currentSize = 0,
        speed = 0,
        priority = 1,
        createTime = 1,
        updateTime = 1
    )

    private fun chunk(index: Int, start: Long, end: Long, downloaded: Long) = DownloadChunk(
        taskId = "task-1",
        index = index,
        startByte = start,
        endByte = end,
        downloaded = downloaded,
        status = if (downloaded == end - start + 1) ChunkStatus.COMPLETED else ChunkStatus.DOWNLOADING,
        updateTime = 1
    )

    @Test
    fun `marks completed only when bytes match total`() {
        val chunks = listOf(chunk(0, 0, 49, 50), chunk(1, 50, 99, 50))

        val result = calculator.getFinalProgress(task(), chunks, 100)

        assertEquals(DownloadStatus.COMPLETED, result.status)
        assertEquals(100, result.progress)
        assertEquals(100, result.currentSize)
        assertEquals(100, result.totalSize)
    }

    @Test
    fun `keeps failed when bytes are short`() {
        val chunks = listOf(chunk(0, 0, 49, 50), chunk(1, 50, 99, 20))

        val result = calculator.getFinalProgress(task(), chunks, 100)

        assertEquals(DownloadStatus.FAILED, result.status)
        assertEquals(70, result.progress)
        assertEquals(70, result.currentSize)
    }

    @Test
    fun `keeps failed when bytes exceed total`() {
        val chunks = listOf(chunk(0, 0, 99, 120))

        val result = calculator.getFinalProgress(task(), chunks, 100)

        assertEquals(DownloadStatus.FAILED, result.status)
        assertEquals(120, result.currentSize)
    }

    @Test
    fun `empty file completes with zero total`() {
        val result = calculator.getFinalProgress(task(), emptyList(), 0)

        assertEquals(DownloadStatus.COMPLETED, result.status)
        assertEquals(100, result.progress)
        assertEquals(0, result.currentSize)
    }

    @Test
    fun `unknown total never completes`() {
        val chunks = listOf(chunk(0, 0, 99, 80))

        val result = calculator.getFinalProgress(task(), chunks, -1)

        assertEquals(DownloadStatus.FAILED, result.status)
        assertEquals(0, result.progress)
    }
}
