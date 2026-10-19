package com.pichs.download.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileUtilsFilenameTest {

    @Test
    fun `keeps explicit filename with extension`() {
        assertEquals(
            "custom.bin",
            FileUtils.generateFilename("custom.bin", "https://example.com/a.mp4", "video/mp4")
        )
    }

    @Test
    fun `adds content type extension to explicit name without suffix`() {
        assertEquals(
            "custom.png",
            FileUtils.generateFilename("custom", "https://example.com/a", "image/png")
        )
    }

    @Test
    fun `extracts extension from url ignoring query`() {
        assertEquals(
            "video.mp4",
            FileUtils.generateFilename(null, "https://example.com/path/video.mp4?token=abc", null)
        )
    }

    @Test
    fun `falls back to content type when url has no extension`() {
        assertEquals(
            "file.mp4",
            FileUtils.generateFilename(null, "https://example.com/file", "video/mp4")
        )
    }

    @Test
    fun `sanitizes illegal characters`() {
        assertEquals(
            "a_b_c_.mp4",
            FileUtils.generateFilename("a:b*c?.mp4", "https://example.com/a.mp4", null)
        )
    }

    @Test
    fun `truncates very long names with stable hash suffix`() {
        val longName = "x".repeat(80) + ".mp4"

        val generated = FileUtils.generateFilename(null, "https://example.com/$longName", null)

        assertTrue(generated.length < longName.length)
        assertTrue(generated.endsWith(".mp4"))
        assertEquals(
            generated,
            FileUtils.generateFilename(null, "https://example.com/$longName", null)
        )
    }
}
