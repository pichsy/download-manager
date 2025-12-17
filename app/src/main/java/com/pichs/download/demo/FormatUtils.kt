package com.pichs.download.demo

/**
 * 格式化工具类
 */
object FormatUtils {
    
    /**
     * 格式化文件大小
     * @param bytes 字节数
     * @return 格式化后的字符串，如 "12.34 MB"
     */
    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "--"
        val kb = 1024.0
        val mb = kb * 1024
        val gb = mb * 1024
        return when {
            bytes >= gb -> String.format("%.2f GB", bytes / gb)
            bytes >= mb -> String.format("%.2f MB", bytes / mb)
            bytes >= kb -> String.format("%.2f KB", bytes / kb)
            else -> "${bytes} B"
        }
    }
}
