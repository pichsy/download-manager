package com.pichs.download.core

import com.pichs.download.config.DownloadConfig
import com.pichs.download.listener.DownloadListener
import com.pichs.download.listener.DownloadListenerManager
import com.pichs.download.listener.ProgressListener
import com.pichs.download.listener.StatusListener
import com.pichs.download.model.DownloadStatus
import com.pichs.download.model.DownloadTask
import com.pichs.download.store.InMemoryTaskStore
import com.pichs.download.utils.OkHttpHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

object DownloadManager {

    private val listenerManager = DownloadListenerManager()
    private val config = DownloadConfig()

    // 简单内存任务表，后续接入数据库
    // private val tasks = ConcurrentHashMap<String, DownloadTask>()

    // 下载引擎（简单实现）
    private val engine: DownloadEngine = SimpleDownloadEngine()
    @Volatile private var repository: com.pichs.download.store.TaskRepository? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 初始化：App 启动时调用，用于恢复历史任务
    fun init(context: android.content.Context) {
        if (repository != null) return
        repository = com.pichs.download.store.TaskRepository(context.applicationContext)
        // 同步恢复历史任务到内存；在 IO 线程阻塞一次，确保 App 冷启动可见
        runBlocking(Dispatchers.IO) {
            runCatching {
                val history = repository!!.getAll()
                val now = System.currentTimeMillis()
                history.forEach { t ->
                    val fixed = if (t.status == DownloadStatus.DOWNLOADING || t.status == DownloadStatus.PENDING) {
                        t.copy(status = DownloadStatus.PAUSED, speed = 0L, updateTime = now)
                    } else t
                    if (fixed !== t) {
                        // 异步持久化修正，避免阻塞 forEach
                        scope.launch { repository?.save(fixed) }
                    }
                    InMemoryTaskStore.put(fixed)
                }
            }
        }
    }

    // API 入口
    fun download(url: String): DownloadRequestBuilder = DownloadRequestBuilder().url(url)
    fun create(): DownloadRequestBuilder = DownloadRequestBuilder()

    // 任务管理（临时内存实现）
    fun getTask(taskId: String): DownloadTask? = InMemoryTaskStore.get(taskId)
    fun getAllTasks(): List<DownloadTask> = InMemoryTaskStore.getAll().sortedBy { it.createTime }
    fun getRunningTasks(): List<DownloadTask> = InMemoryTaskStore.getAll().filter { it.status == DownloadStatus.DOWNLOADING }

    private fun normalizeName(name: String): String = name.substringBeforeLast('.').lowercase()

    // 新增：按资源键查找现存任务（去重）
    fun findExistingTask(url: String, path: String, fileName: String): DownloadTask? {
        val norm = normalizeName(fileName)
        return InMemoryTaskStore.getAll().firstOrNull {
            it.url == url && it.filePath == path && normalizeName(it.fileName) == norm
        }
    }

    // 批量操作（占位）
    fun pauseAll(): DownloadManager = this
    fun resumeAll(): DownloadManager = this
    fun cancelAll(): DownloadManager = this

    // 配置
    fun config(block: DownloadConfig.() -> Unit) {
        block(config)
        OkHttpHelper.rebuildClient(config)
    }

    internal fun currentConfig(): DownloadConfig = config

    // 监听 API 透传
    fun addGlobalListener(listener: DownloadListener) = listenerManager.addGlobalListener(listener)
    fun removeGlobalListener(listener: DownloadListener) = listenerManager.removeGlobalListener(listener)

    fun addTaskListener(taskId: String, listener: DownloadListener) = listenerManager.addTaskListener(taskId, listener)
    fun removeTaskListener(taskId: String, listener: DownloadListener) = listenerManager.removeTaskListener(taskId, listener)

    fun addProgressListener(taskId: String, listener: ProgressListener) = listenerManager.addProgressListener(taskId, listener)
    fun removeProgressListener(taskId: String, listener: ProgressListener) = listenerManager.removeProgressListener(taskId, listener)

    fun addStatusListener(taskId: String, listener: StatusListener) = listenerManager.addStatusListener(taskId, listener)
    fun removeStatusListener(taskId: String, listener: StatusListener) = listenerManager.removeStatusListener(taskId, listener)

    fun addBatchListener(taskIds: List<String>, listener: DownloadListener) = listenerManager.addBatchListener(taskIds, listener)
    fun removeBatchListener(taskIds: List<String>, listener: DownloadListener) = listenerManager.removeBatchListener(taskIds, listener)

    // 内部事件：创建任务时注册并派发开始事件，并启动下载
    fun onTaskCreated(task: DownloadTask) {
    InMemoryTaskStore.put(task)
    repository?.let { repo -> scope.launch(Dispatchers.IO) { repo.save(task) } }
        listenerManager.notifyTaskStart(task)
        scope.launch { engine.start(task) }
    }

    // 供引擎使用的内部方法
    internal fun updateTaskInternal(task: DownloadTask) {
    InMemoryTaskStore.put(task)
    repository?.let { repo -> scope.launch(Dispatchers.IO) { repo.save(task) } }
    }

    internal fun listeners(): DownloadListenerManager = listenerManager

    // 引擎控制
    fun pause(taskId: String) = engine.pause(taskId)
    fun resume(taskId: String) = engine.resume(taskId)
    fun cancel(taskId: String) = engine.cancel(taskId)
}
