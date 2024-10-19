package com.pichs.download.call

import java.util.concurrent.atomic.AtomicLong

class ProgressTracker(private val totalLength: Long) {
    private val downloadedBytes = AtomicLong(0)
    private val lastUpdateTime = AtomicLong(System.currentTimeMillis())
    private val lastBytesRead = AtomicLong(0)

    fun addProgress(bytes: Long): Long {
        return downloadedBytes.addAndGet(bytes).coerceAtMost(totalLength)
    }

    fun getProgress(): Int {
        return ((downloadedBytes.get().toDouble() / totalLength) * 100).toInt().coerceIn(0, 100)
    }

    fun calculateSpeed(): Long {
        val currentTime = System.currentTimeMillis()
        val currentBytes = downloadedBytes.get()
        val timeDiff = (currentTime - lastUpdateTime.get()) / 1000.0
        val bytesDiff = currentBytes - lastBytesRead.get()
        val speed = if (timeDiff > 0) (bytesDiff / timeDiff).toLong() else 0L

        lastUpdateTime.set(currentTime)
        lastBytesRead.set(currentBytes)

        return speed
    }

    fun getTotalDownloaded(): Long {
        return downloadedBytes.get()
    }

    fun shouldUpdateProgress(): Boolean {
        return System.currentTimeMillis() - lastUpdateTime.get() >= PROGRESS_UPDATE_INTERVAL
    }

    fun shouldUpdateCache(): Boolean {
        return System.currentTimeMillis() - lastUpdateTime.get() >= CACHE_UPDATE_INTERVAL
    }

    companion object {
        private const val PROGRESS_UPDATE_INTERVAL = 1000 // 1 second
        private const val CACHE_UPDATE_INTERVAL = 1500 // 1 second
    }
}