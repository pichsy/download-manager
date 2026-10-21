package com.pichs.download.core

import com.pichs.download.model.DownloadStatus
import com.pichs.download.model.DownloadTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DownloadEventTrackerTest {

    private fun task(
        id: String = "task-1",
        status: DownloadStatus = DownloadStatus.DOWNLOADING,
        progress: Int = 0,
        filePath: String = "/tmp",
        fileName: String = "file.bin",
        updateTime: Long = 1
    ) = DownloadTask(
        id = id,
        url = "https://example.com/$id",
        fileName = fileName,
        filePath = filePath,
        status = status,
        progress = progress,
        totalSize = 100,
        currentSize = progress.toLong(),
        speed = 0,
        priority = 1,
        createTime = 1,
        updateTime = updateTime
    )

    @Test
    fun `baseline does not replay events`() {
        val file = File.createTempFile("download-event", ".bin")
        try {
            file.writeText("x")
            val completed = task(
                status = DownloadStatus.COMPLETED,
                progress = 100,
                filePath = requireNotNull(file.parent),
                fileName = file.name
            )
            val tracker = DownloadEventTracker(listOf(completed))

            assertTrue(tracker.update(listOf(completed)).isEmpty())
        } finally {
            file.delete()
        }
    }

    @Test
    fun `completion emits once with file`() {
        val file = File.createTempFile("download-event", ".bin")
        try {
            file.writeText("x")
            val downloading = task(filePath = requireNotNull(file.parent), fileName = file.name)
            val tracker = DownloadEventTracker(listOf(downloading))
            val completed = downloading.copy(status = DownloadStatus.COMPLETED, progress = 100)

            val event = tracker.update(listOf(completed)).single()

            assertTrue(event is DownloadEvent.Completed)
            assertEquals(completed.id, event.task.id)
            assertEquals(100, event.task.progress)
            assertEquals(file, (event as DownloadEvent.Completed).file)
            assertTrue(tracker.update(listOf(completed)).isEmpty())
        } finally {
            file.delete()
        }
    }

    @Test
    fun `completion without file does not emit`() {
        val downloading = task(filePath = "/tmp/not-exists", fileName = "missing.bin")
        val tracker = DownloadEventTracker(listOf(downloading))
        val completed = downloading.copy(status = DownloadStatus.COMPLETED, progress = 100)

        assertTrue(tracker.update(listOf(completed)).isEmpty())
    }

    @Test
    fun `paused then resumed emits once`() {
        val downloading = task()
        val tracker = DownloadEventTracker(listOf(downloading))
        val paused = downloading.copy(status = DownloadStatus.PAUSED, updateTime = 2)

        assertEquals(
            DownloadEvent.Paused(paused),
            tracker.update(listOf(paused)).single()
        )

        val waiting = paused.copy(status = DownloadStatus.WAITING, updateTime = 3)
        assertEquals(
            DownloadEvent.Resumed(waiting),
            tracker.update(listOf(waiting)).single()
        )

        val running = waiting.copy(status = DownloadStatus.DOWNLOADING, updateTime = 4)
        assertTrue(tracker.update(listOf(running)).isEmpty())
    }

    @Test
    fun `retry from failed emits resumed`() {
        val failed = task(status = DownloadStatus.FAILED)
        val tracker = DownloadEventTracker(listOf(failed))
        val running = failed.copy(status = DownloadStatus.DOWNLOADING, updateTime = 2)

        assertEquals(
            DownloadEvent.Resumed(running),
            tracker.update(listOf(running)).single()
        )
    }

    @Test
    fun `new task in pending is not a resume`() {
        val tracker = DownloadEventTracker(emptyList())

        assertTrue(tracker.update(listOf(task(status = DownloadStatus.PENDING))).isEmpty())
    }

    @Test
    fun `new paused task does not emit`() {
        val tracker = DownloadEventTracker(emptyList())

        assertTrue(tracker.update(listOf(task(status = DownloadStatus.PAUSED))).isEmpty())
    }

    @Test
    fun `failed and cancelled emit events`() {
        val downloading = task()
        val tracker = DownloadEventTracker(listOf(downloading))

        assertEquals(
            DownloadEvent.Failed(downloading.copy(status = DownloadStatus.FAILED)),
            tracker.update(listOf(downloading.copy(status = DownloadStatus.FAILED))).single()
        )

        assertEquals(
            DownloadEvent.Cancelled(downloading.copy(status = DownloadStatus.CANCELLED)),
            tracker.update(listOf(downloading.copy(status = DownloadStatus.CANCELLED))).single()
        )
    }

    @Test
    fun `deleted task clears baseline`() {
        val paused = task(status = DownloadStatus.PAUSED)
        val tracker = DownloadEventTracker(listOf(paused))

        assertTrue(tracker.update(emptyList()).isEmpty())

        val running = paused.copy(status = DownloadStatus.DOWNLOADING, updateTime = 2)
        assertTrue(tracker.update(listOf(running)).isEmpty())
    }
}
