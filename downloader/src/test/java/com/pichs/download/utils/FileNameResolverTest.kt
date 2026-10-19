package com.pichs.download.utils

import com.pichs.download.config.FileNameResolver
import org.junit.Assert.assertEquals
import org.junit.Test

class FileNameResolverTest {

    @Test
    fun `custom resolver overrides explicit and url names`() {
        val resolver = FileNameResolver { _, _ -> "resolved.bin" }

        assertEquals(
            "resolved.bin",
            FileUtils.resolveFileName("https://example.com/a.mp4", "explicit.mp4", resolver)
        )
    }

    @Test
    fun `null or blank custom name falls back to explicit name`() {
        val nullResolver = FileNameResolver { _, _ -> null }
        val blankResolver = FileNameResolver { _, _ -> "  " }

        assertEquals(
            "explicit.mp4",
            FileUtils.resolveFileName("https://example.com/a.mp4", "explicit.mp4", nullResolver)
        )
        assertEquals(
            "explicit.mp4",
            FileUtils.resolveFileName("https://example.com/a.mp4", "explicit.mp4", blankResolver)
        )
    }

    @Test
    fun `falls back to url last segment without query`() {
        assertEquals(
            "video.mp4",
            FileUtils.resolveFileName(
                "https://example.com/path/video.mp4?token=abc",
                null,
                null
            )
        )
    }

    @Test
    fun `resolver exception falls back to default rules`() {
        val resolver = FileNameResolver { _, _ -> throw IllegalStateException("boom") }

        assertEquals(
            "fallback.mp4",
            FileUtils.resolveFileName("https://example.com/a.mp4", "fallback.mp4", resolver)
        )
    }

    @Test
    fun `custom names are sanitized`() {
        val resolver = FileNameResolver { _, _ -> "../evil:name?.apk" }

        assertEquals(
            ".._evil_name_.apk",
            FileUtils.resolveFileName("https://example.com/a.apk", null, resolver)
        )
    }
}
