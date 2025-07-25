package com.pichs.download.dispatcher

import android.content.Context
import android.util.Log
import com.pichs.download.DownloadTask
import com.pichs.download.call.DownloadMultiCall
import com.pichs.download.callback.IDownloadListener
import com.pichs.download.entity.DownloadStatus
import com.pichs.download.utils.DownloadLog
import com.pichs.download.utils.FileUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.Path.Companion.toPath
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.ConcurrentSkipListMap
import java.util.Comparator
import java.util.concurrent.ConcurrentHashMap
import com.pichs.download.breakpoint.DownloadBreakPointData
import com.pichs.download.breakpoint.DownloadBreakPointManger

/*
 *  // 创建一个下载管理调度器，下载任务使用DownloadMultiCall去下载。
// 1、创建一个下载队列。
// 2、添加一个下载任务。
// 3、删除一个下载任务。
// 4、暂停一个下载任务。
// 5、恢复一个下载任务。
// 6、获取所有下载任务
// 7、取消所有下载任务
// 8、一个任务下载完成后，需要继续下一个任务。根据同时允许的任务数量进行决定是否继续下一个任务。
// 添加任务时需要添加回调监听DownloadListener，方便用户在任何地方进行监听。
 */

class DownloadQueueDispatcher : CoroutineScope by MainScope() {

    companion object {
        /**
         * * 最大同时下载任务数量
         * [maxTaskQueueSize] 最大同时下载任务数量, 默认1个，修改请在任务开始前修改。
         */
        @JvmField
        var maxTaskQueueSize = 1
    }

    private val mListenersMap = ConcurrentHashMap<String, MutableList<IDownloadListener>>()

    // 全局监听
    private val globalListeners = Collections.synchronizedList(mutableListOf<IDownloadListener>())

    // 所有有效任务队列。排除 completedTasks
    private val allActivatedDownloadCall = Collections.synchronizedList(mutableListOf<DownloadMultiCall>())
    private val runningTaskDownloadCall = Collections.synchronizedList(mutableListOf<DownloadMultiCall>())

    private val completedTasks = ConcurrentSkipListMap<Long, DownloadTask>(Comparator.reverseOrder())

    private val mutexLock = Mutex()

    private var mDownloadListenerWrap = DownloadTaskWrapper()


    /**
     * 初始化所有的任务
     * 放到对应的列表中
     */
    fun init(context: Context) {
        launch {
            val bpList = DownloadBreakPointManger.queryAll()
            bpList?.forEach { info ->
                val task = DownloadTask.create {
                    taskId = info.taskId
                    url = info.url
                    filePath = info.filePath
                    fileName = info.fileName
                    currentLength = info.currentLength
                    totalLength = info.totalLength
                    progress = info.progress
                    status = info.status
                    extra = info.extra
                    tag = info.tag.ifEmpty { null }
                }
                // 将任务添加到下载队列中。
                if (task.downloadInfo.status == DownloadStatus.COMPLETED) {
                    // 如果是已完成的任务，则添加到已完成任务列表中。
                    completedTasks[info.updateTime] = task
                } else if (task.downloadInfo.status == DownloadStatus.DOWNLOADING) {
                    // 否则添加到等待队列中。
                    val call = DownloadMultiCall(task).setListener(mDownloadListenerWrap)
                    allActivatedDownloadCall.add(call)
                    // 如果是正在下载的任务，则添加到正在下载的任务列表中。
                    runningTaskDownloadCall.add(call)
                    // 还要继续下载的任务。
                    DownloadLog.d { "DownloadQueueDispatcher init: add task ${task.getTaskId()} to runningTaskDownloadCall" }
                    // 调用开始下载
                    startTask(call)
                } else {
                    // 否则添加到等待队列中。
                    val call = DownloadMultiCall(task).setListener(mDownloadListenerWrap)
                    allActivatedDownloadCall.add(call)
                    DownloadLog.d { "DownloadQueueDispatcher init: add task ${task.getTaskId()} to allActivatedDownloadCall" }
                }
            }
            DownloadLog.d { "DownloadQueueDispatcher init completedTasks size: ${completedTasks.size}" }
        }
    }


    // 添加下载任务
    fun pushTask(task: DownloadTask) {
        launch {
            mutexLock.withLock {
                // 首先应该检测当前任务是否已经存在
                if (task.downloadInfo.taskId.isEmpty()) {
                    task.downloadInfo.taskId = task.getTaskId()
                }
                // 2. 加入内存队列
                task.downloadInfo?.status = DownloadStatus.WAITING
                if (!isTaskExists(task.getTaskId())) {
                    // 1. 立即持久化任务信息
                    val info = DownloadBreakPointData(
                        taskId = task.getTaskId(),
                        url = task.getUrl(),
                        filePath = task.getFilePath(),
                        fileName = task.getFileName(),
                        currentLength = 0,
                        totalLength = 0,
                        status = DownloadStatus.WAITING,
                        createTime = System.currentTimeMillis(),
                        updateTime = System.currentTimeMillis(),
                        extra = task.downloadInfo.extra,
                        tag = task.downloadInfo.tag ?: ""
                    )

                    DownloadBreakPointManger.upsert(info)
                    val call = DownloadMultiCall(task).setListener(mDownloadListenerWrap)
                    allActivatedDownloadCall.add(call)
                    // 当准备时。
                    mListenersMap[task.getTaskId()]?.let {
                        it.forEach { listener ->
                            listener.onPrepare(task)
                        }
                    }
                    // 全局监听
                    globalListeners.forEach { l ->
                        l.onPrepare(task)
                    }
                    startNextTaskIfAvailable()
                } else {
                    // todo 如果已经存在，则不需要再次添加
                    startNextTaskIfAvailable()
                }
            }
        }
    }

    /**
     * 准备开始任务：需要获取
     */
    private fun startTask(call: DownloadMultiCall) {
        // 开始任务
        call.startCall()
    }

    fun isTaskExists(taskId: String): Boolean {
        return completedTasks.filterValues { it.getTaskId() == taskId }.isNotEmpty() || allActivatedDownloadCall.any { it.task.getTaskId() == taskId }
    }

    /**
     * 获取任务对象。
     */
    fun getTask(taskId: String): DownloadTask? {
        return completedTasks.filterValues { it.getTaskId() == taskId }.values.firstOrNull()
            ?: allActivatedDownloadCall.find { it.task.getTaskId() == taskId }?.task
    }

    fun addGlobalListener(listener: IDownloadListener?) {
        if (listener == null) return
        globalListeners.add(listener)
    }

    fun removeGlobalListener(listener: IDownloadListener?) {
        if (listener == null) return
        globalListeners.remove(listener)
    }

    /**
     * 绑定任务监听
     * 建议放在 addTask 之前
     * 因为会回调添加时回调。
     */
    fun addListener(taskId: String?, listener: IDownloadListener?) {
        if (taskId.isNullOrEmpty()) return
        if (listener == null) return
        mListenersMap.getOrPut(taskId) { mutableListOf() }.add(listener)
    }

    /**
     * 解绑任务监听
     */
    fun removeListener(taskId: String?, listener: IDownloadListener? = null) {
        if (taskId.isNullOrEmpty()) return
        mListenersMap[taskId]?.let {
            if (listener != null) {
                it.remove(listener)
            }
        }
    }

    /**
     * 回调。
     */
    inner class DownloadTaskWrapper : DispatcherListener {
        override fun onStart(call: DownloadMultiCall, task: DownloadTask?, totalLength: Long) {
            if (task == null) return
            // 将任务添加到runningTask中
            launch {
                mutexLock.withLock {
                    // 将当前任务从正在下载的任务队列中移除
                    if (!runningTaskDownloadCall.contains(call)) {
                        runningTaskDownloadCall.add(call)
                    }

                    task.downloadInfo.totalLength = totalLength

                    mListenersMap[task.getTaskId()]?.forEach {
                        it.onStart(task, totalLength)
                    }
                    // 全局监听
                    globalListeners.forEach { l ->
                        l.onStart(task, totalLength)
                    }
                }
            }
        }

        override fun onPause(call: DownloadMultiCall, task: DownloadTask?) {
            launch {
                mutexLock.withLock {
                    // 将当前任务从正在下载的任务队列中移除
                    runningTaskDownloadCall.remove(call)
                    // 回调
                    mListenersMap[task?.getTaskId()]?.forEach {
                        it.onPause(task)
                    }
                    // 全局监听
                    globalListeners.forEach { l ->
                        l.onPause(task)
                    }
                    nextTaskIfAvailable()
                }
            }
        }

        override fun onProgress(call: DownloadMultiCall, task: DownloadTask?, currentLength: Long, totalLength: Long, progress: Int, speed: Long) {
            if (task == null) return
            task.downloadInfo.speed = speed
            task.downloadInfo.currentLength = currentLength
            task.downloadInfo.totalLength = totalLength
            task.downloadInfo.progress = progress

            mListenersMap[task.getTaskId()]?.forEach {
                it.onProgress(task, currentLength, totalLength, progress, speed)
            }
            DownloadLog.d { "DwonaloadDispatchers ==== 》》onProgress: globalListeners=${globalListeners.size}" }
            // 全局监听
            globalListeners.forEach { l ->
                DownloadLog.d { "DwonaloadDispatchers ==== 》globalListeners 》onProgress:" }
                l.onProgress(task, currentLength, totalLength, progress, speed)
            }
        }

        override fun onComplete(call: DownloadMultiCall, task: DownloadTask?) {
            if (task == null) return
            launch aa@{
                mutexLock.withLock {
                    // 任务完成,首先需要将任务添加到完成任务列表中
                    completedTasks[System.currentTimeMillis()] = task
                    // 将当前任务从正在下载的任务队列中移除
                    runningTaskDownloadCall.remove(call)
                    // 从总任务中也要移除
                    allActivatedDownloadCall.remove(call)

                    task.downloadInfo.progress = 100

                    // 回调
                    mListenersMap[task.getTaskId()]?.forEach {
                        it.onComplete(task)
                    }
                    // 全局监听
                    globalListeners.forEach { l ->
                        l.onComplete(task)
                    }
                    // 移除监听
                    mListenersMap.remove(task.getTaskId())
                    // 删除tmp文件
                    // FileUtils.deleteFile(task.getTmpFileAbsolutePath())
                    // 开始下一个任务
                    startNextTaskIfAvailable()
                }
            }
        }

        override fun onCancel(call: DownloadMultiCall, task: DownloadTask?) {
            if (task == null) return
            launch {
                mutexLock.withLock {
                    // 删除tmp文件
                    FileUtils.deleteFile(task.getTmpFileAbsolutePath())
                    // 将当前任务从正在下载的任务队列中移除
                    runningTaskDownloadCall.remove(call)
                    // 添加到总任务中
                    allActivatedDownloadCall.remove(call)
                    // 回调

                    task.downloadInfo.speed = 0L

                    task.downloadInfo.progress = 0

                    mListenersMap[task.getTaskId()]?.forEach {
                        it.onCancel(task)
                    }

                    // 全局监听
                    globalListeners.forEach { l ->
                        l.onCancel(task)
                    }

                    // 移除监听
                    mListenersMap.remove(task.getTaskId())
                    // todo 清除已下载的缓存文件。
                    // todo 理论上应该清除数据库缓存
                    nextTaskIfAvailable()
                }
            }
        }

        override fun onError(call: DownloadMultiCall, task: DownloadTask?, e: Throwable?) {
            if (task == null) return
            launch {
                mutexLock.withLock {
                    // 将当前任务从正在下载的任务队列中移除,只是出错了，还是可以重新下载的。
                    runningTaskDownloadCall.remove(call)
                    task.downloadInfo.speed = 0L
                    // 添加到总任务中
                    mListenersMap[task.getTaskId()]?.forEach {
                        it.onError(task, e)
                    }
                    // 全局监听
                    globalListeners.forEach { l ->
                        l.onError(task, e)
                    }
                    nextTaskIfAvailable()
                }
            }
        }
    }


    /**
     * 寻找下一个任务，如果可以的话。
     */
    private fun nextTaskIfAvailable() {
        startNextTaskIfAvailable()
    }


    // 如果有等待的任务，开始下一个
    private fun startNextTaskIfAvailable() {
        if (allActivatedDownloadCall.isEmpty()) return
        if (runningTaskDownloadCall.size >= maxTaskQueueSize) {
            return
        }
        val needAddSize = maxTaskQueueSize - runningTaskDownloadCall.size
        // 寻找所有等待列表中的等待状态的任务
        allActivatedDownloadCall.filter {
            it.task.downloadInfo?.status == DownloadStatus.WAITING ||
                    it.task.downloadInfo?.status == DownloadStatus.DEFAULT
        }.forEachIndexed { index, t ->
            if (index >= needAddSize) {
                return@forEachIndexed
            }
            startTask(t)
        }
    }

    // 删除下载任务
    fun cancelTask(taskId: String) {
        launch aa@{
            mutexLock.withLock {
                runningTaskDownloadCall.find { it.task.getTaskId() == taskId }?.let {
                    it.cancelCall()
                }
            }
        }
    }

    // 暂停下载任务
    fun pauseTask(taskId: String) {
        launch {
            mutexLock.withLock {
                runningTaskDownloadCall.find { it.task.getTaskId() == taskId }?.let {
                    it.pauseCall()
                    // 将数据添加进暂停任务列表。
                }
            }
        }
    }

    fun pauseAllTasks() {
        launch {
            mutexLock.withLock {
                allActivatedDownloadCall.forEach {
                    it.pauseCall()
                }
            }
        }
    }

    // 恢复下载任务
    fun resumeTask(taskId: String) {
        launch {
            mutexLock.withLock {
                allActivatedDownloadCall.find { it.task.getTaskId() == taskId }?.let {
                    startTask(it)
                }
            }
        }
    }

    /**
     * 恢复所有下载任务
     */
    fun resumeAllTasks() {
        launch {
            mutexLock.withLock {
                startNextTaskIfAvailable()
            }
        }
    }

    fun getRunningTasks(): MutableList<DownloadTask> {
        return runningTaskDownloadCall.map { it.task }.toMutableList()
    }

    // 获取所有下载任务
    fun getAllActivatedTasks(): MutableList<DownloadTask> {
        return allActivatedDownloadCall.map { it.task }.toMutableList()
    }

    // 获取最近完成的任务
    fun getCompletedTasks(): MutableList<DownloadTask> {
        return completedTasks.values.toMutableList()
    }

    // 取消所有下载任务
    fun cancelAllTasks() {
        allActivatedDownloadCall.forEach {
            it.cancelCall()
        }
    }

}
