package com.pichs.download.core

import com.pichs.download.utils.FileUtils
import java.io.File

/**
 * 同一目录下的最终文件名占位表。
 * 多个任务并发解析文件名时，保证每个 (目录, 文件名) 只归属一个任务。
 */
internal class FinalNameRegistry {

    private val lock = Any()
    private val owners = HashMap<String, String>()

    fun release(taskId: String) {
        synchronized(lock) {
            owners.entries.removeAll { it.value == taskId }
        }
    }

    /**
     * @param otherNames 同一目录下其他未取消/未失败任务的文件名
     * @param hasOwnProgress 该任务是否已有断点文件
     */
    fun resolve(
        dir: File,
        baseName: String,
        taskId: String,
        otherNames: Set<String>,
        hasOwnProgress: Boolean
    ): String {
        return synchronized(lock) {
            val hasOwnResume = File(dir, "$baseName.$taskId.part").exists() ||
                    File(dir, "$baseName.part").exists()
            // 该任务自身已有进度且同名文件存在时视为自己的文件，避免续传时改名产生副本
            val ownFinal = hasOwnProgress && File(dir, baseName).exists()

            fun claim(name: String): Boolean {
                val path = FileUtils.canonicalPath(File(dir, name).path)
                val owner = owners[path]
                if (owner != null && owner != taskId) return false
                owners[path] = taskId
                return true
            }

            if (baseName !in otherNames &&
                (!File(dir, baseName).exists() || hasOwnResume || ownFinal) &&
                claim(baseName)
            ) {
                return@synchronized baseName
            }

            val dot = baseName.lastIndexOf('.')
            val stem = if (dot > 0) baseName.substring(0, dot) else baseName
            val ext = if (dot > 0) baseName.substring(dot) else ""
            var index = 1
            while (index < MAX_CANDIDATES) {
                val candidate = "$stem ($index)$ext"
                if (candidate !in otherNames && !File(dir, candidate).exists() && claim(candidate)) {
                    return@synchronized candidate
                }
                index++
            }

            val fallback = "$stem-$taskId$ext"
            owners[FileUtils.canonicalPath(File(dir, fallback).path)] = taskId
            fallback
        }
    }

    private companion object {
        const val MAX_CANDIDATES = 10_000
    }
}
