package com.pichs.download.core

import com.pichs.download.config.DownloadConfig
import com.pichs.download.config.Retention
// 旧的监听器已移除，现在使用Flow监听器
import com.pichs.download.model.DownloadStatus
import com.pichs.download.model.DownloadTask
import com.pichs.download.store.InMemoryTaskStore
import com.pichs.download.store.TaskRepository
import com.pichs.download.utils.NetworkUtils
import com.pichs.download.utils.DownloadLog
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

    @Volatile private var scheduler: DownloadScheduler? = null
    @Volatile private var repository: TaskRepository? = null
    @Volatile private var chunkManager: ChunkManager? = null
    @Volatile private var atomicCommitManager: AtomicCommitManager? = null
    @Volatile private var storageManager: StorageManager? = null
    @Volatile private var cacheManager: CacheManager? = null
    @Volatile private var retentionManager: RetentionManager? = null
    @Volatile private var networkAutoResumeManager: NetworkAutoResumeManager? = null
    @Volatile private var appContext: android.content.Context? = null

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
        appContext = context.applicationContext
        repository = TaskRepository(context.applicationContext)
        chunkManager = ChunkManager(DownloadDatabase.get(context.applicationContext).chunkDao())
        atomicCommitManager = AtomicCommitManager(repository!!)
        storageManager = StorageManager(context.applicationContext, repository!!)
        cacheManager = CacheManager(repository!!)
        retentionManager = RetentionManager(repository!!, storageManager!!)
        networkAutoResumeManager = NetworkAutoResumeManager(this)
        
        // 启动存储监控
        storageManager!!.startMonitoring()
        
        // 启动高级调度器
        scheduler = DownloadScheduler(context.applicationContext, engine, dispatcher)
        scheduler?.start()
        // 同步恢复历史任务到内存；使用协程异步加载，避免阻塞主线程
        scope.launch(dispatcherIO) {
            runCatching {
                val history = repository!!.getAll()
                val now = System.currentTimeMillis()
                history.forEach { t ->
                    // 若标记已完成但文件不存在，清理脏记录
                    val fileGone = (t.status == DownloadStatus.COMPLETED)
                            && !java.io.File(t.filePath, t.fileName).exists()
                    if (fileGone) {
                        repository?.delete(t.id)
                        return@forEach
                    }
                    val fixed = if (t.status == DownloadStatus.DOWNLOADING || t.status == DownloadStatus.PENDING) {
                        t.copy(status = DownloadStatus.PAUSED, speed = 0L, updateTime = now)
                    } else t
                    if (fixed !== t) {
                        repository?.save(fixed)
                    }
                    InMemoryTaskStore.put(fixed)
                }
                // 初始化后同步一次 StateFlow
                _tasksState.value = InMemoryTaskStore.getAll().sortedBy { it.createTime }
                
                // 异步恢复智能暂停/恢复状态
                restorePauseResumeState()
                restoreProgressCalculators()
                
                // 自动清理：按保留策略执行（若配置开启）
                config.retention.takeIf { it.keepDays > 0 || it.keepLatestCompleted > 0 }?.let { retention ->
                    cleanCompletedInternal(deleteFiles = false, retention = retention)
                }
            }
        }
    }
    
    /**
     * 恢复智能暂停/恢复状态
     * 进程重启后检查所有暂停的任务，根据pauseReason决定是否自动恢复
     */
    private fun restorePauseResumeState() {
        try {
            val allTasks = InMemoryTaskStore.getAll()
            val pausedTasks = allTasks.filter { it.status == DownloadStatus.PAUSED }
            
            if (pausedTasks.isNotEmpty()) {
                DownloadLog.d("DownloadManager", "发现 ${pausedTasks.size} 个暂停的任务，检查是否需要自动恢复")
                
                pausedTasks.forEach { task ->
                    when (task.pauseReason) {
                        com.pichs.download.model.PauseReason.NETWORK_ERROR -> {
                            DownloadLog.d("DownloadManager", "发现网络异常暂停的任务: ${task.id} - ${task.fileName}")
                            val networkAvailable = appContext?.let { NetworkUtils.isNetworkAvailable(it) } ?: true
                            if (networkAvailable) {
                                // 网络已恢复，重新排队等待下载
                                val waitingTask = task.copy(
                                    status = DownloadStatus.WAITING,
                                    pauseReason = null,
                                    updateTime = System.currentTimeMillis()
                                )
                                InMemoryTaskStore.put(waitingTask)
                                scope.launch { repository?.save(waitingTask) }
                                dispatcher.enqueue(waitingTask)
                                DownloadLog.d("DownloadManager", "网络已连接，自动恢复任务: ${task.id}")
                            } else {
                                // 网络仍不可用，保持暂停状态，等待监听器自动恢复
                                DownloadLog.d("DownloadManager", "网络仍不可用，保持任务暂停: ${task.id}")
                            }
                        }
                        com.pichs.download.model.PauseReason.STORAGE_FULL -> {
                            // 存储空间不足暂停的任务，检查存储空间决定是否自动恢复
                            DownloadLog.d("DownloadManager", "发现存储空间不足暂停的任务: ${task.id} - ${task.fileName}")
                            // 检查存储空间是否足够（简化实现）
                            val availableSpace = storageManager?.getAvailableSpace() ?: Long.MAX_VALUE
                            val hasEnoughSpace = availableSpace > task.totalSize
                            if (hasEnoughSpace) {
                                val waitingTask = task.copy(
                                    status = DownloadStatus.WAITING,
                                    pauseReason = null,
                                    updateTime = System.currentTimeMillis()
                                )
                                InMemoryTaskStore.put(waitingTask)
                                scope.launch { repository?.save(waitingTask) }
                                dispatcher.enqueue(waitingTask)
                                DownloadLog.d("DownloadManager", "存储空间恢复，自动恢复任务: ${task.id}")
                            }
                        }
                        com.pichs.download.model.PauseReason.USER_MANUAL -> {
                            // 用户手动暂停的任务，保持暂停状态
                            DownloadLog.d("DownloadManager", "保持用户手动暂停的任务: ${task.id} - ${task.fileName}")
                        }
                        else -> {
                            // 其他暂停原因，保持暂停状态
                            DownloadLog.d("DownloadManager", "保持其他原因暂停的任务: ${task.id} - ${task.fileName}")
                        }
                    }
                }
                
                // 更新StateFlow
                _tasksState.value = InMemoryTaskStore.getAll().sortedBy { it.createTime }
                
                // 尝试调度新恢复的任务
                scheduleNext()
            } else {
                DownloadLog.d("DownloadManager", "没有发现暂停的任务")
            }
        } catch (e: Exception) {
            DownloadLog.e("DownloadManager", "恢复暂停/恢复状态时发生异常", e)
        }
    }
    
    /**
     * 恢复 ProgressCalculator 状态
     * 为所有正在下载的任务重新初始化 ProgressCalculator
     */
    private fun restoreProgressCalculators() {
        try {
            val allTasks = InMemoryTaskStore.getAll()
            val downloadingTasks = allTasks.filter { 
                it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.WAITING 
            }
            
            if (downloadingTasks.isNotEmpty()) {
                DownloadLog.d("DownloadManager", "恢复 ${downloadingTasks.size} 个任务的 ProgressCalculator")
                
                downloadingTasks.forEach { task ->
                    // 为每个任务重新初始化 ProgressCalculator
                    ProgressCalculatorManager.getCalculator(task.id)
                    DownloadLog.d("DownloadManager", "恢复任务 ProgressCalculator: ${task.id}")
                }
            } else {
                DownloadLog.d("DownloadManager", "没有需要恢复 ProgressCalculator 的任务")
            }
        } catch (e: Exception) {
            DownloadLog.e("DownloadManager", "恢复 ProgressCalculator 时发生异常", e)
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

    // 批量操作
    fun pauseAll(): DownloadManager {
        InMemoryTaskStore.getAll().forEach { pause(it.id) }
        return this
    }
    
    /**
     * 批量暂停任务（支持指定暂停原因）
     * @param pauseReason 暂停原因
     */
    fun pauseAll(pauseReason: com.pichs.download.model.PauseReason): DownloadManager {
        InMemoryTaskStore.getAll().forEach { 
            pauseTask(it.id, pauseReason) 
        }
        return this
    }
    
    /**
     * 暂停所有正在下载的任务（网络断开时使用）
     */
    fun pauseAllForNetworkError(): DownloadManager {
        InMemoryTaskStore.getAll()
            .filter { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.WAITING || it.status == DownloadStatus.PENDING }
            .forEach { 
                pauseTask(it.id, com.pichs.download.model.PauseReason.NETWORK_ERROR) 
            }
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

    // 网络状态检查API
    fun isNetworkAvailable(): Boolean {
        return appContext?.let { NetworkUtils.isNetworkAvailable(it) } ?: false
    }
    
    fun isWifiAvailable(): Boolean {
        return appContext?.let { NetworkUtils.isWifiAvailable(it) } ?: false
    }
    
    fun isCellularAvailable(): Boolean {
        return appContext?.let { NetworkUtils.isCellularAvailable(it) } ?: false
    }
    
    fun getNetworkType(): String {
        return appContext?.let { NetworkUtils.getNetworkType(it) } ?: "Unknown"
    }
    
    fun isMeteredNetwork(): Boolean {
        return appContext?.let { NetworkUtils.isMeteredNetwork(it) } ?: true
    }

    // 网络恢复相关API
    fun onNetworkRestored() {
        networkAutoResumeManager?.onNetworkRestored()
    }
    
    suspend fun getNetworkPausedTaskCount(): Int {
        return networkAutoResumeManager?.getNetworkPausedTaskCount() ?: 0
    }
    
    suspend fun getNetworkPausedTasks(): List<DownloadTask> {
        return networkAutoResumeManager?.getNetworkPausedTasks() ?: emptyList()
    }

    // 配置
    fun config(block: DownloadConfig.() -> Unit) {
        block(config)
        OkHttpHelper.rebuildClient(config)
        // 传播配置变更到调度器
        scheduler?.updateConfig(config)
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

    private fun scheduleNext() {
        // 调度逻辑已移交 DownloadScheduler，此处保留空实现或直接移除调用
        scheduler?.trySchedule()
    }

    fun setMaxConcurrent(count: Int) { dispatcher.setMaxConcurrentTasks(count) }

    // 供引擎使用的内部方法
    internal fun updateTaskInternal(task: DownloadTask) {
        // 状态机守卫：防止已停止的任务被过期的“下载中”状态覆盖
        // 这是一个架构级的保护，用于拦截Worker线程在停止前的最后一次汇报
        val current = InMemoryTaskStore.get(task.id)
        if (current != null) {
            val isStopped = current.status == DownloadStatus.PAUSED || 
                          current.status == DownloadStatus.CANCELLED || 
                          current.status == DownloadStatus.FAILED
            val isReportingRunning = task.status == DownloadStatus.DOWNLOADING
            
            if (isStopped && isReportingRunning) {
                DownloadLog.d("DownloadManager", "状态机守卫拦截: 忽略过期的进度更新 ${task.id}, 当前状态: ${current.status}")
                return
            }
        }

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

    // 提供给引擎等内部组件的即时任务读取（绕过缓存层）
    internal fun getTaskImmediate(taskId: String): DownloadTask? {
        return InMemoryTaskStore.get(taskId)
    }

    // 旧的监听器管理器已移除，请使用 flowListener

    // 引擎控制
    fun pause(taskId: String) {
        pauseTask(taskId, com.pichs.download.model.PauseReason.USER_MANUAL)
    }
    
    /**
     * 暂停任务（支持指定暂停原因）
     * @param taskId 任务ID
     * @param pauseReason 暂停原因
     */
    fun pauseTask(taskId: String, pauseReason: com.pichs.download.model.PauseReason) {
        // 移出等待队列
        dispatcher.remove(taskId)
        val t = InMemoryTaskStore.get(taskId)
        if (t != null && (t.status == DownloadStatus.WAITING || t.status == DownloadStatus.PENDING)) {
            val paused = t.copy(
                status = DownloadStatus.PAUSED, 
                pauseReason = pauseReason,
                speed = 0L, 
                updateTime = System.currentTimeMillis()
            )
            updateTaskInternal(paused)
            DownloadLog.d("DownloadManager", "任务暂停: $taskId, 原因: $pauseReason")
        } else if (t != null && t.status == DownloadStatus.DOWNLOADING) {
            // 正在下载的任务，先更新状态和暂停原因，再交由引擎处理
            val paused = t.copy(
                status = DownloadStatus.PAUSED, 
                pauseReason = pauseReason,
                speed = 0L, 
                updateTime = System.currentTimeMillis()
            )
            updateTaskInternal(paused)
            DownloadLog.d("DownloadManager", "DOWNLOADING任务暂停: $taskId, 原因: $pauseReason, 状态: ${paused.status}")
            // 然后通知引擎停止下载
            engine.pause(taskId)
        } else {
            // 其他状态的任务，直接交由引擎处理
            engine.pause(taskId)
        }
        scheduleNext()
    }

    fun resume(taskId: String) {
        val t = InMemoryTaskStore.get(taskId) ?: return
        // 标记为待执行并入队，清除暂停原因
        val pending = t.copy(
            status = DownloadStatus.WAITING, 
            pauseReason = null,
            speed = 0L, 
            updateTime = System.currentTimeMillis()
        )
        InMemoryTaskStore.put(pending)
        repository?.let { repo -> scope.launch(Dispatchers.IO) { repo.save(pending) } }
        dispatcher.enqueue(pending)
        DownloadLog.d("DownloadManager", "任务恢复: $taskId")
        // 主动通知一次，UI 立刻显示"等待中"
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

    private val lastEmissionMap = java.util.concurrent.ConcurrentHashMap<String, Long>()
    
    // 供引擎与管理器内部调用的进度派发
    internal fun emitProgress(task: DownloadTask, progress: Int, speed: Long) {
        val now = System.currentTimeMillis()
        val last = lastEmissionMap[task.id] ?: 0L
        
        // 限制发射频率：每200ms最多一次，或者当进度达到100%时强制发射
        // 防止大规模并发下载时 System Server 死锁或 ANR
        if (now - last >= 200 || progress >= 100) {
            lastEmissionMap[task.id] = now
            taskProgressFlows[task.id]?.tryEmit(progress to speed)
            if (progress >= 100) {
               lastEmissionMap.remove(task.id)
            }
        }
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
