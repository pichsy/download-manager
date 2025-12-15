package com.pichs.download.utils

import com.pichs.download.model.PauseReason
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 异常分类工具类
 * 用于区分不同类型的异常，决定任务的状态转换
 */
object ErrorClassifier {

    /**
     * 判断是否为网络相关异常
     * 网络异常会导致任务暂停，网络恢复后自动恢复
     */
    fun isNetworkError(throwable: Throwable): Boolean {
        return when (throwable) {
            is ConnectException -> true
            is SocketTimeoutException -> true
            is UnknownHostException -> true
            is SSLException -> true
            is IOException -> {
                // 检查IOException的消息，判断是否为网络相关
                val message = throwable.message?.lowercase() ?: ""
                message.contains("network") ||
                        message.contains("connection") ||
                        message.contains("timeout") ||
                        message.contains("unreachable") ||
                        message.contains("refused")
            }

            else -> false
        }
    }

    /**
     * 判断是否为文件相关异常
     * 文件异常会导致任务失败，需要用户干预
     */
    fun isFileError(throwable: Throwable): Boolean {
        return when (throwable) {
            is IOException -> {
                val message = throwable.message?.lowercase() ?: ""
                message.contains("file") ||
                        message.contains("directory") ||
                        message.contains("permission") ||
                        message.contains("access") ||
                        message.contains("corrupt") ||
                        message.contains("invalid")
            }

            is SecurityException -> true
            else -> false
        }
    }

    /**
     * 判断是否为存储空间不足异常
     * 存储异常会导致任务暂停，空间释放后自动恢复
     */
    fun isStorageError(throwable: Throwable): Boolean {
        return when (throwable) {
            is IOException -> {
                val message = throwable.message?.lowercase() ?: ""
                message.contains("no space") ||
                        message.contains("disk full") ||
                        message.contains("storage") ||
                        message.contains("space")
            }

            else -> false
        }
    }

    /**
     * 根据异常类型获取对应的暂停原因
     * 如果异常不需要暂停，返回null
     */
    fun getPauseReason(throwable: Throwable): PauseReason? {
        return when {
            isStorageError(throwable) -> PauseReason.STORAGE_FULL
            isNetworkError(throwable) -> PauseReason.NETWORK_ERROR
            else -> null
        }
    }

    /**
     * 判断异常是否需要自动恢复
     * 只有特定类型的暂停原因才支持自动恢复
     */
    fun shouldAutoResume(pauseReason: PauseReason?): Boolean {
        return when (pauseReason) {
            PauseReason.NETWORK_ERROR -> true
            PauseReason.BATTERY_LOW -> true
            PauseReason.STORAGE_FULL -> true
            PauseReason.SYSTEM_RESOURCE_LOW -> true
            PauseReason.USER_MANUAL -> false
            else -> false
        }
    }
}
