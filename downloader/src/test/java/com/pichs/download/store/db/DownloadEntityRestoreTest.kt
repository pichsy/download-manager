package com.pichs.download.store.db

import com.pichs.download.model.DownloadStatus
import com.pichs.download.model.PauseReason
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 进程被杀后恢复的核心契约：持久化字段必须完整还原断点与暂停语义。
 */
class DownloadEntityRestoreTest {

    @Test
    fun `paused task restores offset and manual pause reason`() {
        val entity = DownloadEntity(
            id = "task",
            url = "https://example.com/app.apk",
            fileName = "app.apk",
            filePath = "/tmp",
            status = DownloadStatus.PAUSED,
            progress = 30,
            totalSize = 1000,
            currentSize = 300,
            speed = 0,
            priority = 2,
            createTime = 100,
            updateTime = 200,
            pauseReason = PauseReason.USER_MANUAL,
            cellularConfirmed = true,
        )

        val task = entity.toModel()

        assertEquals(DownloadStatus.PAUSED, task.status)
        assertEquals(PauseReason.USER_MANUAL, task.pauseReason)
        assertEquals(300L, task.currentSize)
        assertEquals(1000L, task.totalSize)
        assertEquals(2, task.priority)
        assertEquals(true, task.cellularConfirmed)
    }

    @Test
    fun `round trip keeps all download fields`() {
        val original = DownloadEntity(
            id = "task",
            url = "https://example.com/app.apk",
            fileName = "app.apk",
            filePath = "/tmp",
            status = DownloadStatus.DOWNLOADING,
            progress = 55,
            totalSize = 2000,
            currentSize = 1100,
            speed = 2048,
            priority = 3,
            createTime = 10,
            updateTime = 20,
            extras = "tag",
            desc = "demo",
            estimatedSize = 2000,
            cellularConfirmed = true,
        )

        assertEquals(original, DownloadEntity.fromModel(original.toModel()))
    }
}
