package com.pichs.download.utils

object SpeedUtils {

    fun formatDownloadSpeed(speed: Long): String {
        return when {
            speed < 1024 -> "${speed}B/s"
            speed < 1024 * 1024 -> "${speed / 1024}KB/s"
            speed < 1024 * 1024 * 1024 -> "${speed / (1024 * 1024)}MB/s"
            else -> "${speed / (1024 * 1024 * 1024)}GB/s"
        }
    }


}