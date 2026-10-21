package com.pichs.download.model

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadTaskChangeTest {

    private fun task() = DownloadTask(
        id = "task-1",
        url = "https://example.com/file.bin",
        fileName = "file.bin",
        filePath = "/tmp",
        status = DownloadStatus.DOWNLOADING,
        progress = 10,
        totalSize = 100,
        currentSize = 10,
        speed = 1024,
        priority = 1,
        createTime = 1,
        updateTime = 1
    )

    @Test
    fun `identical snapshot is none`() {
        val previous = task()

        assertEquals(DownloadTaskChange.NONE, task().diffFrom(previous))
    }

    @Test
    fun `progress fields only is progress`() {
        val previous = task()
        val current = previous.copy(
            progress = 20,
            currentSize = 20,
            speed = 2048,
            updateTime = 2
        )

        assertEquals(DownloadTaskChange.PROGRESS, current.diffFrom(previous))
    }

    @Test
    fun `status change is content`() {
        val previous = task()
        val current = previous.copy(status = DownloadStatus.PAUSED, speed = 0, updateTime = 2)

        assertEquals(DownloadTaskChange.CONTENT, current.diffFrom(previous))
    }

    @Test
    fun `metadata change is content`() {
        val previous = task()

        assertEquals(
            DownloadTaskChange.CONTENT,
            previous.copy(desc = "new description").diffFrom(previous)
        )
    }

    @Test
    fun `total size change is content`() {
        val previous = task()

        assertEquals(
            DownloadTaskChange.CONTENT,
            previous.copy(totalSize = 200).diffFrom(previous)
        )
    }

    @Test
    fun `different task id is content`() {
        val previous = task()

        assertEquals(
            DownloadTaskChange.CONTENT,
            previous.copy(id = "task-2").diffFrom(previous)
        )
    }
}
