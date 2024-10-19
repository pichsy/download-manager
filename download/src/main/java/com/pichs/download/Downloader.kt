package com.pichs.download

import android.content.Context
import com.pichs.download.breakpoint.DownloadBreakPointManger
import com.pichs.download.callback.IDownloadListener
import com.pichs.download.dispatcher.DownloadQueueDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class Downloader {

    companion object {
        private val _downloader by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { Downloader() }

        fun with(): Downloader {
            return _downloader
        }
    }

    private lateinit var mContext: Context

    /**
     * 下载任务调度器,对外开放对象。
     */
    val downloadQueueDispatcher by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { DownloadQueueDispatcher() }

    fun init(context: Context) {
        mContext = context.applicationContext
    }

    fun getContext(): Context {
        return mContext
    }

    /**
     * 查询断点信息，调出历史下载数据。
     */
    suspend fun queryAllTasksFromCache(): MutableList<DownloadTask> {
        return withContext(Dispatchers.IO) {
            val breakPointList = DownloadBreakPointManger.queryAll()
            val taskList = mutableListOf<DownloadTask>()
            breakPointList?.forEach { info ->
                val task = DownloadTask.build {
                    url = info.url
                    progress = info.progress
                    status = info.status
                    fileName = info.fileName
                    filePath = info.filePath
                    totalLength = info.totalLength
                    progress = info.progress
                    this.info = info
                    this.currentLength = info.currentLength
                }
                taskList.add(task)
            }
            return@withContext taskList
        }
    }

    fun getTaskList(): MutableList<DownloadTask> {
        return downloadQueueDispatcher.getAllTasks()
    }

    fun getTaskById(taskId: String): DownloadTask? {
        return downloadQueueDispatcher.getTask(taskId)
    }

    fun addListener(taskId: String, listener: IDownloadListener?) {
        downloadQueueDispatcher.addListener(taskId, listener)
    }

    fun removeListener(taskId: String) {
        downloadQueueDispatcher.removeListener(taskId)
    }

    fun addTask(task: DownloadTask) {
        downloadQueueDispatcher.addTask(task)
    }

    fun removeTask(taskId: String) {
        downloadQueueDispatcher.removeTask(taskId)
    }

    fun pauseTask(taskId: String) {
        downloadQueueDispatcher.pauseTask(taskId)
    }

    fun resumeTask(taskId: String) {
        downloadQueueDispatcher.resumeTask(taskId)
    }

    fun cancelTask(taskId: String) {
        downloadQueueDispatcher.removeTask(taskId)
    }

    fun cancelAllTask() {
        downloadQueueDispatcher.cancelAllTasks()
    }

    fun pauseAllTask() {
        downloadQueueDispatcher.pauseAllTasks()
    }

    fun resumeAllTask() {
        downloadQueueDispatcher.resumeAllTasks()
    }

    fun isTaskExists(taskId: String): Boolean {
        return downloadQueueDispatcher.isTaskExists(taskId)
    }

}