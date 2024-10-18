package com.pichs.download.api

import com.pichs.download.callback.IDownloadListener
import com.pichs.download.entity.DownloadTaskInfo

interface IDownloadTask {

    var downloadInfo: DownloadTaskInfo?

    /**
     * @param isClearOld 是否清除旧文件
     */
    fun start(isClearOld: Boolean = false)

    /**
     * 暂停下载
     */
    fun pause()

    /**
     * 取消下载
     */
    fun cancel()


    fun addListener(listener: IDownloadListener)

    fun removeListener(listener: IDownloadListener)

}