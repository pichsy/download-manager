package com.pichs.download.core

import com.pichs.download.model.DownloadStatus
import com.pichs.download.model.DownloadTask
import com.pichs.download.model.PauseReason
import com.pichs.download.store.InMemoryTaskStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadStateMachineTest {

    private val taskId = "state-machine-test"

    private fun task(
        status: DownloadStatus,
        pauseReason: PauseReason? = null,
        updateTime: Long = 1L
    ) = DownloadTask(
        id = taskId,
        url = "https://example.com/file.bin",
        fileName = "file.bin",
        filePath = "/tmp",
        status = status,
        progress = 0,
        totalSize = 100,
        currentSize = 0,
        speed = 0,
        priority = 1,
        createTime = 1,
        updateTime = updateTime,
        pauseReason = pauseReason
    )

    @After
    fun tearDown() {
        InMemoryTaskStore.remove(taskId)
    }

    @Test
    fun `late active update cannot resurrect completed task`() {
        InMemoryTaskStore.put(task(DownloadStatus.COMPLETED))

        val applied = DownloadManager.updateTaskInternal(task(DownloadStatus.DOWNLOADING, updateTime = 2))

        assertFalse(applied)
        assertEquals(DownloadStatus.COMPLETED, InMemoryTaskStore.get(taskId)?.status)
    }

    @Test
    fun `cancelled task ignores progress updates`() {
        InMemoryTaskStore.put(task(DownloadStatus.CANCELLED))

        assertFalse(DownloadManager.updateTaskInternal(task(DownloadStatus.WAITING, updateTime = 2)))
        assertEquals(DownloadStatus.CANCELLED, InMemoryTaskStore.get(taskId)?.status)
    }

    @Test
    fun `user manual pause survives late progress update`() {
        InMemoryTaskStore.put(task(DownloadStatus.PAUSED, PauseReason.USER_MANUAL))

        assertFalse(DownloadManager.updateTaskInternal(task(DownloadStatus.DOWNLOADING, updateTime = 2)))
        assertEquals(DownloadStatus.PAUSED, InMemoryTaskStore.get(taskId)?.status)
        assertEquals(PauseReason.USER_MANUAL, InMemoryTaskStore.get(taskId)?.pauseReason)
    }

    @Test
    fun `explicit resume may override manual pause`() {
        InMemoryTaskStore.put(task(DownloadStatus.PAUSED, PauseReason.USER_MANUAL))

        val applied = DownloadManager.updateTaskInternal(
            task(DownloadStatus.WAITING, updateTime = 2),
            allowResume = true,
            allowManualResume = true
        )

        assertTrue(applied)
        assertEquals(DownloadStatus.WAITING, InMemoryTaskStore.get(taskId)?.status)
        assertNull(InMemoryTaskStore.get(taskId)?.pauseReason)
    }

    @Test
    fun `implicit resume cannot override manual pause`() {
        InMemoryTaskStore.put(task(DownloadStatus.PAUSED, PauseReason.USER_MANUAL))

        val applied = DownloadManager.updateTaskInternal(
            task(DownloadStatus.WAITING, updateTime = 2),
            allowResume = true
        )

        assertFalse(applied)
        assertEquals(DownloadStatus.PAUSED, InMemoryTaskStore.get(taskId)?.status)
        assertEquals(PauseReason.USER_MANUAL, InMemoryTaskStore.get(taskId)?.pauseReason)
    }

    @Test
    fun `late pause cannot overwrite completed task`() {
        InMemoryTaskStore.put(task(DownloadStatus.COMPLETED))

        val applied = DownloadManager.updateTaskInternal(
            task(DownloadStatus.PAUSED, PauseReason.USER_MANUAL, updateTime = 2)
        )

        assertFalse(applied)
        assertEquals(DownloadStatus.COMPLETED, InMemoryTaskStore.get(taskId)?.status)
    }

    @Test
    fun `late pause cannot overwrite failed task`() {
        InMemoryTaskStore.put(task(DownloadStatus.FAILED))

        val applied = DownloadManager.updateTaskInternal(
            task(DownloadStatus.PAUSED, PauseReason.NETWORK_ERROR, updateTime = 2)
        )

        assertFalse(applied)
        assertEquals(DownloadStatus.FAILED, InMemoryTaskStore.get(taskId)?.status)
    }

    @Test
    fun `failed task cannot be resumed implicitly`() {
        InMemoryTaskStore.put(task(DownloadStatus.FAILED))

        assertFalse(DownloadManager.updateTaskInternal(task(DownloadStatus.WAITING, updateTime = 2)))
        assertEquals(DownloadStatus.FAILED, InMemoryTaskStore.get(taskId)?.status)
    }

    @Test
    fun `failed task can be resumed explicitly`() {
        InMemoryTaskStore.put(task(DownloadStatus.FAILED))

        assertTrue(
            DownloadManager.updateTaskInternal(
                task(DownloadStatus.WAITING, updateTime = 2),
                allowResume = true
            )
        )
        assertEquals(DownloadStatus.WAITING, InMemoryTaskStore.get(taskId)?.status)
    }

    @Test
    fun `updates for deleted tasks are rejected`() {
        assertFalse(DownloadManager.updateTaskInternal(task(DownloadStatus.WAITING)))
        assertNull(InMemoryTaskStore.get(taskId))
    }
}
