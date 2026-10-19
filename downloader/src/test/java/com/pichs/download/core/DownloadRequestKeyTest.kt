package com.pichs.download.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DownloadRequestKeyTest {

    private fun key(url: String, path: String, fileName: String): DownloadRequestKey =
        DownloadRequestBuilder()
            .url(url)
            .path(path)
            .fileName(fileName)
            .resolveKey()

    @Test
    fun `same url path and extension share key`() {
        assertEquals(
            key("https://example.com/app.apk", "/tmp", "App.APK"),
            key("https://example.com/app.apk", "/tmp", "app.apk")
        )
    }

    @Test
    fun `different urls do not share key`() {
        assertNotEquals(
            key("https://example.com/a.apk", "/tmp", "app.apk"),
            key("https://example.com/b.apk", "/tmp", "app.apk")
        )
    }

    @Test
    fun `different paths do not share key`() {
        assertNotEquals(
            key("https://example.com/app.apk", "/tmp/a", "app.apk"),
            key("https://example.com/app.apk", "/tmp/b", "app.apk")
        )
    }

    @Test
    fun `different file names do not share key`() {
        assertNotEquals(
            key("https://example.com/app.apk", "/tmp", "one.apk"),
            key("https://example.com/app.apk", "/tmp", "two.apk")
        )
    }

    @Test
    fun `dot segments and trailing separators share key`() {
        val root = Files.createTempDirectory("request-key").toFile()
        try {
            File(root, "nested").mkdirs()
            val canonical = File(root, "nested").path
            val dotted = File(root, "other/../nested/").path

            assertEquals(
                key("https://example.com/app.apk", canonical, "app.apk"),
                key("https://example.com/app.apk", dotted, "app.apk")
            )
        } finally {
            root.deleteRecursively()
        }
    }
}
