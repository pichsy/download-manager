package com.pichs.download.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ChunkPlannerTest {

    private val mb = 1024L * 1024

    @Test
    fun `unknown or empty size uses single chunk`() {
        assertEquals(1, ChunkPlanner.calculateChunkCount(-1))
        assertEquals(1, ChunkPlanner.calculateChunkCount(0))
    }

    @Test
    fun `small files stay single threaded`() {
        assertEquals(1, ChunkPlanner.calculateChunkCount(1 * mb))
        assertEquals(1, ChunkPlanner.calculateChunkCount(3 * mb))
        assertEquals(2, ChunkPlanner.calculateChunkCount(4 * mb))
    }

    @Test
    fun `chunk count grows with file size`() {
        assertEquals(5, ChunkPlanner.calculateChunkCount(10 * mb))
        assertEquals(6, ChunkPlanner.calculateChunkCount(50 * mb))
        assertEquals(6, ChunkPlanner.calculateChunkCount(500 * mb))
    }

    @Test
    fun `min chunk size and max chunks are configurable`() {
        assertEquals(4, ChunkPlanner.calculateChunkCount(64 * mb, minChunkSizeBytes = 16 * mb, maxChunks = 8))
        assertEquals(2, ChunkPlanner.calculateChunkCount(100 * mb, minChunkSizeBytes = 16 * mb, maxChunks = 2))
        assertEquals(1, ChunkPlanner.calculateChunkCount(20 * mb, minChunkSizeBytes = 16 * mb))
    }

    @Test
    fun `invalid planner values are clamped`() {
        assertEquals(1, ChunkPlanner.calculateChunkCount(100 * mb, minChunkSizeBytes = 0, maxChunks = 0))
        assertEquals(32, ChunkPlanner.calculateChunkCount(1000 * mb, minChunkSizeBytes = 1, maxChunks = 1000))
    }
}
