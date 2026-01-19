package com.pichs.download.core

import com.pichs.download.config.DownloadConfig
import com.pichs.download.config.Retention
import com.pichs.download.config.RetentionConfig
import com.pichs.download.config.RetentionStats
import com.pichs.download.model.DownloadStatus
import com.pichs.download.model.DownloadTask
import com.pichs.download.model.NetworkDownloadConfig
import com.pichs.download.model.PauseReason
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

object DownloadManager {

    private const val TAG = "DownloadManager"
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
    @Volatile private var networkRuleManager: NetworkRuleManager? = null
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
        networkRuleManager = NetworkRuleManager(context.applicationContext, this)
        
        // 启动存储监控
        storageManager!!.startMonitoring()
        
        // 启动高级调度器
        scheduler = DownloadScheduler(context.applicationContext, engine, dispatcher)
        scheduler?.start()
        
        // 任务加载和恢复逻辑延迟到 restoreInterruptedTasks() 调用时执行
        // 这样可以确保 checkAfterCallback 已经设置好
        DownloadLog.d(TAG, "DownloadManager 初始化完成，等待调用 restoreInterruptedTasks() 恢复任务")
    }
    
    /**
     * 恢复智能暂停/恢复状态
     * 进程重启后检查所有暂停的任务，根据pauseReason决定是否自动恢复
     */
    private fun restorePauseResumeState() {
        try {
            val allTasks = InMemoryTaskStore.getAll()
            val pausedTasks = allTasks.filter { it.status == DownloadStatus.PAUSED }
            
            if (pausedTasks.isEmpty()) {
                DownloadLog.d(TAG, "没有发现暂停的任务")
                return
            }
            
            DownloadLog.d(TAG, "发现 ${pausedTasks.size} 个暂停的任务，检查是否需要自动恢复")
            
            // 收集可以恢复的任务
            val tasksToResume = mutableListOf<DownloadTask>()
            
            pausedTasks.forEach { task ->
                when (task.pauseReason) {
                    com.pichs.download.model.PauseReason.NETWORK_ERROR -> {
                        DownloadLog.d(TAG, "发现网络异常暂停的任务: ${task.id} - ${task.fileName}")
                        val networkAvailable = appContext?.let { NetworkUtils.isNetworkAvailable(it) } ?: true
                        if (networkAvailable) {
                            val waitingTask = task.copy(
                                status = DownloadStatus.WAITING,
                                pauseReason = null,
                                updateTime = System.currentTimeMillis()
                            )
                            tasksToResume.add(waitingTask)
                            DownloadLog.d(TAG, "网络已连接，准备恢复任务: ${task.id}")
                        } else {
                            DownloadLog.d(TAG, "网络仍不可用，保持任务暂停: ${task.id}")
                        }
                    }
                    com.pichs.download.model.PauseReason.STORAGE_FULL -> {
                        DownloadLog.d(TAG, "发现存储空间不足暂停的任务: ${task.id} - ${task.fileName}")
                        val availableSpace = storageManager?.getAvailableSpace() ?: Long.MAX_VALUE
                        val hasEnoughSpace = availableSpace > task.totalSize
                        if (hasEnoughSpace) {
                            val waitingTask = task.copy(
                                status = DownloadStatus.WAITING,
                                pauseReason = null,
                                updateTime = System.currentTimeMillis()
                            )
                            tasksToResume.add(waitingTask)
                            DownloadLog.d(TAG, "存储空间恢复，准备恢复任务: ${task.id}")
                        }
                    }
                    com.pichs.download.model.PauseReason.USER_MANUAL -> {
                        DownloadLog.d(TAG, "保持用户手动暂停的任务: ${task.id} - ${task.fileName}")
                    }
                    else -> {
                        DownloadLog.d(TAG, "保持其他原因暂停的任务: ${task.id} - ${task.fileName}")
                    }
                }
            }
            
            // 恢复收集到的任务（使用与 init 相同的分组逻辑）
            if (tasksToResume.isNotEmpty()) {
                val sortedTasks = tasksToResume.sortedBy { it.createTime }
                
                // 分组：已确认使用流量的任务 vs 未确认的任务
                val confirmedTasks = sortedTasks.filter { it.cellularConfirmed }
                val unconfirmedTasks = sortedTasks.filter { !it.cellularConfirmed }
                
                DownloadLog.d(TAG, "恢复暂停任务: 已确认=${confirmedTasks.size}, 未确认=${unconfirmedTasks.size}")
                
                // 已确认的任务直接入队
                if (confirmedTasks.isNotEmpty()) {
                    confirmedTasks.forEach { task ->
                        InMemoryTaskStore.put(task)
                        scope.launch(Dispatchers.IO) { repository?.save(task) }
                        dispatcher.enqueue(task)
                    }
                    scheduleNext()
                }
                
                // 未确认的任务走后置检查流程（可能需要流量确认）
                if (unconfirmedTasks.isNotEmpty()) {
                    val totalSize = unconfirmedTasks.sumOf { it.estimatedSize }
                    unconfirmedTasks.forEach { task ->
                        InMemoryTaskStore.put(task)
                        scope.launch(Dispatchers.IO) { repository?.save(task) }
                    }
                    checkAfterCreate(totalSize, unconfirmedTasks)
                }
                
                // 更新 StateFlow
                _tasksState.value = InMemoryTaskStore.getAll().sortedBy { it.createTime }
            }
        } catch (e: Exception) {
            DownloadLog.e(TAG, "恢复暂停/恢复状态时发生异常", e)
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
    
    /**
     * 检查是否有可用的下载槽位
     * @return true 表示可以立即开始下载，false 表示需要排队等待
     */
    fun hasAvailableSlot(): Boolean {
        val running = dispatcher.getRunningTasks().size
        val limit = dispatcher.getCurrentConcurrencyLimit()
        return running < limit
    }

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
    
    // ==================== 批量下载 API ====================
    
    /**
     * 批量创建下载任务
     * @param builders 任务构建器列表
     * @return 创建的任务列表
     */
    fun startTasks(builders: List<DownloadRequestBuilder>): List<DownloadTask> {
        // 先创建所有任务对象（不触发检查）
        val tasks = builders.map { builder ->
            builder.buildTask()
        }
        
        // 计算总大小
        val totalSize = tasks.sumOf { it.estimatedSize }
        
        // 批量调用创建后检查
        checkAfterCreate(totalSize, tasks)
        
        return tasks
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
    
    /**
     * 恢复所有被中断的任务（公开 API）
     * 用于在 checkAfterCallback 设置后调用，恢复：
     * 1. DOWNLOADING/WAITING/PENDING 状态的"僵尸"任务（进程被杀导致）
     * 2. 非用户手动暂停的 PAUSED 任务（如网络异常、存储不足等）
     * 
     * 批量处理，只弹一次确认框
     */
    fun restoreInterruptedTasks(): DownloadManager {
        scope.launch(dispatcherIO) {
            try {
                DownloadLog.d(TAG, "开始从数据库恢复被中断的任务")
                
                // 从数据库读取所有历史任务
                val history = repository?.getAll() ?: emptyList()
                DownloadLog.d(TAG, "从数据库读取到 ${history.size} 个历史任务")
                
                if (history.isEmpty()) {
                    DownloadLog.d(TAG, "没有历史任务，跳过恢复")
                    return@launch
                }
                
                val tasksToResume = mutableListOf<DownloadTask>()
                val now = System.currentTimeMillis()
                
                history.forEach { task ->
                    // 清理脏记录：已完成但文件不存在的任务
                    if (task.status == DownloadStatus.COMPLETED) {
                        val fileExists = java.io.File(task.filePath, task.fileName).exists()
                        if (!fileExists) {
                            DownloadLog.d(TAG, "清理脏记录（文件不存在）: ${task.id} - ${task.fileName}")
                            repository?.delete(task.id)
                            return@forEach
                        }
                        // 文件存在的已完成任务，加载到内存但不恢复下载
                        InMemoryTaskStore.put(task)
                        return@forEach
                    }
                    
                    when (task.status) {
                        // 僵尸任务：进程被杀时正在下载/等待的任务
                        DownloadStatus.DOWNLOADING, DownloadStatus.WAITING, DownloadStatus.PENDING -> {
                            val waitingTask = task.copy(
                                status = DownloadStatus.WAITING,
                                speed = 0L,
                                updateTime = now
                            )
                            InMemoryTaskStore.put(waitingTask)
                            tasksToResume.add(waitingTask)
                            DownloadLog.d(TAG, "发现僵尸任务: ${task.id} - ${task.fileName}, status=${task.status}")
                        }
                        
                        // 暂停任务：根据暂停原因决定是否恢复
                        DownloadStatus.PAUSED -> {
                            // 先加载到内存
                            InMemoryTaskStore.put(task)
                            
                            when (task.pauseReason) {
                                PauseReason.USER_MANUAL -> {
                                    // 用户手动暂停，不自动恢复
                                    DownloadLog.d(TAG, "保持用户手动暂停的任务: ${task.id}")
                                }
                                PauseReason.NETWORK_ERROR -> {
                                    // 网络异常暂停，检查网络状态
                                    val networkAvailable = appContext?.let { NetworkUtils.isNetworkAvailable(it) } ?: true
                                    if (networkAvailable) {
                                        val waitingTask = task.copy(
                                            status = DownloadStatus.WAITING,
                                            pauseReason = null,
                                            updateTime = now
                                        )
                                        InMemoryTaskStore.put(waitingTask)
                                        tasksToResume.add(waitingTask)
                                        DownloadLog.d(TAG, "网络已恢复，准备恢复任务: ${task.id}")
                                    }
                                }
                                PauseReason.WIFI_UNAVAILABLE -> {
                                    // WiFi 不可用暂停，检查 WiFi 状态
                                    val wifiAvailable = appContext?.let { NetworkUtils.isWifiAvailable(it) } ?: false
                                    if (wifiAvailable) {
                                        val waitingTask = task.copy(
                                            status = DownloadStatus.WAITING,
                                            pauseReason = null,
                                            updateTime = now
                                        )
                                        InMemoryTaskStore.put(waitingTask)
                                        tasksToResume.add(waitingTask)
                                        DownloadLog.d(TAG, "WiFi 已连接，准备恢复任务: ${task.id}")
                                    }
                                }
                                PauseReason.STORAGE_FULL -> {
                                    // 存储不足暂停，检查存储空间
                                    val availableSpace = storageManager?.getAvailableSpace() ?: Long.MAX_VALUE
                                    if (availableSpace > task.totalSize) {
                                        val waitingTask = task.copy(
                                            status = DownloadStatus.WAITING,
                                            pauseReason = null,
                                            updateTime = now
                                        )
                                        InMemoryTaskStore.put(waitingTask)
                                        tasksToResume.add(waitingTask)
                                        DownloadLog.d(TAG, "存储空间已恢复，准备恢复任务: ${task.id}")
                                    }
                                }
                                PauseReason.CELLULAR_PENDING -> {
                                    // 等待流量确认，走后置检查流程
                                    val waitingTask = task.copy(
                                        status = DownloadStatus.WAITING,
                                        pauseReason = null,
                                        updateTime = now
                                    )
                                    InMemoryTaskStore.put(waitingTask)
                                    tasksToResume.add(waitingTask)
                                    DownloadLog.d(TAG, "流量待确认任务，准备走后置检查: ${task.id}")
                                }
                                else -> {
                                    // 其他原因（如 BATTERY_LOW, SYSTEM_RESOURCE_LOW），暂不自动恢复
                                    DownloadLog.d(TAG, "保持其他原因暂停的任务: ${task.id}, reason=${task.pauseReason}")
                                }
                            }
                        }
                        
                        else -> {
                            // 其他状态（FAILED, CANCELLED等）也加载到内存
                            InMemoryTaskStore.put(task)
                        }
                    }
                }
                
                if (tasksToResume.isEmpty()) {
                    DownloadLog.d(TAG, "没有需要恢复的任务")
                    return@launch
                }
                
                DownloadLog.d(TAG, "准备恢复 ${tasksToResume.size} 个任务")
                
                // 按优先级降序 + 创建时间升序排序
                val sortedTasks = tasksToResume.sortedWith(
                    compareByDescending<DownloadTask> { it.priority }
                        .thenBy { it.createTime }
                )
                
                // 分组处理
                val confirmedTasks = sortedTasks.filter { it.cellularConfirmed }
                val unconfirmedTasks = sortedTasks.filter { !it.cellularConfirmed }
                
                DownloadLog.d(TAG, "恢复任务分组: 已确认流量=${confirmedTasks.size}, 未确认=${unconfirmedTasks.size}")
                
                // 已确认的任务直接入队
                if (confirmedTasks.isNotEmpty()) {
                    confirmedTasks.forEach { task ->
                        InMemoryTaskStore.put(task)
                        repository?.save(task)
                        dispatcher.enqueue(task)
                    }
                    scheduleNext()
                }
                
                // 未确认的任务走后置检查流程（会触发弹窗）
                if (unconfirmedTasks.isNotEmpty()) {
                    val totalSize = unconfirmedTasks.sumOf { it.estimatedSize }
                    unconfirmedTasks.forEach { task ->
                        InMemoryTaskStore.put(task)
                        repository?.save(task)
                    }
                    checkAfterCreate(totalSize, unconfirmedTasks)
                }
                
                // 更新 StateFlow
                _tasksState.value = InMemoryTaskStore.getAll().sortedBy { it.createTime }
                
            } catch (e: Exception) {
                DownloadLog.e(TAG, "恢复中断任务时发生异常", e)
            }
        }
        return this
    }

    /**
     * 批量恢复所有暂停的任务
     * 优化：批量后置检查，只弹一次确认框
     */
    fun resumeAll(): DownloadManager {
        val pausedTasks = InMemoryTaskStore.getAll()
            .filter { it.status == DownloadStatus.PAUSED }
        resumeTasksBatch(pausedTasks)
        return this
    }
    
    /**
     * 批量恢复指定暂停原因的任务
     * @param pauseReason 暂停原因
     */
    fun resumeAll(pauseReason: com.pichs.download.model.PauseReason): DownloadManager {
        val tasksToResume = InMemoryTaskStore.getAll()
            .filter { it.status == DownloadStatus.PAUSED && it.pauseReason == pauseReason }
        resumeTasksBatch(tasksToResume)
        return this
    }
    
    /**
     * 批量恢复指定的任务列表
     * @param tasks 要恢复的任务列表
     */
    fun resumeTasks(tasks: List<DownloadTask>): DownloadManager {
        // 从内存获取最新状态，避免使用过期的快照数据
        val pausedTasks = tasks.mapNotNull { InMemoryTaskStore.get(it.id) }
            .filter { it.status == DownloadStatus.PAUSED }
        resumeTasksBatch(pausedTasks)
        return this
    }
    
    /**
     * 内部批量恢复方法
     * 批量后置检查，只弹一次确认框
     */
    private fun resumeTasksBatch(tasks: List<DownloadTask>) {
        if (tasks.isEmpty()) return
        
        // 按优先级降序 + 创建时间升序排序，确保高优先级任务先入队
        val sortedTasks = tasks.sortedWith(
            compareByDescending<DownloadTask> { it.priority }
                .thenBy { it.createTime }
        )
        
        val config = networkRuleManager?.config ?: NetworkDownloadConfig()
        
        // 如果后置检查未启用，直接全部恢复
        if (!config.checkAfterCreate) {
            sortedTasks.forEach { task ->
                val pending = task.copy(
                    status = DownloadStatus.WAITING,
                    pauseReason = null,
                    speed = 0L,
                    updateTime = System.currentTimeMillis()
                )
                InMemoryTaskStore.put(pending)
                repository?.let { repo -> scope.launch(Dispatchers.IO) { repo.save(pending) } }
                dispatcher.enqueue(pending)
                DownloadEventBus.emitTaskEvent(TaskEvent.TaskResumed(pending))
            }
            scheduleNext()
            DownloadLog.d(TAG, "批量恢复 ${tasks.size} 个任务（后置检查未启用）")
            return
        }
        
        // 使用第一个任务判断网络状态（批量检查只判断一次）
        val firstTask = tasks.first()
        val totalSize = tasks.sumOf { it.totalSize }
        
        when (val decision = checkAfterPermission(firstTask)) {
            is CheckAfterResult.Allow -> {
                // 允许下载，全部恢复
                sortedTasks.forEach { task ->
                    val pending = task.copy(
                        status = DownloadStatus.WAITING,
                        pauseReason = null,
                        speed = 0L,
                        updateTime = System.currentTimeMillis()
                    )
                    InMemoryTaskStore.put(pending)
                    repository?.let { repo -> scope.launch(Dispatchers.IO) { repo.save(pending) } }
                    dispatcher.enqueue(pending)
                    DownloadEventBus.emitTaskEvent(TaskEvent.TaskResumed(pending))
                }
                scheduleNext()
                DownloadLog.d(TAG, "批量恢复 ${tasks.size} 个任务（网络允许）")
            }
            is CheckAfterResult.NeedConfirmation -> {
                // 需要确认：通过回调弹窗，只弹一次
                networkRuleManager?.checkAfterCallback?.requestCellularConfirmation(
                    pendingTasks = tasks,
                    totalSize = totalSize,
                    onConnectWifi = {
                        // 用户选择连接 WiFi，保持暂停状态
                        DownloadLog.d(TAG, "用户选择连接 WiFi，${tasks.size} 个任务保持暂停")
                    },
                    onUseCellular = {
                        // 用户确认使用流量，标记并恢复所有任务
                        sortedTasks.forEach { task ->
                            val confirmed = task.copy(
                                cellularConfirmed = true,
                                status = DownloadStatus.WAITING,
                                pauseReason = null,
                                speed = 0L,
                                updateTime = System.currentTimeMillis()
                            )
                            InMemoryTaskStore.put(confirmed)
                            repository?.let { repo -> scope.launch(Dispatchers.IO) { repo.save(confirmed) } }
                            dispatcher.enqueue(confirmed)
                            DownloadEventBus.emitTaskEvent(TaskEvent.TaskResumed(confirmed))
                        }
                        scheduleNext()
                        DownloadLog.d(TAG, "批量恢复 ${tasks.size} 个任务（用户确认使用流量）")
                    }
                ) ?: run {
                    // 未设置回调，直接放行
                    DownloadLog.w(TAG, "未设置 checkAfterCallback，直接放行 ${tasks.size} 个任务")
                    sortedTasks.forEach { task ->
                        val pending = task.copy(
                            status = DownloadStatus.WAITING,
                            pauseReason = null,
                            speed = 0L,
                            updateTime = System.currentTimeMillis()
                        )
                        InMemoryTaskStore.put(pending)
                        repository?.let { repo -> scope.launch(Dispatchers.IO) { repo.save(pending) } }
                        dispatcher.enqueue(pending)
                        DownloadEventBus.emitTaskEvent(TaskEvent.TaskResumed(pending))
                    }
                    scheduleNext()
                }
            }
            is CheckAfterResult.Deny -> {
                when (decision.reason) {
                    DenyReason.NO_NETWORK -> {
                        // 无网络，更新暂停原因
                        sortedTasks.forEach { task ->
                            updateTaskInternal(task.copy(
                                pauseReason = com.pichs.download.model.PauseReason.NETWORK_ERROR,
                                updateTime = System.currentTimeMillis()
                            ))
                        }
                        // 通知使用端
                        networkRuleManager?.checkAfterCallback?.requestConfirmation(
                            scenario = NetworkScenario.NO_NETWORK,
                            pendingTasks = tasks,
                            totalSize = totalSize,
                            onConnectWifi = { },
                            onUseCellular = { }
                        )
                        DownloadLog.d(TAG, "批量恢复失败：无网络，${tasks.size} 个任务保持暂停")
                    }
                    DenyReason.WIFI_ONLY_MODE -> {
                        // 仅 WiFi 模式，更新暂停原因
                        sortedTasks.forEach { task ->
                            updateTaskInternal(task.copy(
                                pauseReason = com.pichs.download.model.PauseReason.WIFI_UNAVAILABLE,
                                updateTime = System.currentTimeMillis()
                            ))
                        }
                        networkRuleManager?.checkAfterCallback?.showWifiOnlyHint(firstTask)
                        DownloadLog.d(TAG, "批量恢复失败：仅 WiFi 模式，${tasks.size} 个任务保持暂停")
                    }

                }
            }
        }
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
    
    /**
     * 设置保留策略配置
     * @param config 保留策略配置，可设置保护期、保留天数、保留数量等
     */
    fun setRetentionConfig(config: RetentionConfig) {
        retentionManager?.config = config
        DownloadLog.d(TAG, "Retention配置已更新: protectionPeriodHours=${config.protectionPeriodHours}")
    }
    
    /**
     * 获取当前保留策略配置
     */
    fun getRetentionConfig(): RetentionConfig {
        return retentionManager?.config ?: RetentionConfig()
    }
    
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
    
    /**
     * 创建前检查
     * @param totalSize 总大小
     * @param count 任务数量
     * @return 检查结果
     */
    fun checkBeforeCreate(totalSize: Long, count: Int = 1): com.pichs.download.model.CheckBeforeResult {
        return networkRuleManager?.checkBeforeCreate(totalSize, count) 
            ?: com.pichs.download.model.CheckBeforeResult.Allow
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
    
    // ==================== URL 查询 API ====================
    
    /**
     * 通过 URL 查询任务（返回第一个匹配的）
     * 用于检查某个 URL 是否已有任务
     * @param url 下载 URL
     * @return 匹配的任务，如果没有则返回 null
     */
    fun getTaskByUrl(url: String): DownloadTask? {
        return InMemoryTaskStore.getAll().firstOrNull { it.url == url }
    }
    
    /**
     * 通过 URL 查询所有匹配的任务
     * @param url 下载 URL
     * @return 所有匹配的任务列表
     */
    fun getTasksByUrl(url: String): List<DownloadTask> {
        return InMemoryTaskStore.getAll().filter { it.url == url }
    }
    
    /**
     * 批量通过 URL 查询任务
     * @param urls URL 列表
     * @return URL 到任务的映射（仅包含有任务的 URL）
     */
    fun getTasksByUrls(urls: List<String>): Map<String, DownloadTask> {
        val allTasks = InMemoryTaskStore.getAll()
        val urlSet = urls.toSet()
        return allTasks
            .filter { it.url in urlSet }
            .associateBy { it.url }
    }
    
    // ==================== 网络规则 API ====================
    
    /**
     * 设置网络下载配置
     */
    fun setNetworkConfig(config: com.pichs.download.model.NetworkDownloadConfig) {
        networkRuleManager?.config = config
    }
    
    /**
     * 获取网络下载配置
     */
    fun getNetworkConfig(): com.pichs.download.model.NetworkDownloadConfig {
        return networkRuleManager?.config ?: com.pichs.download.model.NetworkDownloadConfig()
    }
    
    /**
     * 设置决策回调（使用端实现 UI）
     */
    fun setCheckAfterCallback(callback: CheckAfterCallback?) {
        networkRuleManager?.checkAfterCallback = callback
    }
    
    /**
     * WiFi 连接事件（使用端调用）
     */
    fun onWifiConnected() {
        networkRuleManager?.onWifiConnected()
    }
    
    /**
     * WiFi 断开事件（使用端调用）
     */
    fun onWifiDisconnected() {
        networkRuleManager?.onWifiDisconnected()
    }
    
    /**
     * 前置检查：检查是否可以创建新下载任务
     * 在创建任务前调用，判断网络状态和配置
     * @param totalSize 预估下载总大小（字节）
     * @param count 任务数量
     * @return 检查结果
     */
    fun checkBeforePermission(totalSize: Long, count: Int = 1): com.pichs.download.model.CheckBeforeResult {
        return networkRuleManager?.checkBeforeCreate(totalSize, count)
            ?: com.pichs.download.model.CheckBeforeResult.Allow
    }
    
    /**
     * 后置检查：检查任务是否可以恢复/下载
     * 使用端可调用此方法在恢复前自行判断网络状态
     * @param task 要检查的任务
     * @return 下载决策结果
     */
    fun checkAfterPermission(task: DownloadTask): CheckAfterResult {
        return networkRuleManager?.checkCanDownload(task) ?: CheckAfterResult.Allow
    }
    
    /**
     * 请求流量确认（内部使用）
     */
    internal fun requestCellularConfirmation(tasks: List<DownloadTask>, onAllowed: () -> Unit) {
        networkRuleManager?.requestCellularConfirmation(tasks, onAllowed)
    }
    
    /**
     * 显示仅 WiFi 提示（内部使用）
     */
    internal fun showWifiOnlyHint(task: DownloadTask) {
        networkRuleManager?.showWifiOnlyHint(task)
    }
    
    /**
     * 更新任务的流量确认状态
     * @param taskId 任务ID
     * @param confirmed 是否已确认
     */
    fun updateTaskCellularConfirmed(taskId: String, confirmed: Boolean) {
        val task = InMemoryTaskStore.get(taskId) ?: return
        val updated = task.copy(cellularConfirmed = confirmed, updateTime = System.currentTimeMillis())
        InMemoryTaskStore.put(updated)
        repository?.let { repo -> scope.launch(Dispatchers.IO) { repo.save(updated) } }
        DownloadLog.d(TAG, "任务 $taskId cellularConfirmed 更新为 $confirmed")
    }
    
    // ==================== 流量下载检查 ====================
    
    /**
     * 检查当前是否允许流量下载（不弹窗，只返回结果）
     * 注意：这只检查全局配置，不考虑任务级别的 cellularConfirmed
     * @return true: WiFi 可用 或 配置为不提醒
     */
    fun isCellularDownloadAllowed(): Boolean {
        if (isWifiAvailable()) return true
        val config = getNetworkConfig()
        if (!config.wifiOnly && config.cellularThreshold == com.pichs.download.model.CellularThreshold.NEVER_PROMPT) return true
        return false
    }
    
    /**
     * 批量下载前置检查
     * @param totalSize 待下载总大小
     * @param taskCount 任务数量
     * @param onAllow 允许下载的回调
     * @param onDeny 拒绝下载的回调
     */
    fun checkBeforeBatchDownloadPermission(
        totalSize: Long,
        taskCount: Int,
        onAllow: () -> Unit,
        onDeny: () -> Unit
    ) {
        networkRuleManager?.checkBeforeBatchDownloadPermission(totalSize, taskCount, onAllow, onDeny)
            ?: onAllow() // 未初始化时默认允许
    }


    // 配置
    fun config(block: DownloadConfig.() -> Unit) {
        block(config)
        OkHttpHelper.rebuildClient(config)
        // 传播配置变更到调度器
        scheduler?.updateConfig(config)
    }

    internal fun currentConfig(): DownloadConfig = config
    
    // ==================== 默认下载目录 API ====================
    
    /**
     * 设置默认下载目录
     * 当 DownloadRequestBuilder.path() 未指定时，使用此目录
     * @param dirPath 目录绝对路径
     */
    fun setDefaultDownloadDirPath(dirPath: String) {
        config.defaultDownloadDirPath = dirPath
        DownloadLog.d(TAG, "默认下载目录已设置: $dirPath")
    }
    
    /**
     * 获取默认下载目录
     * @return 已配置的默认目录，如果未配置则返回空字符串
     */
    fun getDefaultDownloadDirPath(): String = config.defaultDownloadDirPath
    
    /**
     * 获取有效的下载目录（优先使用配置的默认目录，否则使用应用缓存目录）
     * @return 有效的目录路径
     */
    fun getEffectiveDownloadDirPath(): String {
        val defaultDir = config.defaultDownloadDirPath
        if (defaultDir.isNotBlank()) return defaultDir
        // 回退到应用缓存目录，如果 appContext 为空则使用系统 Download 目录
        return appContext?.externalCacheDir?.absolutePath 
            ?: appContext?.cacheDir?.absolutePath 
            ?: android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS).absolutePath
    }

    // 旧的监听器API已移除，请使用 flowListener 进行响应式监听

    /**
     * 创建后检查（任务创建后调用）
     * @param totalSize 总大小
     * @param tasks 任务列表
     */
    fun checkAfterCreate(totalSize: Long, tasks: List<DownloadTask>) {
        // 保存所有任务到内存和数据库
        tasks.forEach { task ->
            InMemoryTaskStore.put(task)
            scope.launch(dispatcherIO) {
                cacheManager?.putTask(task)
            }
        }
        
        val config = networkRuleManager?.config ?: NetworkDownloadConfig()
        
        // 如果未启用创建后检查，直接入队
        if (!config.checkAfterCreate) {
            tasks.forEach { task ->
                dispatcher.enqueue(task)
                updateTaskInternal(task.copy(status = DownloadStatus.WAITING, speed = 0L, updateTime = System.currentTimeMillis()))
            }
            scheduleNext()
            return
        }
        
        // 检查网络规则（使用第一个任务的权限判断，因为都是同一批下载）
        val firstTask = tasks.firstOrNull() ?: return
        when (val decision = checkAfterPermission(firstTask)) {
            is CheckAfterResult.Allow -> {
                // 允许下载，全部入队
                tasks.forEach { task ->
                    dispatcher.enqueue(task)
                    updateTaskInternal(task.copy(status = DownloadStatus.WAITING, speed = 0L, updateTime = System.currentTimeMillis()))
                }
                // 触发调度，开始下载
                scheduleNext()
            }
            is CheckAfterResult.NeedConfirmation -> {
                // 需要确认：暂停所有任务，通过回调弹窗
                tasks.forEach { task ->
                    updateTaskInternal(task.copy(status = DownloadStatus.PAUSED, pauseReason = com.pichs.download.model.PauseReason.CELLULAR_PENDING, updateTime = System.currentTimeMillis()))
                }
                
                // 调用决策回调弹窗
                networkRuleManager?.checkAfterCallback?.requestCellularConfirmation(
                    pendingTasks = tasks,
                    totalSize = totalSize,
                    onConnectWifi = {
                        // 用户选择连接WiFi，任务保持暂停状态
                        DownloadLog.d(TAG, "用户选择连接WiFi，${tasks.size} 个任务保持暂停")
                    },
                    onUseCellular = {
                        // 用户确认使用流量，标记任务已确认并恢复
                        tasks.forEach { task ->
                            val confirmedTask = task.copy(cellularConfirmed = true)
                            InMemoryTaskStore.put(confirmedTask)
                            repository?.let { repo -> scope.launch(Dispatchers.IO) { repo.save(confirmedTask) } }
                            resume(task.id)
                        }
                        DownloadLog.d(TAG, "用户确认使用流量，${tasks.size} 个任务标记 cellularConfirmed 并恢复")
                    }
                ) ?: run {
                    // 未设置回调，直接放行
                    DownloadLog.w(TAG, "未设置 decisionCallback，直接放行 ${tasks.size} 个任务")
                    tasks.forEach { task ->
                        dispatcher.enqueue(task)
                        updateTaskInternal(task.copy(status = DownloadStatus.WAITING, speed = 0L, updateTime = System.currentTimeMillis()))
                    }
                    scheduleNext()
                }
            }
            is CheckAfterResult.Deny -> {
                when (decision.reason) {
                    DenyReason.NO_NETWORK -> {
                        // 无网络，暂停所有任务
                        tasks.forEach { task ->
                            updateTaskInternal(task.copy(status = DownloadStatus.PAUSED, pauseReason = com.pichs.download.model.PauseReason.NETWORK_ERROR, updateTime = System.currentTimeMillis()))
                        }
                        // 调用回调通知使用端（使用端可以显示 Toast）
                        networkRuleManager?.checkAfterCallback?.requestConfirmation(
                            scenario = NetworkScenario.NO_NETWORK,
                            pendingTasks = tasks,
                            totalSize = totalSize,
                            onConnectWifi = { },
                            onUseCellular = { }
                        )
                    }
                    DenyReason.WIFI_ONLY_MODE -> {
                        // 仅 WiFi 模式，暂停所有任务
                        tasks.forEach { task ->
                            updateTaskInternal(task.copy(status = DownloadStatus.PAUSED, pauseReason = com.pichs.download.model.PauseReason.WIFI_UNAVAILABLE, updateTime = System.currentTimeMillis()))
                        }
                        networkRuleManager?.checkAfterCallback?.showWifiOnlyHint(firstTask)
                    }

                }
            }
        }
    }
    
    /** 单个任务创建后检查（便捷方法） */
    fun checkAfterCreate(task: DownloadTask) {
        checkAfterCreate(task.estimatedSize, listOf(task))
    }

    private fun scheduleNext() {
        DownloadLog.d(TAG, "scheduleNext() called, scheduler=${scheduler != null}")
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
        
        // 立即同步更新 StateFlow（从内存读取，无延迟）
        // 这是关键修复：确保 UI 监听的 tasksState 立即响应
        _tasksState.value = InMemoryTaskStore.getAll().sortedBy { it.createTime }
        
        // 异步持久化到数据库（不阻塞 UI 更新）
        scope.launch(dispatcherIO) {
            cacheManager?.putTask(task)
        }
        // 当任务结束或被取消/暂停时，从运行集中移除并尝试补位
        if (task.status == DownloadStatus.COMPLETED ||
            task.status == DownloadStatus.FAILED ||
            task.status == DownloadStatus.CANCELLED ||
            task.status == DownloadStatus.PAUSED) {
            dispatcher.remove(task.id)
            scheduleNext()
            // ✅ 修复：移除自动清理触发，防止刚完成的任务被立即删除
            // 清理策略应该由使用端在合适的时机手动调用（如应用启动时）
        }
    }

    // 提供给引擎等内部组件的即时任务读取（绕过缓存层）
    internal fun getTaskImmediate(taskId: String): DownloadTask? {
        return InMemoryTaskStore.get(taskId)
    }

    /**
     * 从内存获取任务，如果内存中没有则从数据库加载
     * 解决进程重启后 InMemoryTaskStore 与数据库不同步的问题
     * @param taskId 任务ID
     * @return 任务对象，如果找不到返回 null
     */
    private fun getTaskOrLoad(taskId: String): DownloadTask? {
        // 优先从内存获取
        var task = InMemoryTaskStore.get(taskId)
        if (task != null) return task
        
        // 内存中没有，尝试从数据库加载
        task = kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            repository?.getById(taskId)
        }
        
        if (task != null) {
            // 加载到内存，保持同步
            InMemoryTaskStore.put(task)
            DownloadLog.d(TAG, "任务从数据库加载到内存: $taskId, status=${task.status}")
        }
        
        return task
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
        // 使用 getTaskOrLoad 确保从内存或数据库获取任务
        val t = getTaskOrLoad(taskId)
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
        // 使用 getTaskOrLoad 确保从内存或数据库获取任务
        val t = getTaskOrLoad(taskId) ?: return
        
        val config = networkRuleManager?.config ?: NetworkDownloadConfig()
        
        // 如果后置检查未启用，直接恢复
        if (!config.checkAfterCreate) {
            val pending = t.copy(
                status = DownloadStatus.WAITING, 
                pauseReason = null,
                speed = 0L, 
                updateTime = System.currentTimeMillis()
            )
            InMemoryTaskStore.put(pending)
            repository?.let { repo -> scope.launch(Dispatchers.IO) { repo.save(pending) } }
            dispatcher.enqueue(pending)
            DownloadLog.d("DownloadManager", "任务恢复(后置检查未启用): $taskId")
            DownloadEventBus.emitTaskEvent(TaskEvent.TaskResumed(pending))
            scheduleNext()
            return
        }
        
        // 检查网络规则
        when (val decision = checkAfterPermission(t)) {
            is CheckAfterResult.Allow -> {
                // 允许下载，标记为待执行并入队
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
                DownloadEventBus.emitTaskEvent(TaskEvent.TaskResumed(pending))
                scheduleNext()
            }
            is CheckAfterResult.NeedConfirmation -> {
                // 需要用户确认
                requestCellularConfirmation(listOf(t)) {
                    // 用户确认后恢复
                    val pending = t.copy(
                        status = DownloadStatus.WAITING, 
                        pauseReason = null,
                        speed = 0L, 
                        updateTime = System.currentTimeMillis()
                    )
                    InMemoryTaskStore.put(pending)
                    repository?.let { repo -> scope.launch(Dispatchers.IO) { repo.save(pending) } }
                    dispatcher.enqueue(pending)
                    DownloadLog.d("DownloadManager", "任务恢复(用户确认): $taskId")
                    DownloadEventBus.emitTaskEvent(TaskEvent.TaskResumed(pending))
                    scheduleNext()
                }
            }
            is CheckAfterResult.Deny -> {
                when (decision.reason) {
                    DenyReason.NO_NETWORK -> {
                        // 无网络，保持暂停状态
                        val paused = t.copy(pauseReason = com.pichs.download.model.PauseReason.NETWORK_ERROR, updateTime = System.currentTimeMillis())
                        updateTaskInternal(paused)
                    }
                    DenyReason.WIFI_ONLY_MODE -> {
                        // 仅 WiFi 模式，保持暂停并提示
                        val paused = t.copy(pauseReason = com.pichs.download.model.PauseReason.WIFI_UNAVAILABLE, updateTime = System.currentTimeMillis())
                        updateTaskInternal(paused)
                        showWifiOnlyHint(t)
                    }

                }
            }
        }
        scheduleNext()
    }

    /**
     * 取消任务（完全删除任务数据和文件）
     * 取消后任务将从数据库和内存中删除，就像从未存在过
     */
    fun cancel(taskId: String) {
        // 直接调用 deleteTask，完全删除
        deleteTask(taskId, deleteFile = true)
    }
    
    // 显式删除单任务
    fun deleteTask(taskId: String, deleteFile: Boolean = false) {
        // 使用 getTaskOrLoad 确保从内存或数据库获取任务
        val t = getTaskOrLoad(taskId) ?: return
        
        // 如果任务正在下载中，先通知引擎停止
        if (t.status == DownloadStatus.DOWNLOADING) {
            engine.cancel(taskId)
        }
        
        // 从调度队列移除
        dispatcher.remove(taskId)
        
        // 删文件
        if (deleteFile) {
            runCatching {
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
        
        // 调度下一个任务
        scheduleNext()
    }
    
    /**
     * 验证并清理无效的任务
     * 检测文件被删除或损坏的任务并清理
     * 
     * @return 清理的任务数量
     */
    suspend fun validateAndCleanTasks(): Int = withContext(dispatcherIO) {
        val allTasks = getAllTasks()
        var cleanedCount = 0
        
        allTasks.forEach { task ->
            val shouldClean = when (task.status) {
                DownloadStatus.COMPLETED -> {
                    // 已完成但文件不存在
                    val file = java.io.File(task.filePath, task.fileName)
                    val fileNotExists = !file.exists()
                    if (fileNotExists) {
                        DownloadLog.d("DownloadManager", 
                            "检测到已完成任务文件丢失: ${task.id} - ${task.fileName}")
                    }
                    fileNotExists
                }
                DownloadStatus.DOWNLOADING, DownloadStatus.PAUSED -> {
                    // 关键修复：只有当已经下载过（currentSize > 0）但文件丢失时，才认为异常
                    if (task.currentSize > 0) {
                        val file = java.io.File(task.filePath, "${task.fileName}.part")
                        val fileNotExists = !file.exists()
                        
                        if (fileNotExists) {
                            DownloadLog.d("DownloadManager", 
                                "检测到下载中任务文件丢失: ${task.id} - ${task.fileName}, currentSize=${task.currentSize}")
                            true
                        } else {
                            // 文件存在但大小超过预期（损坏）
                            val fileSize = file.length()
                            val isCorrupted = task.totalSize > 0 && fileSize > task.totalSize
                            if (isCorrupted) {
                                DownloadLog.d("DownloadManager", 
                                    "检测到文件损坏: ${task.id} - ${task.fileName}, fileSize=$fileSize, expected=${task.totalSize}")
                            }
                            isCorrupted
                        }
                    } else {
                        // currentSize = 0，说明还没开始下载，文件不存在是正常的
                        false
                    }
                }
                else -> false
            }
            
            if (shouldClean) {
                deleteTask(task.id, deleteFile = true)
                cleanedCount++
            }
        }
        
        if (cleanedCount > 0) {
            DownloadLog.d("DownloadManager", "任务验证完成，清理了 $cleanedCount 个无效任务")
        }
        
        cleanedCount
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
        byTag?.let { tag -> candidates = candidates.filter { it.extras?.contains(tag) == true } }

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
