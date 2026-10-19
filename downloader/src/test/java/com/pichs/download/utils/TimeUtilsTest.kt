package com.pichs.download.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeUtilsTest {

    @Test
    fun `toMillis converts microsecond timestamps`() {
        assertEquals(1_700_000_000_123L, TimeUtils.toMillis(1_700_000_000_123_456L))
    }

    @Test
    fun `currentMicros keeps millisecond semantics`() {
        val before = System.currentTimeMillis()
        val millis = TimeUtils.toMillis(TimeUtils.currentMicros())
        val after = System.currentTimeMillis()

        assertTrue(millis in before..after)
    }

    @Test
    fun `currentMicros is strictly increasing within the same millisecond`() {
        val first = TimeUtils.currentMicros()
        val second = TimeUtils.currentMicros()

        assertTrue(second > first)
    }
}
