package com.pichs.download.model

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadTaskOrderTest {

    private fun task(id: String, priority: Int, createTime: Long, updateTime: Long) = DownloadTask(
        id = id,
        url = "https://example.com/$id",
        fileName = "$id.bin",
        filePath = "/tmp",
        status = DownloadStatus.DOWNLOADING,
        progress = 0,
        totalSize = 100,
        currentSize = 0,
        speed = 0,
        priority = priority,
        createTime = createTime,
        updateTime = updateTime
    )

    @Test
    fun `priority sorts by priority then create time descending`() {
        val tasks = listOf(
            task("low", priority = 1, createTime = 10, updateTime = 10),
            task("urgent-old", priority = 3, createTime = 5, updateTime = 5),
            task("urgent-new", priority = 3, createTime = 8, updateTime = 8)
        )

        val sorted = tasks.sortedWith(DownloadTaskOrder.PRIORITY).map { it.id }

        assertEquals(listOf("urgent-new", "urgent-old", "low"), sorted)
    }

    @Test
    fun `recent sorts by create time descending`() {
        val tasks = listOf(
            task("old", priority = 1, createTime = 5, updateTime = 20),
            task("new", priority = 1, createTime = 10, updateTime = 10)
        )

        val sorted = tasks.sortedWith(DownloadTaskOrder.RECENT).map { it.id }

        assertEquals(listOf("new", "old"), sorted)
    }

    @Test
    fun `updated sorts by update time descending`() {
        val tasks = listOf(
            task("old", priority = 1, createTime = 10, updateTime = 5),
            task("new", priority = 1, createTime = 5, updateTime = 10)
        )

        val sorted = tasks.sortedWith(DownloadTaskOrder.UPDATED).map { it.id }

        assertEquals(listOf("new", "old"), sorted)
    }
}
