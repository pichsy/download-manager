package com.pichs.download.core

import com.pichs.download.listener.DownloadListener
import com.pichs.download.listener.DownloadListenerManager

internal object DownloadEvents {
    val listeners = DownloadListenerManager()

    fun addGlobalListener(listener: DownloadListener) = listeners.addGlobalListener(listener)
    fun removeGlobalListener(listener: DownloadListener) = listeners.removeGlobalListener(listener)
}
