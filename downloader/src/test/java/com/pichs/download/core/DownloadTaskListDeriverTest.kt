package com.pichs.download.core

import com.pichs.download.model.DownloadStatus
import com.pichs.download.model.DownloadTask
import com.pichs.download.model.DownloadTaskOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadTaskListDeriverTest {

    private fun task(
        id: String,
        status: DownloadStatus,
        progress: Int = 0,
        priority: Int = 1,
        createTime: Long = 1,
        updateTime: Long = 1
    ) = DownloadTask(
        id = id,
        url = "https://example.com/$id",
        fileName = "$id.bin",
        filePath = "/tmp",
        status = status,
        progress = progress,
        totalSize = 100,
        currentSize = progress.toLong(),
        speed = 0,
        priority = priority,
        createTime = createTime,
        updateTime = updateTime
    )

    @Test
    fun `filters statuses`() {
        val deriver = DownloadTaskListDeriver(setOf(DownloadStatus.DOWNLOADING), null)
        val downloading = task("downloading", DownloadStatus.DOWNLOADING)
        val completed = task("completed", DownloadStatus.COMPLETED)

        assertEquals(listOf(downloading), deriver.derive(listOf(downloading, completed)))
    }

    @Test
    fun `ignores changes outside the filter`() {
        val deriver = DownloadTaskListDeriver(setOf(DownloadStatus.DOWNLOADING), null)
        val downloading = task("downloading", DownloadStatus.DOWNLOADING)
        val completed = task("completed", DownloadStatus.COMPLETED)

        assertEquals(listOf(downloading), deriver.derive(listOf(downloading, completed)))
        assertNull(
            deriver.derive(
                listOf(downloading, completed.copy(progress = 50, updateTime = 2))
            )
        )
    }

    @Test
    fun `reemits when a filtered task changes`() {
        val deriver = DownloadTaskListDeriver(setOf(DownloadStatus.DOWNLOADING), null)
        val completed = task("completed", DownloadStatus.COMPLETED)
        val downloading = task("downloading", DownloadStatus.DOWNLOADING)

        assertEquals(listOf(downloading), deriver.derive(listOf(downloading, completed)))

        val updated = downloading.copy(progress = 20, updateTime = 2)
        assertEquals(listOf(updated), deriver.derive(listOf(updated, completed)))
    }

    @Test
    fun `sorts after filtering`() {
        val deriver = DownloadTaskListDeriver(
            setOf(DownloadStatus.DOWNLOADING),
            DownloadTaskOrder.PRIORITY
        )
        val low = task("low", DownloadStatus.DOWNLOADING, priority = 1, createTime = 10)
        val urgent = task("urgent", DownloadStatus.DOWNLOADING, priority = 3, createTime = 5)
        val completed = task("completed", DownloadStatus.COMPLETED, priority = 0)

        assertEquals(
            listOf("urgent", "low"),
            deriver.derive(listOf(low, urgent, completed))?.map { it.id }
        )
    }
}
