package com.pichs.download

import android.content.Context
import com.pichs.download.breakpoint.DownloadBreakPointManger
import com.pichs.download.callback.IDownloadListener
import com.pichs.download.dispatcher.DownloadQueueDispatcher
import com.pichs.download.entity.DownloadTaskInfo
import com.pichs.download.utils.TaskIdUtils
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
                val task = DownloadTask.create {
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

    /**
     * 获取所有活跃任务，包含running task
     */
    fun getTaskAllActivatedList(): MutableList<DownloadTask> {
        return downloadQueueDispatcher.getAllActivatedTasks()
    }

    /**
     * 获取所有正在下载的任务
     */
    fun getAllRunningTaskList(): MutableList<DownloadTask> {
        return downloadQueueDispatcher.getRunningTasks()
    }

    fun getTaskById(taskId: String): DownloadTask? {
        return downloadQueueDispatcher.getTask(taskId)
    }

    /**
     * 绑定监听器
     * 用于其他位置监听
     */
    fun bindListener(taskId: String, listener: IDownloadListener?) {
        downloadQueueDispatcher.addListener(taskId, listener)
    }

    /**
     * 解绑监听器
     * 用于其他位置解绑监听
     */
    fun unbindListener(taskId: String, listener: IDownloadListener?) {
        downloadQueueDispatcher.removeListener(taskId, listener)
    }

    fun pauseTask(taskId: String) {
        downloadQueueDispatcher.pauseTask(taskId)
    }

    fun resumeTask(taskId: String) {
        downloadQueueDispatcher.resumeTask(taskId)
    }

    fun cancelTask(taskId: String) {
        downloadQueueDispatcher.cancelTask(taskId)
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


    class Builder {

        private var listener: IDownloadListener? = null
        val downloadTask: DownloadTask = DownloadTask(DownloadTaskInfo())

        fun setListener(listener: IDownloadListener?): Builder {
            this.listener = listener
            return this
        }

        fun setTaskId(taskId: String): Builder {
            downloadTask.downloadInfo?.taskId = taskId
            return this
        }

        fun setDownloadTaskInfo(block: DownloadTaskInfo.() -> Unit): Builder {
            downloadTask.downloadInfo?.let {
                block(it)
            }
            return this
        }

        fun build(): DownloadTask {
            downloadTask.apply {
                if (this.downloadInfo?.taskId.isNullOrEmpty()) {
                    this.downloadInfo?.taskId = TaskIdUtils.generateTaskId(
                        this.downloadInfo.url,
                        this.downloadInfo.filePath,
                        this.downloadInfo.fileName,
                        this.downloadInfo.tag ?: this.downloadInfo.fileMD5 ?: ""
                    )
                }
                addListener(listener)
            }
            return downloadTask
        }
    }

}