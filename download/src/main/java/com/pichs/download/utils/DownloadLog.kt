package com.pichs.download.utils

import android.util.Log

object DownloadLog {

    private const val TAG = "Download"
    private var isDebug = true

    fun setDebug(isDebug: Boolean) {
        this.isDebug = isDebug
    }

    fun d(message: String) {
        if (isDebug) Log.d(TAG, message)
    }

    fun d(tag: String = TAG, message: String) {
        if (isDebug) Log.d(tag, message)
    }

    fun e(message: String, e: Throwable? = null) {
        if (isDebug) Log.e(TAG, message, e)
    }

    fun e(tag: String = TAG, message: String, e: Throwable? = null) {
        if (isDebug) Log.e(tag, message, e)
    }
}