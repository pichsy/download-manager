package com.pichs.download.core

import com.pichs.download.model.DownloadTask
import com.pichs.download.utils.FileUtils
import java.io.File

/**
 * 最终文件归属判断。
 * 删除任务时若同一路径仍被其他任务引用（历史数据/跨进程恢复），不能删掉对方的文件。
 */
internal object FileOwnership {

    fun targetPath(filePath: String, fileName: String): String =
        FileUtils.canonicalPath(File(filePath, FileUtils.sanitizeFileName(fileName)).path)

    fun isReferencedByOtherTask(
        taskId: String,
        filePath: String,
        fileName: String,
        tasks: List<DownloadTask>
    ): Boolean {
        val target = targetPath(filePath, fileName)
        return tasks.any { task ->
            task.id != taskId && targetPath(task.filePath, task.fileName) == target
        }
    }
}
