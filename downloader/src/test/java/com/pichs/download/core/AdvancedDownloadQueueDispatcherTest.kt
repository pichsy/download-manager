package com.pichs.download.core

import com.pichs.download.model.DownloadStatus
import com.pichs.download.model.DownloadTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedDownloadQueueDispatcherTest {

    private val dispatcher = AdvancedDownloadQueueDispatcher()

    private fun task(id: String, priority: Int = 1, createTime: Long = 1L) = DownloadTask(
        id = id,
        url = "https://example.com/$id",
        fileName = id,
        filePath = "/tmp",
        status = DownloadStatus.WAITING,
        progress = 0,
        totalSize = 100,
        currentSize = 0,
        speed = 0,
        priority = priority,
        createTime = createTime,
        updateTime = createTime
    )

    @Test
    fun `enqueue deduplicates same task id`() {
        val item = task("a")
        dispatcher.enqueue(item)
        dispatcher.enqueue(item.copy(updateTime = 2))

        assertEquals(1, dispatcher.getWaitingCount())
        assertEquals(item.id, dispatcher.dequeue()?.id)
    }

    @Test
    fun `dequeue prefers higher priority then earlier creation`() {
        dispatcher.setMaxConcurrentTasks(10)
        dispatcher.enqueue(task("low", priority = 1))
        dispatcher.enqueue(task("urgent-old", priority = 3, createTime = 1))
        dispatcher.enqueue(task("normal", priority = 2))
        dispatcher.enqueue(task("urgent-new", priority = 3, createTime = 2))

        assertEquals("urgent-old", dispatcher.dequeue()?.id)
        assertEquals("urgent-new", dispatcher.dequeue()?.id)
        assertEquals("normal", dispatcher.dequeue()?.id)
        assertEquals("low", dispatcher.dequeue()?.id)
        assertNull(dispatcher.dequeue())
    }

    @Test
    fun `remove clears waiting and running entries`() {
        val item = task("a")
        dispatcher.enqueue(item)
        dispatcher.dequeue()

        assertEquals(1, dispatcher.getRunningCount())

        dispatcher.remove(item.id)

        assertEquals(0, dispatcher.getRunningCount())
        assertEquals(0, dispatcher.getWaitingCount())
        assertTrue(dispatcher.getRunningTasks().isEmpty())
    }

    @Test
    fun `concurrency limit is coerced to at least one`() {
        dispatcher.setMaxConcurrentTasks(0)
        assertEquals(1, dispatcher.getCurrentConcurrencyLimit())

        dispatcher.setMaxConcurrentTasks(-3)
        assertEquals(1, dispatcher.getCurrentConcurrencyLimit())
    }

    @Test
    fun `requeue after removeFromRunning restores waiting entry`() {
        val item = task("a")
        dispatcher.enqueue(item)
        dispatcher.dequeue()
        dispatcher.removeFromRunning(item.id)

        dispatcher.requeue(item)

        assertEquals(1, dispatcher.getWaitingCount())
        assertEquals("a", dispatcher.dequeue()?.id)
    }
}
