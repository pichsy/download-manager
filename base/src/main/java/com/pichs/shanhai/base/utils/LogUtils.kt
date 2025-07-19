package com.pichs.shanhai.base.utils

import android.util.Log
import com.pichs.xbase.utils.ThreadUtils

object LogUtils {

    const val LEVEL_DEBUG = 1
    const val LEVEL_INFO = 2
    const val LEVEL_WARN = 3
    const val LEVEL_ERROR = 4
    private var globalTag = "DPC管控"

    private const val maxLengthOfLines = 1024 * 3

    private var isLogEnable = true

    fun setLogEnable(enable: Boolean) {
        isLogEnable = enable
    }

    fun setGlobalTag(tag: String) {
        globalTag = tag
    }

    private fun log(logLevel: Int, tag: String, msg: String) {
        ThreadUtils.runOnIOThread {
            if (!isLogEnable) {
                return@runOnIOThread
            }
            if (msg.length <= maxLengthOfLines) {
                logOnly(logLevel, tag, msg)
            } else {
                logWhile(logLevel, tag, msg)
            }
        }
    }

    private fun logWhile(logLevel: Int, tag: String, msg: String) {
        var splitMsg = msg
        var index = 0
        while (splitMsg.isNotEmpty()) {
            if (splitMsg.length > maxLengthOfLines) {
                logOnly(logLevel, "${tag}=>《分割索引【${index}】》", splitMsg.substring(0, maxLengthOfLines))
                splitMsg = splitMsg.substring(maxLengthOfLines)
            } else {
                logOnly(logLevel, "${tag}=>《分割索引【${index}】》", splitMsg)
                splitMsg = ""
            }
            index++
        }
    }

    private fun logOnly(logLevel: Int, tag: String, msg: String) {
        when (logLevel) {
            LEVEL_DEBUG -> {
                Log.println(Log.DEBUG, globalTag, "$tag: $msg")
            }

            LEVEL_INFO -> {
                Log.println(Log.INFO, globalTag, "$tag: $msg")
            }

            LEVEL_WARN -> {
                Log.println(Log.WARN, globalTag, "$tag: $msg")
            }

            LEVEL_ERROR -> {
                Log.println(Log.ERROR, globalTag, "$tag: $msg")
            }
        }
    }

    fun d(msg: String) {
        log(LEVEL_DEBUG, globalTag, msg)
    }

    fun d(tag: String, msg: String) {
        log(LEVEL_DEBUG, tag, msg)
    }

    fun i(msg: String) {
        log(LEVEL_INFO, globalTag, msg)
    }

    fun i(tag: String, msg: String) {
        log(LEVEL_INFO, tag, msg)
    }

    fun w(msg: String) {
        log(LEVEL_WARN, globalTag, msg)
    }

    fun w(tag: String, msg: String) {
        log(LEVEL_WARN, tag, msg)
    }

    fun e(msg: String) {
        log(LEVEL_ERROR, globalTag, msg)
    }

    fun e(tag: String, msg: String, e: Throwable?) {
        log(LEVEL_ERROR, tag, "$msg,===>e:${Log.getStackTraceString(e)}")
    }

}