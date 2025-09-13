package com.pichs.download.core

import com.pichs.download.config.DownloadConfig
import com.pichs.download.config.Retention
// 旧的监听器已移除，现在使用Flow监听器
import com.pichs.download.model.DownloadStatus
import com.pichs.download.model.DownloadTask
import com.pichs.download.store.InMemoryTaskStore
import com.pichs.download.store.TaskRepository
import com.pichs.download.utils.OkHttpHelper
import com.pichs.download.store.db.DownloadDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

object DownloadManager {

    private val config = DownloadConfig()
    
    // Flow监听器（新方式）
    val flowListener = FlowDownloadListener()

    // 简单内存任务表，后续接入数据库
    // private val tasks = ConcurrentHashMap<String, DownloadTask>()

    // 下载引擎（多线程分片实现）
    private val engine: DownloadEngine = MultiThreadDownloadEngine()
    private val dispatcher = AdvancedDownloadQueueDispatcher()
    private val scheduler: DownloadScheduler? = null
    @Volatile private var repository: TaskRepository? = null
    @Volatile private var chunkManager: ChunkManager? = null
    @Volatile private var atomicCommitManager: AtomicCommitManager? = null
    @Volatile private var storageManager: StorageManager? = null
    @Volatile private var cacheManager: CacheManager? = null
    @Volatile private var retentionManager: RetentionManager? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dispatcherIO: CoroutineDispatcher = Dispatchers.IO

    // Flow: 任务全量状态
    private val _tasksState = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasksState = _tasksState.asStateFlow()

    // Flow: 单任务进度
    private val taskProgressFlows = java.util.concurrent.ConcurrentHashMap<String, MutableSharedFlow<Pair<Int, Long>>>()

    // 每个任务的自定义请求头
    private val taskHeaders = java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>()

    internal fun setTaskHeaders(taskId: String, headers: Map<String, String>) {
        taskHeaders[taskId] = headers
    }

    internal fun getTaskHeaders(taskId: String): Map<String, String> = taskHeaders[taskId] ?: emptyMap()
    
    internal fun getChunkManager(): ChunkManager? = chunkManager

    // 初始化：App 启动时调用，用于恢复历史任务
    fun init(context: android.content.Context) {
        if (repository != null) return
        repository = TaskRepository(context.applicationContext)
        chunkManager = ChunkManager(DownloadDatabase.get(context.applicationContext).chunkDao())
        atomicCommitManager = AtomicCommitManager(repository!!)
        storageManager = StorageManager(context.applicationContext, repository!!)
        cacheManager = CacheManager(repository!!)
        retentionManager = RetentionManager(repository!!, storageManager!!)
        
        // 启动存储监控
        storageManager!!.startMonitoring()
        
        // 启动高级调度器
        val scheduler = DownloadScheduler(context.applicationContext, engine, dispatcher)
        scheduler.start()
        // 同步恢复历史任务到内存；在 IO 线程阻塞一次，确保 App 冷启动可见
        runBlocking(Dispatchers.IO) {
            runCatching {
                val history = repository!!.getAll()
                val now = System.currentTimeMillis()
                history.forEach { t ->
                    // 若标记已完成但文件不存在，清理脏记录
                    val fileGone = (t.status == DownloadStatus.COMPLETED)
                            && !java.io.File(t.filePath, t.fileName).exists()
                    if (fileGone) {
                        scope.launch { repository?.delete(t.id) }
                        return@forEach
                    }
                    val fixed = if (t.status == DownloadStatus.DOWNLOADING || t.status == DownloadStatus.PENDING) {
                        t.copy(status = DownloadStatus.PAUSED, speed = 0L, updateTime = now)
                    } else t
                    if (fixed !== t) {
                        // 异步持久化修正，避免阻塞 forEach
                        scope.launch { repository?.save(fixed) }
                    }
                    InMemoryTaskStore.put(fixed)
                }
                // 初始化后同步一次 StateFlow
                _tasksState.value = InMemoryTaskStore.getAll().sortedBy { it.createTime }
                // 自动清理：按保留策略执行（若配置开启）
                config.retention.takeIf { it.keepDays > 0 || it.keepLatestCompleted > 0 }?.let { retention ->
                    scope.launch(dispatcherIO) { cleanCompletedInternal(deleteFiles = false, retention = retention) }
                }
            }
        }
    }

    // API 入口
    fun download(url: String): DownloadRequestBuilder = DownloadRequestBuilder().url(url)
    fun create(): DownloadRequestBuilder = DownloadRequestBuilder()

    // 任务管理（使用缓存管理器）
    suspend fun getTask(taskId: String): DownloadTask? = cacheManager?.getTask(taskId)
    suspend fun getAllTasks(): List<DownloadTask> = cacheManager?.getAllTasks()?.sortedBy { it.createTime } ?: emptyList()
    suspend fun getRunningTasks(): List<DownloadTask> = cacheManager?.getAllTasks()?.filter { it.status == DownloadStatus.DOWNLOADING } ?: emptyList()
    // 队列可视化（可选）：仅用于调试或UI展示
    fun getWaitingQueueTasks(): List<DownloadTask> = dispatcher.getWaitingTasks()
    fun getRunningQueueTasks(): List<DownloadTask> = dispatcher.getRunningTasks()
    fun getUrgentTasks(): List<DownloadTask> = dispatcher.getUrgentTasks()
    fun getNormalTasks(): List<DownloadTask> = dispatcher.getNormalTasks()
    fun getBackgroundTasks(): List<DownloadTask> = dispatcher.getBackgroundTasks()

    private fun normalizeName(name: String): String = name.substringBeforeLast('.').lowercase()

    // 新增：按资源键查找现存任务（去重）
    fun findExistingTask(url: String, path: String, fileName: String): DownloadTask? {
        val norm = normalizeName(fileName)
        return InMemoryTaskStore.getAll().firstOrNull {
            it.url == url && it.filePath == path && normalizeName(it.fileName) == norm
        }
    }
    
    // 新增：按优先级创建任务
    fun downloadWithPriority(url: String, priority: DownloadPriority = DownloadPriority.NORMAL): DownloadRequestBuilder {
        return DownloadRequestBuilder().url(url).priority(priority.value)
    }
    
    // 新增：创建紧急任务
    fun downloadUrgent(url: String): DownloadRequestBuilder {
        return DownloadRequestBuilder().url(url).priority(DownloadPriority.URGENT.value)
    }
    
    // 新增：创建后台任务
    fun downloadBackground(url: String): DownloadRequestBuilder {
        return DownloadRequestBuilder().url(url).priority(DownloadPriority.LOW.value)
    }

    // 批量操作（占位）
    fun pauseAll(): DownloadManager {
        InMemoryTaskStore.getAll().forEach { pause(it.id) }
        return this
    }
    fun resumeAll(): DownloadManager {
        InMemoryTaskStore.getAll().forEach { resume(it.id) }
        return this
    }
    fun cancelAll(): DownloadManager {
        InMemoryTaskStore.getAll().forEach { cancel(it.id) }
        return this
    }

    // 清理 API
    fun cleanCompleted(
        deleteFiles: Boolean = false,
        beforeTime: Long? = null,
        byTag: String? = null,
        limit: Int? = null
    ) {
        scope.launch(dispatcherIO) {
            retentionManager?.executeRetentionPolicy()
        }
    }
    
    // 存储管理 API
    fun getStorageInfo(): StorageInfo? = storageManager?.getStorageInfo()
    fun isLowStorage(): Boolean = storageManager?.isLowStorage?.value ?: false
    fun isPathAllowed(path: String): Boolean = storageManager?.isPathAllowed(path) ?: false
    fun getRecommendedPath(): String = storageManager?.getRecommendedPath() ?: ""
    
    // 缓存管理 API
    fun getCacheStats(): CacheStats? = cacheManager?.cacheStats?.value
    fun getHotTasks(): List<DownloadTask> = cacheManager?.getHotTasks() ?: emptyList()
    suspend fun getColdTasks(): List<DownloadTask> = cacheManager?.getColdTasks() ?: emptyList()
    
    // 保留策略 API
    suspend fun getRetentionStats(): RetentionStats? = retentionManager?.getRetentionStats()
    fun executeRetentionPolicy() {
        scope.launch(dispatcherIO) {
            retentionManager?.executeRetentionPolicy()
        }
    }

    // 配置
    fun config(block: DownloadConfig.() -> Unit) {
        block(config)
        OkHttpHelper.rebuildClient(config)
    }

    internal fun currentConfig(): DownloadConfig = config

    // 旧的监听器API已移除，请使用 flowListener 进行响应式监听

    // 内部事件：创建任务时注册并派发开始事件，并启动下载
    fun onTaskCreated(task: DownloadTask) {
        InMemoryTaskStore.put(task)
        scope.launch(dispatcherIO) {
            cacheManager?.putTask(task)
        }
        // 旧监听器已移除，现在通过EventBus和Flow通知
        // 入队并尝试调度
        dispatcher.enqueue(task)
        // 初始进入队列时标记为 WAITING，便于 UI 直观展示
        updateTaskInternal(task.copy(status = DownloadStatus.WAITING, speed = 0L, updateTime = System.currentTimeMillis()))
        // 高级调度器会自动处理调度
    }

    @Synchronized
    private fun scheduleNext() {
        // 尝试取出直到达到并发上限
        while (true) {
            val next = dispatcher.dequeue() ?: break
            // 直接标记为 DOWNLOADING，让 UI 立即切到进度态
            val running = next.copy(status = DownloadStatus.DOWNLOADING, speed = 0L, updateTime = System.currentTimeMillis())
            updateTaskInternal(running)
            // 触发一次进度通知，推动 UI 立刻刷新（即使暂时为 0%）
            // 旧监听器已移除，现在通过EventBus和Flow通知
            scope.launch { engine.start(next) }
        }
    }

    fun setMaxConcurrent(count: Int) { dispatcher.setMaxConcurrentTasks(count) }

    // 供引擎使用的内部方法
    internal fun updateTaskInternal(task: DownloadTask) {
        InMemoryTaskStore.put(task)
        scope.launch(dispatcherIO) {
            cacheManager?.putTask(task)
            // 更新 StateFlow
            _tasksState.value = cacheManager?.getAllTasks()?.sortedBy { it.createTime } ?: emptyList()
        }
        // 当任务结束或被取消/暂停时，从运行集中移除并尝试补位
        if (task.status == DownloadStatus.COMPLETED ||
            task.status == DownloadStatus.FAILED ||
            task.status == DownloadStatus.CANCELLED ||
            task.status == DownloadStatus.PAUSED) {
            dispatcher.remove(task.id)
            scheduleNext()
            // 若任务已完成，根据保留策略尝试清理
            if (task.status == DownloadStatus.COMPLETED) {
                scope.launch(dispatcherIO) { 
                    retentionManager?.executeRetentionPolicy()
                }
            }
        }
    }

    // 旧的监听器管理器已移除，请使用 flowListener

    // 引擎控制
    fun pause(taskId: String) {
        // 移出等待队列
        dispatcher.remove(taskId)
        val t = InMemoryTaskStore.get(taskId)
    if (t != null && (t.status == DownloadStatus.WAITING || t.status == DownloadStatus.PENDING)) {
            val paused = t.copy(status = DownloadStatus.PAUSED, speed = 0L, updateTime = System.currentTimeMillis())
            updateTaskInternal(paused)
            // 主动通知一次，驱动 UI 立即刷新为“继续”
            // 旧监听器已移除，现在通过EventBus和Flow通知
        } else {
            // 正在下载则交由引擎处理
            engine.pause(taskId)
        }
        scheduleNext()
    }

    fun resume(taskId: String) {
        val t = InMemoryTaskStore.get(taskId) ?: return
        // 标记为待执行并入队
    val pending = t.copy(status = DownloadStatus.WAITING, speed = 0L, updateTime = System.currentTimeMillis())
        InMemoryTaskStore.put(pending)
        repository?.let { repo -> scope.launch(Dispatchers.IO) { repo.save(pending) } }
        dispatcher.enqueue(pending)
        // 主动通知一次，UI 立刻显示“等待中”
        // 旧监听器已移除，现在通过EventBus和Flow通知
        scheduleNext()
    }

    fun cancel(taskId: String) {
        dispatcher.remove(taskId)
        val t = InMemoryTaskStore.get(taskId)
    if (t != null && (t.status == DownloadStatus.WAITING || t.status == DownloadStatus.PENDING || t.status == DownloadStatus.PAUSED)) {
            val cancelled = t.copy(status = DownloadStatus.CANCELLED, speed = 0L, updateTime = System.currentTimeMillis())
            updateTaskInternal(cancelled)
        } else {
            engine.cancel(taskId)
        }
        scheduleNext()
    }

    // 显式删除单任务
    fun deleteTask(taskId: String, deleteFile: Boolean = false) {
        val t = InMemoryTaskStore.get(taskId) ?: return
        // 停止并移除调度
        cancel(taskId)
        // 删文件
        if (deleteFile) {
            kotlin.runCatching {
                val f = java.io.File(t.filePath, t.fileName)
                if (f.exists()) f.delete()
                val part = java.io.File(t.filePath, "${t.fileName}.part")
                if (part.exists()) part.delete()
            }
        }
        // 删库&内存&缓存
        repository?.let { repo -> scope.launch(dispatcherIO) { repo.delete(taskId) } }
        InMemoryTaskStore.remove(taskId)
        scope.launch(dispatcherIO) {
            cacheManager?.removeTask(taskId)
            _tasksState.value = cacheManager?.getAllTasks()?.sortedBy { it.createTime } ?: emptyList()
        }
    }

    // Flow: 获取单任务进度流（进度, 速度）
    fun getProgressFlow(taskId: String) = taskProgressFlows.getOrPut(taskId) {
        MutableSharedFlow(replay = 1, extraBufferCapacity = 64)
    }.asSharedFlow()

    // 供引擎与管理器内部调用的进度派发
    internal fun emitProgress(task: DownloadTask, progress: Int, speed: Long) {
        taskProgressFlows[task.id]?.tryEmit(progress to speed)
    }

    // 内部清理实现
    private suspend fun cleanCompletedInternal(
        deleteFiles: Boolean,
        beforeTime: Long? = null,
        byTag: String? = null,
        limit: Int? = null,
        retention: Retention? = null
    ) {
        val all = InMemoryTaskStore.getAll()
        val completed = all.filter { it.status == DownloadStatus.COMPLETED }
            .sortedBy { it.updateTime }

        val now = System.currentTimeMillis()
        var candidates = completed.asSequence()
        beforeTime?.let { t -> candidates = candidates.filter { it.updateTime <= t } }
        byTag?.let { tag -> candidates = candidates.filter { it.extras?.contains(tag) == true || it.packageName == tag } }

        retention?.let { r ->
            if (r.keepDays > 0) {
                val threshold = now - r.keepDays * 24L * 60L * 60L * 1000L
                candidates = candidates.filter { it.updateTime < threshold }
            }
            if (r.keepLatestCompleted > 0) {
                val toKeep = completed.takeLast(r.keepLatestCompleted).map { it.id }.toSet()
                candidates = candidates.filterNot { it.id in toKeep }
            }
        }

        val toDelete = candidates.toList().let { list ->
            if (limit != null && limit > 0) list.take(limit) else list
        }

        if (toDelete.isEmpty()) return

        toDelete.forEach { task ->
            if (deleteFiles) {
                kotlin.runCatching {
                    val f = java.io.File(task.filePath, task.fileName)
                    if (f.exists()) f.delete()
                    val part = java.io.File(task.filePath, "${task.fileName}.part")
                    if (part.exists()) part.delete()
                }
            }
            repository?.let { repo -> scope.launch(dispatcherIO) { repo.delete(task.id) } }
            // 从内存移除
            InMemoryTaskStore.remove(task.id)
        }
        // 同步 StateFlow
        _tasksState.value = InMemoryTaskStore.getAll().sortedBy { it.createTime }
    }
}
