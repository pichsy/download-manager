package com.pichs.download.call

import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

object ThreadPoolUtils {

    private val executor by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ThreadPoolExecutor(
            0,
            Integer.MAX_VALUE,
            60L,
            TimeUnit.SECONDS,
            SynchronousQueue(),
            threadFactory("download-manager-thread", true)
        )
    }

    private fun threadFactory(name: String?, daemon: Boolean): ThreadFactory {
        return ThreadFactory { runnable ->
            val result = Thread(runnable, name)
            result.isDaemon = daemon
            result
        }
    }


    fun execute(runnable: Runnable) {
        executor.execute(thread {
            try {
                runnable.run()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        })
    }

    fun shutdown() {
        executor.shutdown()
    }


}


