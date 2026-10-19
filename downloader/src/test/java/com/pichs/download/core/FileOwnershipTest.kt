package com.pichs.download.core

import com.pichs.download.model.DownloadStatus
import com.pichs.download.model.DownloadTask
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FileOwnershipTest {

    private val root = Files.createTempDirectory("file-ownership").toFile()

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun task(id: String, fileName: String, path: String = root.path) = DownloadTask(
        id = id,
        url = "https://example.com/$fileName",
        fileName = fileName,
        filePath = path,
        status = DownloadStatus.COMPLETED,
        progress = 100,
        totalSize = 1,
        currentSize = 1,
        speed = 0,
        priority = 1,
        createTime = 1,
        updateTime = 1,
    )

    @Test
    fun `same final file is reported as shared across tasks`() {
        val tasks = listOf(task("a", "app.apk"), task("b", "app.apk"))

        assertTrue(FileOwnership.isReferencedByOtherTask("a", root.path, "app.apk", tasks))
    }

    @Test
    fun `renamed final file is not shared`() {
        val tasks = listOf(task("a", "app.apk"), task("b", "app (1).apk"))

        assertFalse(FileOwnership.isReferencedByOtherTask("a", root.path, "app.apk", tasks))
    }

    @Test
    fun `task itself does not count as another owner`() {
        val tasks = listOf(task("a", "app.apk"))

        assertFalse(FileOwnership.isReferencedByOtherTask("a", root.path, "app.apk", tasks))
    }

    @Test
    fun `dot segments resolve to the same owner`() {
        val dottedPath = File(root, "sub/..").path
        val tasks = listOf(task("a", "app.apk"), task("b", "app.apk", dottedPath))

        assertTrue(FileOwnership.isReferencedByOtherTask("a", root.path, "app.apk", tasks))
    }
}
