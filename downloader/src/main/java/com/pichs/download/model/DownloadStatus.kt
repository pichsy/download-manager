package com.pichs.download.model

import androidx.annotation.Keep

@Keep
enum class DownloadStatus {
    WAITING,
    PENDING,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}
