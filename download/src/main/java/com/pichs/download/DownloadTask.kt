package com.pichs.download

import com.pichs.download.api.IDownloadTask
import com.pichs.download.callback.IDownloadListener
import com.pichs.download.entity.DownloadTaskInfo

class DownloadTask(override var downloadInfo: DownloadTaskInfo? = null) : IDownloadTask {

    override fun start(isClearOld: Boolean) {
    }

    override fun pause() {
    }

    override fun cancel() {
    }

    override fun addListener(listener: IDownloadListener) {
    }

    override fun removeListener(listener: IDownloadListener) {

    }

    public class Builder {
        fun build(block: (DownloadTaskInfo) -> Unit): DownloadTask {
            val info = DownloadTaskInfo()
            block.invoke(info)
            return DownloadTask(info)
        }
    }

}