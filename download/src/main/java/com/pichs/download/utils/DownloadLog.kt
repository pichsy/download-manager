package com.pichs.download.utils

import android.util.Log

object DownloadLog {

    private var isDebug = false
    fun setDebug(isDebug: Boolean) {
        this.isDebug = isDebug
    }

    fun d(tag: String, message: () -> String) {
        if (isDebug) {
            d(tag) { message() }
        }
    }

    fun d(message: () -> String) {
        if (isDebug) {
            Log.d("Download", message())
        }
    }

    fun e(tag: String, e: Throwable? = null, message: () -> String) {
        if (isDebug) {
            e(tag, e) { message() }
        }
    }

    fun e(e: Throwable? = null, message: () -> String) {
        if (isDebug) {
            e("Download", e, message)
        }
    }

}