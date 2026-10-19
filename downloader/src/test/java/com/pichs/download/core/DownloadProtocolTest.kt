package com.pichs.download.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadProtocolTest {

    @Test
    fun `parses normal content range`() {
        val range = parseContentRange("bytes 0-1023/4096")
        assertEquals(ContentRange(0, 1023, 4096), range)
    }

    @Test
    fun `parses offset range`() {
        val range = parseContentRange("bytes 1024-2047/4096")
        assertEquals(ContentRange(1024, 2047, 4096), range)
    }

    @Test
    fun `parses unknown total`() {
        val range = parseContentRange("bytes 0-99/*")
        assertEquals(ContentRange(0, 99, -1), range)
    }

    @Test
    fun `parses case insensitive and trims whitespace`() {
        val range = parseContentRange("  BYTES 5-9/100  ")
        assertEquals(ContentRange(5, 9, 100), range)
    }

    @Test
    fun `rejects malformed content range`() {
        assertNull(parseContentRange(null))
        assertNull(parseContentRange(""))
        assertNull(parseContentRange("bytes 0-99"))
        assertNull(parseContentRange("items 0-99/100"))
    }

    @Test
    fun `rejects inverted content range`() {
        assertNull(parseContentRange("bytes 10-5/100"))
    }

    @Test
    fun `parses unsatisfied range total`() {
        assertEquals(4096L, parseUnsatisfiedRangeTotal("bytes */4096"))
        assertEquals(0L, parseUnsatisfiedRangeTotal("bytes */0"))
        assertEquals(12L, parseUnsatisfiedRangeTotal("  BYTES */12 "))
    }

    @Test
    fun `rejects malformed unsatisfied range`() {
        assertNull(parseUnsatisfiedRangeTotal(null))
        assertNull(parseUnsatisfiedRangeTotal(""))
        assertNull(parseUnsatisfiedRangeTotal("bytes */*"))
        assertNull(parseUnsatisfiedRangeTotal("bytes 0-10/100"))
        assertNull(parseUnsatisfiedRangeTotal("*/100"))
    }
}
