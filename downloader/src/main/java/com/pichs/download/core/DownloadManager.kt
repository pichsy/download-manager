package com.pichs.download.core

import android.content.Context
import com.pichs.download.config.Checksum
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
import com.pichs.download.utils.FileUtils
import com.pichs.download.utils.OkHttpHelper
import com.pichs.download.store.db.DownloadDatabase
import com.pichs.download.store.db.DownloadMeta
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
import java.util.concurrent.atomic.AtomicBoolean

object DownloadManager {

    private const val TAG = "DownloadManager"
    private val config = DownloadConfig()

    // Flow监听器（新方式）
    val flowDownloadListener = FlowDownloadListener()

    @Deprecated("请示用新的变量：flowDownloadListener 更加贴切直观，用法一样")
    val flowListener = flowDownloadListener

    // 简单内存任务表，后续接入数据库
    // private val tasks = ConcurrentHashMap<String, DownloadTask>()

    // 下载引擎（多线程分片实现）
    private val engine: DownloadEngine = MultiThreadDownloadEngine()
    private val dispatcher = AdvancedDownloadQueueDispatcher()

    @Volatile
    private var scheduler: DownloadScheduler? = null

    @Volatile
    private var repository: TaskRepository? = null

    @Volatile
    private var chunkManager: ChunkManager? = null

    @Volatile
    private var storageManager: StorageManager? = null

    @Volatile
    private var cacheManager: CacheManager? = null

    @Volatile
    private var retentionManager: RetentionManager? = null

    @Volatile
    private var networkAutoResumeManager: NetworkAutoResumeManager? = null

    @Volatile
    private var networkRuleManager: NetworkRuleManager? = null

    @Volatile
    private var appContext: android.content.Context? = null

    @Volatile
    private var isRestored: Boolean = false

    private val restoreStarted = AtomicBoolean(false)

    // 已删除任务的墓碑，阻止恢复流程或迟到的数据库读取把任务复活
    private val deletedTaskIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dispatcherIO: CoroutineDispatcher = Dispatchers.IO

    /**
     * 所有任务写库操作串行化，并且写入时读取内存中的最新快照。
     * 避免进度更新、终态更新、删除操作乱序导致数据库回退或任务复活。
     */
    private enum class PersistKind { TASK, DELETE, META }

    private data class PersistOp(
        val taskId: String,
        val kind: PersistKind
    )

    private val persistOps = kotlinx.coroutines.channels.Channel<PersistOp>(
        kotlinx.coroutines.channels.Channel.UNLIMITED
    )

    init {
        scope.launch(dispatcherIO) {
            for (op in persistOps) {
                runCatching {
                    when (op.kind) {
                        PersistKind.DELETE -> cacheManager?.removeTask(op.taskId)
                        PersistKind.TASK -> InMemoryTaskStore.get(op.taskId)
                            ?.let { cacheManager?.putTask(it) }

                        PersistKind.META -> taskMeta[op.taskId]
                            ?.let { repository?.saveMeta(op.taskId, it) }
                    }
                }.onFailure {
                    DownloadLog.w(TAG, "任务持久化失败: ${op.taskId}", it)
                }
            }
        }
    }

    private fun persistTask(taskId: String) {
        persistOps.trySend(PersistOp(taskId, PersistKind.TASK))
    }

    private fun deletePersistedTask(taskId: String) {
        persistOps.trySend(PersistOp(taskId, PersistKind.DELETE))
    }

    private fun persistTaskMeta(taskId: String) {
        persistOps.trySend(PersistOp(taskId, PersistKind.META))
    }

    // Flow: 任务全量状态
    private val _tasksState = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasksState = _tasksState.asStateFlow()

    internal val tasksRestored: Boolean
        get() = isRestored

    // Flow: 单任务进度
    private val taskProgressFlows = java.util.concurrent.ConcurrentHashMap<String, MutableSharedFlow<Pair<Int, Long>>>()

    // 每个任务的自定义请求头
    private val taskHeaders = java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>()

    // 每个任务的远端校验器（ETag/Last-Modified/远端大小），用于断点续传一致性判断
    private val taskMeta = java.util.concurrent.ConcurrentHashMap<String, DownloadMeta>()

    internal fun setTaskHeaders(taskId: String, headers: Map<String, String>) {
        taskHeaders[taskId] = headers
        val current = taskMeta[taskId] ?: DownloadMeta(taskId)
        taskMeta[taskId] = current.copy(headers = headers)
        persistTaskMeta(taskId)
    }

    internal fun getTaskHeaders(taskId: String): Map<String, String> = taskHeaders[taskId] ?: emptyMap()

    internal fun getTaskMeta(taskId: String): DownloadMeta? = taskMeta[taskId]

    /**
     * 任务级内容校验配置。创建任务时从请求/全局配置快照，进程重启后从 meta 表恢复。
     */
    internal fun setTaskChecksum(taskId: String, checksum: Checksum?) {
        if (checksum == null) return
        val current = taskMeta[taskId]
            ?: DownloadMeta(taskId, headers = taskHeaders[taskId] ?: emptyMap())
        taskMeta[taskId] = current.copy(checksum = checksum)
        persistTaskMeta(taskId)
    }

    internal fun getTaskChecksum(taskId: String): Checksum? = taskMeta[taskId]?.checksum

    /**
     * 探测成功后更新远端校验器；若有变化，引擎会丢弃旧断点后重新下载。
     */
    internal fun updateTaskMeta(taskId: String, eTag: String?, lastModified: String?, totalSize: Long) {
        if (InMemoryTaskStore.get(taskId) == null) return
        val current = taskMeta[taskId]
            ?: DownloadMeta(taskId, headers = taskHeaders[taskId] ?: emptyMap())
        taskMeta[taskId] = current.copy(
            eTag = eTag ?: current.eTag,
            lastModified = lastModified ?: current.lastModified,
            totalSize = if (totalSize >= 0) totalSize else current.totalSize
        )
        persistTaskMeta(taskId)
    }

    internal fun getChunkManager(): ChunkManager? = chunkManager

    fun getContext(): Context? {
        return appContext
    }

    // 初始化：App 启动时调用，用于恢复历史任务
    fun init(context: android.content.Context) {
        if (repository != null) return
        appContext = context.applicationContext
        repository = TaskRepository(context.applicationContext)
        chunkManager = ChunkManager(DownloadDatabase.get(context.applicationContext).chunkDao())
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

    // API 入口
    fun download(url: String): DownloadRequestBuilder = DownloadRequestBuilder().url(url)
    fun create(): DownloadRequestBuilder = DownloadRequestBuilder()

    // 任务管理
    suspend fun getTask(taskId: String): DownloadTask? {
        InMemoryTaskStore.get(taskId)?.let { return it }
        if (taskId in deletedTaskIds) return null
        val loaded = cacheManager?.getTask(taskId) ?: return null
        InMemoryTaskStore.put(loaded)
        return loaded
    }

    /**
     * 获取所有任务（包含内存中最新状态）
     * 解决数据库异步写入导致的状态延迟问题
     */
    suspend fun getAllTasks(): List<DownloadTask> {
        // 优化：如果已经完成从数据库的恢复，直接使用内存数据作为“真理”
        // 避免每次都进行数据库 I/O 和合并计算
        if (isRestored) {
            return InMemoryTaskStore.getAll().sortedBy { it.createTime }
        }

        val dbTasks = cacheManager?.getAllTasks() ?: emptyList()
        val memTasks = InMemoryTaskStore.getAll()

        if (memTasks.isEmpty()) return dbTasks.sortedBy { it.createTime }

        // 内存中的状态覆盖数据库状态
        val mergedMap = dbTasks.associateBy { it.id }.toMutableMap()
        memTasks.forEach { task ->
            mergedMap[task.id] = task
        }

        return mergedMap.values.sortedBy { it.createTime }
    }

    suspend fun getRunningTasks(): List<DownloadTask> = getAllTasks()
        .filter { it.status == DownloadStatus.DOWNLOADING }

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
        val canonicalPath = FileUtils.canonicalPath(path)
        return InMemoryTaskStore.getAll().firstOrNull {
            it.url == url &&
                    FileUtils.canonicalPath(it.filePath) == canonicalPath &&
                    normalizeName(it.fileName) == norm
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
        if (builders.isEmpty()) return emptyList()

        // 同一批内以及和现有任务之间都要去重，避免重复点击产生多份相同下载。
        // 使用内存快照，避免在调用线程上做数据库全表读取。
        val existingTasks = InMemoryTaskStore.getAll()
        val taskByKey = existingTasks.associateBy {
            DownloadRequestKey(
                it.url,
                FileUtils.canonicalPath(it.filePath),
                normalizeName(it.fileName)
            )
        }.toMutableMap()

        val created = mutableListOf<DownloadTask>()
        val resolved = mutableListOf<DownloadTask>()

        builders.forEach { builder ->
            val key = builder.resolveKey()
            val existing = taskByKey[key]
            if (existing == null) {
                val task = builder.buildTask()
                taskByKey[key] = task
                created += task
                resolved += task
                return@forEach
            }

            builder.applyHeadersTo(existing.id)
            builder.applyChecksumTo(existing.id)
            when (existing.status) {
                DownloadStatus.COMPLETED -> {
                    val file = java.io.File(existing.filePath, existing.fileName)
                    if (file.exists()) {
                        resolved += existing
                    } else {
                        deleteTask(existing.id, deleteFile = false)
                        resolved += createBatchTask(builder, key, taskByKey, created)
                    }
                }

                DownloadStatus.CANCELLED, DownloadStatus.FAILED -> {
                    deleteTask(existing.id, deleteFile = false)
                    java.io.File(existing.filePath, "${existing.fileName}.${existing.id}.part")
                        .takeIf { it.exists() }?.delete()
                    java.io.File(existing.filePath, "${existing.fileName}.part")
                        .takeIf { it.exists() }?.delete()
                    resolved += createBatchTask(builder, key, taskByKey, created)
                }

                DownloadStatus.PAUSED -> {
                    resume(existing.id)
                    resolved += com.pichs.download.store.InMemoryTaskStore.get(existing.id) ?: existing
                }

                else -> resolved += existing
            }
        }

        // 只对本次真正新建的任务做创建后检查；复用任务保持原状态
        if (created.isNotEmpty()) {
            checkAfterCreate(created.sumOf { it.estimatedSize }, created)
        }

        // 返回最新的任务状态（checkAfterCreate 可能修改状态，如无网络时设为 PAUSED）
        return resolved.map { task ->
            com.pichs.download.store.InMemoryTaskStore.get(task.id) ?: task
        }
    }

    private fun createBatchTask(
        builder: DownloadRequestBuilder,
        key: DownloadRequestKey,
        taskByKey: MutableMap<DownloadRequestKey, DownloadTask>,
        created: MutableList<DownloadTask>
    ): DownloadTask {
        val task = builder.buildTask()
        taskByKey[key] = task
        created += task
        return task
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
        if (repository == null) {
            DownloadLog.w(TAG, "DownloadManager 尚未初始化，忽略 restoreInterruptedTasks 调用")
            return this
        }
        if (!restoreStarted.compareAndSet(false, true)) {
            DownloadLog.d(TAG, "任务恢复已执行，忽略重复调用")
            return this
        }
        scope.launch(dispatcherIO) {
            try {
                DownloadLog.d(TAG, "开始从数据库恢复被中断的任务")

                // 从数据库读取所有历史任务
                val history = repository?.getAll() ?: emptyList()
                DownloadLog.d(TAG, "从数据库读取到 ${history.size} 个历史任务")

                // 恢复请求头与远端校验器，并清理没有对应任务的孤儿 meta
                val activeTaskIds = history.mapTo(HashSet(history.size)) { it.id }
                InMemoryTaskStore.getAll().forEach { activeTaskIds.add(it.id) }
                restoreTaskMeta(activeTaskIds)

                val tasksToResume = mutableListOf<DownloadTask>()

                history.forEach { dbTask ->
                    // 恢复期间被删除的任务不能因数据库快照而复活
                    if (dbTask.id in deletedTaskIds) return@forEach

                    // 内存中的状态更新（恢复期间新建或操作的任务）优先于数据库快照
                    val memoryTask = InMemoryTaskStore.get(dbTask.id)
                    if (memoryTask == null) {
                        InMemoryTaskStore.put(dbTask)
                        // put 与删除并发时，删除方已写入墓碑，需要撤回
                        if (dbTask.id in deletedTaskIds) {
                            InMemoryTaskStore.remove(dbTask.id)
                            return@forEach
                        }
                    }
                    val task = memoryTask ?: dbTask

                    // 清理脏记录：已完成但文件不存在的任务
                    if (task.status == DownloadStatus.COMPLETED) {
                        if (!java.io.File(task.filePath, task.fileName).exists()) {
                            DownloadLog.d(TAG, "清理脏记录（文件不存在）: ${task.id} - ${task.fileName}")
                            removeTaskState(task.id)
                        } else {
                            deleteTaskTempFiles(task)
                        }
                        return@forEach
                    }

                    if (task.status == DownloadStatus.CANCELLED) {
                        deleteTaskTempFiles(task)
                        return@forEach
                    }

                    when (task.status) {
                        // 僵尸任务：进程被杀时正在下载/等待的任务
                        DownloadStatus.DOWNLOADING, DownloadStatus.WAITING, DownloadStatus.PENDING -> {
                            tasksToResume.add(task)
                            DownloadLog.d(TAG, "发现僵尸任务: ${task.id} - ${task.fileName}, status=${task.status}")
                        }

                        // 暂停任务：根据暂停原因决定是否恢复
                        DownloadStatus.PAUSED -> {
                            val shouldResume = when (task.pauseReason) {
                                PauseReason.USER_MANUAL -> {
                                    DownloadLog.d(TAG, "保持用户手动暂停的任务: ${task.id}")
                                    false
                                }

                                PauseReason.NETWORK_ERROR -> {
                                    appContext?.let { NetworkUtils.isNetworkAvailable(it) } ?: true
                                }

                                PauseReason.WIFI_UNAVAILABLE -> {
                                    appContext?.let { NetworkUtils.isWifiAvailable(it) } ?: false
                                }

                                PauseReason.STORAGE_FULL -> {
                                    (storageManager?.getAvailableSpace() ?: Long.MAX_VALUE) > task.totalSize
                                }

                                PauseReason.CELLULAR_PENDING -> true

                                else -> {
                                    DownloadLog.d(
                                        TAG,
                                        "保持其他原因暂停的任务: ${task.id}, reason=${task.pauseReason}"
                                    )
                                    false
                                }
                            }
                            if (shouldResume) {
                                tasksToResume.add(task)
                                DownloadLog.d(TAG, "准备恢复任务: ${task.id}, reason=${task.pauseReason}")
                            }
                        }

                        else -> Unit
                    }
                }

                // 历史任务已进入内存，先推一次状态，避免恢复期间列表首帧长时间为空
                _tasksState.value = InMemoryTaskStore.getAll().sortedBy { it.createTime }

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
                    confirmedTasks.forEach { task -> enqueueWaiting(task.id) }
                    scheduleNext()
                }

                // 未确认的任务走后置检查流程（会触发弹窗）
                if (unconfirmedTasks.isNotEmpty()) {
                    val totalSize = unconfirmedTasks.sumOf { it.estimatedSize }
                    checkAfterCreate(totalSize, unconfirmedTasks)
                }
            } catch (e: Exception) {
                DownloadLog.e(TAG, "恢复中断任务时发生异常", e)
            } finally {
                // 无论成功失败都要结束恢复流程，避免 isRestored 永远为 false
                _tasksState.value = InMemoryTaskStore.getAll().sortedBy { it.createTime }
                isRestored = true
                DownloadLog.d(TAG, "任务恢复流程结束，isRestored = true")
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
        resumeTasksBatch(pausedTasks, allowManualResume = false)
        return this
    }

    /**
     * 内部批量恢复方法
     * 批量后置检查，只弹一次确认框
     * @param allowManualResume 显式恢复（用户主动调用）时允许恢复手动暂停的任务
     */
    private fun resumeTasksBatch(tasks: List<DownloadTask>, allowManualResume: Boolean = true) {
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
                enqueueWaiting(task.id, allowManualResume = allowManualResume)
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
                    enqueueWaiting(task.id, allowManualResume = allowManualResume)
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
                            enqueueWaiting(
                                task.id,
                                cellularConfirmed = true,
                                allowManualResume = allowManualResume
                            )
                        }
                        scheduleNext()
                        DownloadLog.d(TAG, "批量恢复 ${tasks.size} 个任务（用户确认使用流量）")
                    }
                ) ?: run {
                    // 未设置回调，直接放行
                    DownloadLog.w(TAG, "未设置 checkAfterCallback，直接放行 ${tasks.size} 个任务")
                    sortedTasks.forEach { task ->
                        enqueueWaiting(task.id, allowManualResume = allowManualResume)
                    }
                    scheduleNext()
                }
            }

            is CheckAfterResult.Deny -> {
                when (decision.reason) {
                    DenyReason.NO_NETWORK -> {
                        // 无网络，更新暂停原因
                        sortedTasks.forEach { task ->
                            updateTaskInternal(
                                task.copy(
                                    pauseReason = com.pichs.download.model.PauseReason.NETWORK_ERROR,
                                    updateTime = com.pichs.download.utils.TimeUtils.currentMicros()
                                )
                            )
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
                            updateTaskInternal(
                                task.copy(
                                    pauseReason = com.pichs.download.model.PauseReason.WIFI_UNAVAILABLE,
                                    updateTime = com.pichs.download.utils.TimeUtils.currentMicros()
                                )
                            )
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
            // 无任何筛选条件时按保留策略清理，避免误删全部已完成记录
            if (beforeTime == null && byTag == null && limit == null) {
                val rc = retentionManager?.config
                if (rc != null && (rc.keepCompletedDays > 0 || rc.keepLatestCompleted > 0)) {
                    cleanCompletedInternal(
                        deleteFiles,
                        retention = Retention(
                            keepDays = rc.keepCompletedDays,
                            keepLatestCompleted = rc.keepLatestCompleted
                        )
                    )
                }
                return@launch
            }
            cleanCompletedInternal(deleteFiles, beforeTime, byTag, limit)
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

    /**
     * 网络状态变化时调用（推荐使用）
     * 统一入口，内部会判断当前网络类型并做对应处理：
     * - WiFi 可用：恢复所有网络相关暂停的任务
     * - 仅流量可用：恢复已确认流量的任务，未确认的触发确认流程
     * - 无网络：不做任何处理
     */
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
     * 获取决策回调（内部使用）
     */
    internal fun getCheckAfterCallback(): CheckAfterCallback? {
        return networkRuleManager?.checkAfterCallback
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
        if (task.cellularConfirmed == confirmed) return
        val updated = task.copy(
            cellularConfirmed = confirmed,
            updateTime = com.pichs.download.utils.TimeUtils.currentMicros()
        )
        updateTaskInternal(updated)
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
        if (!currentConfig().allowMetered) return false
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

    // ==================== 全局请求扩展 API ====================

    /**
     * 设置全局请求头。任务级 headers 优先于这里设置的值。
     */
    fun setGlobalHeaders(headers: Map<String, String>) {
        config.globalHeaders.clear()
        config.globalHeaders.putAll(headers)
    }

    fun addGlobalHeader(name: String, value: String) {
        config.globalHeaders[name] = value
    }

    fun removeGlobalHeader(name: String) {
        config.globalHeaders.remove(name)
    }

    fun getGlobalHeaders(): Map<String, String> = config.globalHeaders.toMap()

    /**
     * 添加全局 OkHttp 拦截器（探测请求和分片请求都会经过）。
     * 多次调用按添加顺序执行；新增后立即重建客户端。
     */
    @Synchronized
    fun addRequestInterceptor(interceptor: okhttp3.Interceptor) {
        if (config.requestInterceptors.any { it === interceptor }) return
        config.requestInterceptors.add(interceptor)
        OkHttpHelper.rebuildClient(config)
    }

    @Synchronized
    fun removeRequestInterceptor(interceptor: okhttp3.Interceptor) {
        if (config.requestInterceptors.removeAll { it === interceptor }) {
            OkHttpHelper.rebuildClient(config)
        }
    }

    @Synchronized
    fun clearRequestInterceptors() {
        if (config.requestInterceptors.isNotEmpty()) {
            config.requestInterceptors.clear()
            OkHttpHelper.rebuildClient(config)
        }
    }

    /**
     * 设置自定义文件名解析器，传 null 恢复默认规则。
     */
    fun setFileNameResolver(resolver: com.pichs.download.config.FileNameResolver?) {
        config.fileNameResolver = resolver
    }

    internal fun currentFileNameResolver(): com.pichs.download.config.FileNameResolver? =
        config.fileNameResolver

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
     * 获取有效的下载目录（优先使用配置的默认目录，否则使用应用外部文件目录）
     * 不使用缓存目录，避免系统在空间紧张时清理已下载文件
     * @return 有效的目录路径
     */
    fun getEffectiveDownloadDirPath(): String {
        val defaultDir = config.defaultDownloadDirPath
        if (defaultDir.isNotBlank()) return defaultDir
        return appContext?.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)?.absolutePath
            ?: appContext?.filesDir?.absolutePath
            ?: android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS).absolutePath
    }

    // 旧的监听器API已移除，请使用 flowListener 进行响应式监听

    /**
     * 创建后检查（任务创建后调用）
     * @param totalSize 总大小
     * @param tasks 任务列表
     */
    fun checkAfterCreate(totalSize: Long, tasks: List<DownloadTask>) {
        // 首次创建时写入内存；已存在的任务保留内存中的最新状态（恢复流程/并发操作）
        tasks.forEach { task ->
            if (task.id in deletedTaskIds) return@forEach
            val existing = InMemoryTaskStore.get(task.id)
            if (existing == null) {
                InMemoryTaskStore.put(task)
                persistTask(task.id)
            } else if (task.updateTime > existing.updateTime) {
                updateTaskInternal(task)
            }
        }

        val networkAvailable = appContext?.let { NetworkUtils.isNetworkAvailable(it) } ?: false

        // ✅ 强制检查无网络情况（优先级最高）
        if (!networkAvailable) {
            DownloadLog.d(TAG, "创建后检查：无网络，任务暂停")
            tasks.forEach { task ->
                pauseTaskInternal(task.id, PauseReason.NETWORK_ERROR)
            }
            // 尝试通知 UI（如果设置了回调）
            networkRuleManager?.checkAfterCallback?.requestConfirmation(
                NetworkScenario.NO_NETWORK,
                tasks,
                totalSize,
                {}, {}
            )
            return
        }

        val config = networkRuleManager?.config ?: NetworkDownloadConfig()

        // 如果未启用创建后检查，直接入队
        if (!config.checkAfterCreate) {
            tasks.forEach { task ->
                enqueueWaiting(task.id)
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
                    enqueueWaiting(task.id)
                }
                // 触发调度，开始下载
                scheduleNext()
            }

            is CheckAfterResult.NeedConfirmation -> {
                // 需要确认：暂停所有任务，通过回调弹窗
                tasks.forEach { task ->
                    pauseTaskInternal(task.id, PauseReason.CELLULAR_PENDING)
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
                            enqueueWaiting(task.id, cellularConfirmed = true)
                        }
                        scheduleNext()
                        DownloadLog.d(TAG, "用户确认使用流量，${tasks.size} 个任务标记 cellularConfirmed 并恢复")
                    }
                ) ?: run {
                    // 未设置回调，直接放行
                    DownloadLog.w(TAG, "未设置 decisionCallback，直接放行 ${tasks.size} 个任务")
                    tasks.forEach { task ->
                        enqueueWaiting(task.id)
                    }
                    scheduleNext()
                }
            }

            is CheckAfterResult.Deny -> {
                when (decision.reason) {
                    DenyReason.NO_NETWORK -> {
                        // 无网络，暂停所有任务
                        tasks.forEach { task ->
                            pauseTaskInternal(task.id, PauseReason.NETWORK_ERROR)
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
                            pauseTaskInternal(task.id, PauseReason.WIFI_UNAVAILABLE)
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

    fun setMaxConcurrent(count: Int) {
        dispatcher.setMaxConcurrentTasks(count)
        scheduleNext()
    }

    /**
     * 将任务转为等待中并入队。
     * 已删除、已完成、已取消以及正在运行的任务不会被重复入队。
     * @param cellularConfirmed 非空时同步更新流量确认标记
     * @param allowManualResume 用户主动恢复时允许覆盖手动暂停
     * @return 入队后的任务快照；未入队返回 null
     */
    private fun enqueueWaiting(
        taskId: String,
        cellularConfirmed: Boolean? = null,
        allowManualResume: Boolean = false
    ): DownloadTask? {
        if (taskId in deletedTaskIds) return null
        val current = InMemoryTaskStore.get(taskId) ?: return null
        if (current.status == DownloadStatus.COMPLETED || current.status == DownloadStatus.CANCELLED) {
            return null
        }
        if (current.status == DownloadStatus.DOWNLOADING && isTaskRunning(taskId)) {
            return null
        }
        if (!allowManualResume &&
            current.status == DownloadStatus.PAUSED &&
            current.pauseReason == PauseReason.USER_MANUAL
        ) {
            DownloadLog.d(TAG, "保持用户手动暂停，忽略恢复: $taskId")
            return null
        }
        val waiting = current.copy(
            status = DownloadStatus.WAITING,
            pauseReason = null,
            speed = 0L,
            cellularConfirmed = cellularConfirmed ?: current.cellularConfirmed,
            updateTime = com.pichs.download.utils.TimeUtils.currentMicros()
        )
        if (!updateTaskInternal(
                waiting,
                allowResume = true,
                allowManualResume = allowManualResume
            )
        ) {
            return null
        }
        dispatcher.enqueue(waiting)
        return waiting
    }

    private fun isTaskRunning(taskId: String): Boolean =
        dispatcher.getRunningTasks().any { it.id == taskId }

    private fun pauseTaskInternal(taskId: String, reason: PauseReason) {
        val current = InMemoryTaskStore.get(taskId) ?: return
        if (current.status == DownloadStatus.COMPLETED ||
            current.status == DownloadStatus.CANCELLED ||
            current.status == DownloadStatus.FAILED
        ) {
            return
        }
        // 用户手动暂停是强状态，不被网络/批量逻辑改写
        if (current.status == DownloadStatus.PAUSED &&
            (current.pauseReason == PauseReason.USER_MANUAL || current.pauseReason == reason)
        ) {
            return
        }
        updateTaskInternal(
            current.copy(
                status = DownloadStatus.PAUSED,
                pauseReason = reason,
                speed = 0L,
                updateTime = com.pichs.download.utils.TimeUtils.currentMicros()
            )
        )
    }

    // 供引擎使用的内部方法
    internal fun updateTaskInternal(
        task: DownloadTask,
        allowResume: Boolean = false,
        allowManualResume: Boolean = false
    ): Boolean {
        val current = InMemoryTaskStore.get(task.id)
        if (current == null) {
            // 任务已被删除，拦截迟到线程的更新，避免任务被重新写回内存
            DownloadLog.d("DownloadManager", "忽略已删除任务的更新: ${task.id}")
            return false
        }

        // 终态不可被迟到的进度/暂停/抢占写入覆盖
        if ((current.status == DownloadStatus.COMPLETED ||
                    current.status == DownloadStatus.CANCELLED) &&
            task.status != current.status
        ) {
            DownloadLog.d(
                "DownloadManager",
                "终态守卫拦截: ${current.status} -> ${task.status}, 任务=${task.id}"
            )
            return false
        }
        if (task.status == DownloadStatus.PAUSED &&
            (current.status == DownloadStatus.COMPLETED ||
                    current.status == DownloadStatus.CANCELLED ||
                    current.status == DownloadStatus.FAILED)
        ) {
            DownloadLog.d("DownloadManager", "忽略对终态任务的暂停: ${task.id}, status=${current.status}")
            return false
        }

        val incomingActive = task.status == DownloadStatus.DOWNLOADING ||
                task.status == DownloadStatus.WAITING ||
                task.status == DownloadStatus.PENDING
        if (incomingActive) {
            val blocked = when (current.status) {
                DownloadStatus.COMPLETED, DownloadStatus.CANCELLED -> true
                DownloadStatus.PAUSED, DownloadStatus.FAILED -> !allowResume
                else -> false
            }
            if (blocked) {
                DownloadLog.d(
                    "DownloadManager",
                    "状态机守卫拦截: ${current.status} -> ${task.status}, 任务=${task.id}"
                )
                return false
            }
        }

        // 用户手动暂停是强状态：除显式恢复外，不允许被进度/网络/抢占改写
        val currentManualPaused = current.status == DownloadStatus.PAUSED &&
                current.pauseReason == PauseReason.USER_MANUAL
        if (currentManualPaused && !allowManualResume) {
            if (task.status != DownloadStatus.PAUSED || task.pauseReason != PauseReason.USER_MANUAL) {
                DownloadLog.d("DownloadManager", "保持用户手动暂停: ${task.id}")
                return false
            }
        }

        InMemoryTaskStore.put(task)

        // 立即同步更新 StateFlow（从内存读取，无延迟）
        // 这是关键修复：确保 UI 监听的 tasksState 立即响应
        _tasksState.value = InMemoryTaskStore.getAll().sortedBy { it.createTime }

        // 异步持久化（串行队列会读取最新内存快照，旧状态不会覆盖新状态）
        persistTask(task.id)
        // 当任务结束或被取消/暂停时，从运行集中移除并尝试补位
        if (task.status == DownloadStatus.COMPLETED ||
            task.status == DownloadStatus.FAILED ||
            task.status == DownloadStatus.CANCELLED ||
            task.status == DownloadStatus.PAUSED
        ) {
            dispatcher.remove(task.id)
            scheduleNext()
            // ✅ 修复：移除自动清理触发，防止刚完成的任务被立即删除
            // 清理策略应该由使用端在合适的时机手动调用（如应用启动时）
        }
        return true
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
        // 已删除的任务不允许从数据库回读（DELETE 是异步落库的）
        if (taskId in deletedTaskIds) return null

        // 内存中没有，尝试从数据库加载
        task = kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            repository?.getById(taskId)
        }

        if (task != null) {
            // 加载到内存，保持同步
            InMemoryTaskStore.put(task)
            DownloadLog.d(TAG, "任务从数据库加载到内存: $taskId, status=${task.status}")
        }

        if (!taskMeta.containsKey(taskId)) {
            val meta = kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                repository?.getMeta(taskId)
            }
            if (meta != null) {
                taskMeta[taskId] = meta
                if (meta.headers.isNotEmpty()) {
                    taskHeaders.putIfAbsent(taskId, meta.headers)
                }
            }
        }

        return task
    }

    private suspend fun restoreTaskMeta(activeTaskIds: Set<String>) {
        // 新创建的任务可能尚未写入 downloads 表，用内存快照兜底避免误删
        val liveIds = HashSet<String>(activeTaskIds.size + 8)
        liveIds.addAll(activeTaskIds)
        InMemoryTaskStore.getAll().forEach { liveIds.add(it.id) }
        liveIds.addAll(taskMeta.keys)

        val removed = runCatching { repository?.deleteOrphanMeta(liveIds) ?: 0 }
            .onFailure { DownloadLog.w(TAG, "清理孤儿任务元数据失败", it) }
            .getOrDefault(0)
        if (removed > 0) {
            DownloadLog.d(TAG, "已清理 $removed 条孤儿任务元数据")
        }

        val metas = runCatching { repository?.getAllMeta() ?: emptyList() }
            .getOrElse { error ->
                DownloadLog.w(TAG, "读取任务元数据失败", error)
                emptyList()
            }
        // 读取期间可能有新任务创建，追加一次内存快照
        InMemoryTaskStore.getAll().forEach { liveIds.add(it.id) }
        var loaded = 0
        metas.forEach { meta ->
            if (meta.taskId !in liveIds) return@forEach
            taskMeta.putIfAbsent(meta.taskId, meta)
            if (meta.headers.isNotEmpty()) {
                taskHeaders.putIfAbsent(meta.taskId, meta.headers)
            }
            loaded++
        }
        DownloadLog.d(TAG, "已加载 $loaded 条任务元数据")
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
        if (t != null) {
            val keepCurrentState = t.status == DownloadStatus.COMPLETED ||
                    t.status == DownloadStatus.CANCELLED ||
                    t.status == DownloadStatus.FAILED ||
                    (t.status == DownloadStatus.PAUSED && t.pauseReason == PauseReason.USER_MANUAL)
            if (!keepCurrentState) {
                val paused = t.copy(
                    status = DownloadStatus.PAUSED,
                    pauseReason = pauseReason,
                    speed = 0L,
                    updateTime = com.pichs.download.utils.TimeUtils.currentMicros()
                )
                updateTaskInternal(paused)
                DownloadLog.d("DownloadManager", "任务暂停: $taskId, 原因: $pauseReason")
            }
        }
        // 无论当前状态如何都要通知引擎，避免下载协程仍然存活
        engine.pause(taskId)
        scheduleNext()
    }

    fun resume(taskId: String) {
        // 使用 getTaskOrLoad 确保从内存或数据库获取任务
        val t = getTaskOrLoad(taskId) ?: return

        val current = InMemoryTaskStore.get(taskId) ?: return
        if (current.status == DownloadStatus.COMPLETED ||
            current.status == DownloadStatus.CANCELLED ||
            (current.status == DownloadStatus.DOWNLOADING && isTaskRunning(taskId))
        ) {
            DownloadLog.d(TAG, "任务状态无需恢复: $taskId, status=${current.status}")
            return
        }

        val config = networkRuleManager?.config ?: NetworkDownloadConfig()

        // 如果后置检查未启用，直接恢复
        if (!config.checkAfterCreate) {
            if (enqueueWaiting(taskId, allowManualResume = true) != null) {
                DownloadLog.d(TAG, "任务恢复(后置检查未启用): $taskId")
            }
            scheduleNext()
            return
        }

        // 检查网络规则
        when (val decision = checkAfterPermission(t)) {
            is CheckAfterResult.Allow -> {
                // 允许下载，标记为待执行并入队
                if (enqueueWaiting(taskId, allowManualResume = true) != null) {
                    DownloadLog.d(TAG, "任务恢复: $taskId")
                }
                scheduleNext()
            }

            is CheckAfterResult.NeedConfirmation -> {
                // 需要用户确认
                requestCellularConfirmation(listOf(t)) {
                    // 先记录确认标记（即使用户在弹窗期间手动暂停也要保存）
                    updateTaskCellularConfirmed(taskId, true)
                    val resumed = enqueueWaiting(
                        taskId,
                        cellularConfirmed = true,
                        allowManualResume = true
                    )
                    if (resumed != null) {
                        DownloadLog.d(TAG, "任务恢复(用户确认): $taskId")
                    } else {
                        DownloadLog.d(TAG, "任务未恢复（已删除或已被手动暂停）: $taskId")
                    }
                    scheduleNext()
                }
            }

            is CheckAfterResult.Deny -> {
                // 保持暂停，仅更新暂停原因
                when (decision.reason) {
                    DenyReason.NO_NETWORK -> pauseTaskInternal(taskId, PauseReason.NETWORK_ERROR)
                    DenyReason.WIFI_ONLY_MODE -> {
                        pauseTaskInternal(taskId, PauseReason.WIFI_UNAVAILABLE)
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
        // 先通知引擎停止，防止记录缺失时残留协程继续写文件
        engine.cancel(taskId)
        scheduler?.remove(taskId)
        dispatcher.remove(taskId)

        val t = getTaskOrLoad(taskId)

        // 先从内存/流中移除，拦截迟到线程的更新
        removeTaskState(taskId, deleteRecord = true)

        // 删除是用户可见的强语义：同步落库，避免进程在异步队列执行前被杀导致任务复活
        repository?.let { repo ->
            runCatching {
                kotlinx.coroutines.runBlocking(dispatcherIO) { repo.delete(taskId) }
            }.onFailure {
                DownloadLog.w(TAG, "删除任务记录失败: $taskId", it)
            }
        }

        // 删文件（清洗文件名，防止历史脏数据造成路径穿越）
        if (deleteFile && t != null) {
            val safeName = FileUtils.sanitizeFileName(t.fileName)
            val shared = FileOwnership.isReferencedByOtherTask(
                t.id, t.filePath, t.fileName, InMemoryTaskStore.getAll()
            )
            if (shared) {
                DownloadLog.w(TAG, "最终文件仍被其他任务引用，跳过删除: ${t.id}")
            }
            runCatching {
                if (!shared) {
                    java.io.File(t.filePath, safeName).takeIf { it.exists() }?.delete()
                    java.io.File(t.filePath, "$safeName.part").takeIf { it.exists() }?.delete()
                }
                // 带任务 ID 的断点文件只属于当前任务
                java.io.File(t.filePath, "$safeName.${t.id}.part").takeIf { it.exists() }?.delete()
            }
        }

        // 调度下一个任务
        scheduleNext()
    }

    /**
     * 清理任务的临时文件，包含旧版本不带 taskId 的 `<name>.part`。
     * 仅用于任务已结束（完成/取消）的场景，避免误删可续传的断点。
     */
    private fun deleteTaskTempFiles(task: DownloadTask) {
        val safeName = FileUtils.sanitizeFileName(task.fileName)
        runCatching {
            java.io.File(task.filePath, "$safeName.${task.id}.part")
                .takeIf { it.exists() }?.delete()
            java.io.File(task.filePath, "$safeName.part")
                .takeIf { it.exists() }?.delete()
        }.onFailure {
            DownloadLog.w(TAG, "清理任务临时文件失败: ${task.id}", it)
        }
    }

    /**
     * 仅移除任务的内存状态与持久化记录，不处理最终文件。
     * 供删除、清理、保留策略复用。
     */
    internal fun removeTaskState(taskId: String, deleteRecord: Boolean = true) {
        InMemoryTaskStore.remove(taskId)
        taskProgressFlows.remove(taskId)
        lastEmissionMap.remove(taskId)
        lastProgressEmission.remove(taskId)
        taskHeaders.remove(taskId)
        taskMeta.remove(taskId)
        MultiThreadDownloadEngine.releaseFinalNames(taskId)
        if (deleteRecord) {
            deletedTaskIds.add(taskId)
            deletePersistedTask(taskId)
            scope.launch(dispatcherIO) { chunkManager?.deleteChunks(taskId) }
        }
        _tasksState.value = InMemoryTaskStore.getAll().sortedBy { it.createTime }
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
            // 正在运行的任务由引擎自己维护文件，跳过以免误判
            if (task.status == DownloadStatus.DOWNLOADING && isTaskRunning(task.id)) {
                return@forEach
            }

            val safeName = FileUtils.sanitizeFileName(task.fileName)
            val finalFile = java.io.File(task.filePath, safeName)
            when (task.status) {
                DownloadStatus.COMPLETED -> {
                    val valid = finalFile.exists() &&
                            (task.totalSize <= 0 || finalFile.length() == task.totalSize)
                    if (!valid) {
                        if (!finalFile.exists()) {
                            DownloadLog.d(
                                "DownloadManager",
                                "检测到已完成任务文件丢失: ${task.id} - ${task.fileName}"
                            )
                        } else {
                            DownloadLog.d(
                                "DownloadManager",
                                "已完成任务文件大小异常: ${task.id} - ${task.fileName}, " +
                                        "size=${finalFile.length()}, expected=${task.totalSize}"
                            )
                        }
                        // 只清理记录，不删除磁盘上的文件
                        deleteTask(task.id, deleteFile = false)
                        cleanedCount++
                    }
                }

                DownloadStatus.DOWNLOADING,
                DownloadStatus.PAUSED,
                DownloadStatus.WAITING,
                DownloadStatus.PENDING -> {
                    // 数据其实已经完整落盘：修复为完成状态，而不是删除记录
                    if (finalFile.exists() && task.totalSize > 0 && finalFile.length() == task.totalSize) {
                        DownloadLog.w(
                            "DownloadManager",
                            "任务数据已完整落盘，修复为完成状态: ${task.id} - ${task.fileName}"
                        )
                        updateTaskInternal(
                            task.copy(
                                status = DownloadStatus.COMPLETED,
                                progress = 100,
                                currentSize = task.totalSize,
                                speed = 0L,
                                updateTime = com.pichs.download.utils.TimeUtils.currentMicros()
                            )
                        )
                        return@forEach
                    }

                    val qualifiedTemp = java.io.File(task.filePath, "$safeName.${task.id}.part")
                    val legacyTemp = java.io.File(task.filePath, "$safeName.part")
                    val tempFile = when {
                        qualifiedTemp.exists() -> qualifiedTemp
                        legacyTemp.exists() -> legacyTemp
                        else -> null
                    }

                    if (tempFile != null && task.totalSize > 0 && tempFile.length() > task.totalSize) {
                        DownloadLog.d(
                            "DownloadManager",
                            "检测到临时文件损坏: ${task.id} - ${tempFile.name}, " +
                                    "size=${tempFile.length()}, expected=${task.totalSize}"
                        )
                        tempFile.delete()
                        resetInterruptedTask(task)
                        cleanedCount++
                    } else if (tempFile == null && task.currentSize > 0) {
                        DownloadLog.d(
                            "DownloadManager",
                            "检测到断点数据丢失，重置任务进度: ${task.id} - ${task.fileName}"
                        )
                        resetInterruptedTask(task)
                        cleanedCount++
                    }
                }

                else -> Unit
            }
        }

        if (cleanedCount > 0) {
            DownloadLog.d("DownloadManager", "任务验证完成，处理了 $cleanedCount 个异常任务")
        }

        cleanedCount
    }

    private fun resetInterruptedTask(task: DownloadTask) {
        if (InMemoryTaskStore.get(task.id) == null) return
        updateTaskInternal(
            task.copy(
                currentSize = 0,
                progress = 0,
                totalSize = 0,
                speed = 0L,
                updateTime = com.pichs.download.utils.TimeUtils.currentMicros()
            )
        )
        scope.launch(dispatcherIO) { chunkManager?.deleteChunks(task.id) }
    }

    // Flow: 获取单任务进度流（进度, 速度）
    // replay 保证新订阅者立即拿到最新进度；DROP_OLDEST 保证慢消费者永远看到最新值，
    // 不会因为缓冲满而丢掉最后的 100%。
    fun getProgressFlow(taskId: String) = taskProgressFlows.getOrPut(taskId) {
        MutableSharedFlow<Pair<Int, Long>>(
            replay = 1,
            extraBufferCapacity = 1,
            onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
        )
    }.asSharedFlow()

    private val lastEmissionMap = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private data class ProgressEmission(val progress: Int, val speed: Long)

    private val lastProgressEmission = java.util.concurrent.ConcurrentHashMap<String, ProgressEmission>()

    // 供引擎与管理器内部调用的进度派发
    internal fun emitProgress(task: DownloadTask, progress: Int, speed: Long) {
        // 相同进度和速度重复上报直接丢弃（完成/暂停路径可能重复调用）
        val lastValue = lastProgressEmission[task.id]
        if (lastValue != null && lastValue.progress == progress && lastValue.speed == speed) {
            return
        }
        val now = System.currentTimeMillis()
        val last = lastEmissionMap[task.id] ?: 0L

        // 限制发射频率：每200ms最多一次，100% 强制发射
        // 防止大规模并发下载时 System Server 死锁或 ANR
        if (now - last >= 200 || progress >= 100) {
            lastEmissionMap[task.id] = now
            lastProgressEmission[task.id] = ProgressEmission(progress, speed)
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
        val all = getAllTasks()
        val completed = all.filter { it.status == DownloadStatus.COMPLETED }
            .sortedBy { it.updateTime }

        var candidates = completed.asSequence()
        // beforeTime 为毫秒时间戳；任务内 updateTime 为微秒
        beforeTime?.let { millis ->
            candidates = candidates.filter {
                com.pichs.download.utils.TimeUtils.toMillis(it.updateTime) <= millis
            }
        }
        byTag?.let { tag ->
            candidates = candidates.filter { task ->
                task.extras?.split(',', ';', '|')
                    ?.map { it.trim() }
                    ?.any { it == tag } == true
            }
        }

        retention?.let { r ->
            if (r.keepDays > 0) {
                val thresholdMicros = (System.currentTimeMillis() - r.keepDays * 24L * 60L * 60L * 1000L) * 1000L
                candidates = candidates.filter { it.updateTime < thresholdMicros }
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
                val safeName = FileUtils.sanitizeFileName(task.fileName)
                val shared = FileOwnership.isReferencedByOtherTask(
                    task.id, task.filePath, task.fileName, InMemoryTaskStore.getAll()
                )
                kotlin.runCatching {
                    if (!shared) {
                        java.io.File(task.filePath, safeName).takeIf { it.exists() }?.delete()
                        java.io.File(task.filePath, "$safeName.part").takeIf { it.exists() }?.delete()
                    }
                    java.io.File(task.filePath, "$safeName.${task.id}.part")
                        .takeIf { it.exists() }?.delete()
                }
            }
            removeTaskState(task.id)
        }
    }
}
