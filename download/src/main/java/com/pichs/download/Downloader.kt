package com.pichs.download

import android.annotation.SuppressLint
import android.content.Context
import com.pichs.download.api.IDownloader

@SuppressLint("StaticFieldLeak")
object Downloader : IDownloader {

    @SuppressLint("StaticFieldLeak")
    @Volatile
    private lateinit var mContext: Context

    override fun init(context: Context) {
        mContext = context.applicationContext
    }

    fun getContext(): Context {
        return mContext
    }

    override fun singleDownload() {

    }

    override fun addDownloadTask() {

    }

}