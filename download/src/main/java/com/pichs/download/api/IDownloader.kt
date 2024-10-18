package com.pichs.download.api

import android.content.Context

interface IDownloader {

    fun init(context: Context)

    fun singleDownload()

    fun addDownloadTask()

}