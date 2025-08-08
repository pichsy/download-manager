# Android下载库开发思路文档

## 1. 项目概述

### 1.1 目标
构建一个企业级的Android下载库，满足应用市场级别的需求：
- **API简洁**: 一行代码开始下载
- **自动任务队列管理**: 智能调度和优先级控制
- **断点续传**: 支持网络中断后的自动恢复
- **多线程下载**: 提升下载速度和稳定性
- **下载加速**: 智能优化和CDN支持
- **高稳定性**: 完善的错误处理和恢复机制

### 1.2 技术栈
- **语言**: Kotlin + Java
- **架构**: MVVM + Repository Pattern
- **网络**: OkHttp + Retrofit
- **数据库**: Room + SQLite
- **并发**: Kotlin协程 + 线程池
- **生命周期**: Android Lifecycle Components
- **依赖注入**: Hilt (可选)

## 2. 架构设计

### 2.1 整体架构图
```
┌─────────────────────────────────────────────────────────────┐
│                    Presentation Layer                       │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐         │
│  │   Activity  │  │  Fragment   │  │   Service   │         │
│  └─────────────┘  └─────────────┘  └─────────────┘         │
├─────────────────────────────────────────────────────────────┤
│                      API Layer                              │
│  ┌─────────────────────────────────────────────────────────┐ │
│  │              DownloadManager (Facade)                   │ │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐     │ │
│  │  │   Builder   │  │   Task      │  │  Config     │     │ │
│  │  └─────────────┘  └─────────────┘  └─────────────┘     │ │
│  └─────────────────────────────────────────────────────────┘ │
├─────────────────────────────────────────────────────────────┤
│                   Business Logic Layer                      │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐         │
│  │ Dispatcher  │  │   Queue     │  │  Strategy   │         │
│  └─────────────┘  └─────────────┘  └─────────────┘         │
├─────────────────────────────────────────────────────────────┤
│                    Data Layer                               │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐         │
│  │ Repository  │  │   Cache     │  │  Database   │         │
│  └─────────────┘  └─────────────┘  └─────────────┘         │
├─────────────────────────────────────────────────────────────┤
│                   Network Layer                             │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐         │
│  │   OkHttp    │  │  MultiCall  │  │  Progress   │         │
│  └─────────────┘  └─────────────┘  └─────────────┘         │
└─────────────────────────────────────────────────────────────┘
```

### 2.2 核心组件设计

#### 2.2.1 DownloadManager (单例门面)
```kotlin
object DownloadManager {
    // 简洁的API入口
    fun download(url: String): DownloadBuilder
    fun create(): DownloadBuilder
    
    // 任务管理
    fun getTask(taskId: String): DownloadTask?
    fun getAllTasks(): List<DownloadTask>
    fun getRunningTasks(): List<DownloadTask>
    
    // 批量操作
    fun pauseAll(): DownloadManager
    fun resumeAll(): DownloadManager
    fun cancelAll(): DownloadManager
    
    // 配置管理
    fun config(block: DownloadConfig.() -> Unit)
}
```

#### 2.2.2 DownloadBuilder (流畅构建器)
```kotlin
class DownloadBuilder {
    fun url(url: String): DownloadBuilder
    fun to(path: String, fileName: String? = null): DownloadBuilder
    fun tag(tag: String): DownloadBuilder
    fun priority(priority: Int): DownloadBuilder
    fun headers(headers: Map<String, String>): DownloadBuilder
    
    // 回调设置
    fun onProgress(callback: (Int, Long) -> Unit): DownloadBuilder
    fun onComplete(callback: (File) -> Unit): DownloadBuilder
    fun onError(callback: (Throwable) -> Unit): DownloadBuilder
    
    // 生命周期绑定
    fun bindLifecycle(lifecycleOwner: LifecycleOwner): DownloadBuilder
    
    // 启动下载
    fun start(): DownloadTask
}
```

#### 2.2.3 DownloadTask (任务实体)
```kotlin
data class DownloadTask(
    val id: String,
    val url: String,
    val fileName: String,
    val filePath: String,
    val status: DownloadStatus,
    val progress: Int,
    val totalSize: Long,
    val currentSize: Long,
    val speed: Long,
    val priority: Int,
    val createTime: Long,
    val updateTime: Long
) {
    fun pause(): DownloadTask
    fun resume(): DownloadTask
    fun cancel(): DownloadTask
    fun addListener(listener: DownloadListener): DownloadTask
    fun removeListener(listener: DownloadListener): DownloadTask
}

enum class DownloadStatus {
    PENDING,    // 等待中
    DOWNLOADING, // 下载中
    PAUSED,     // 暂停
    COMPLETED,  // 完成
    FAILED,     // 失败
    CANCELLED   // 取消
}
```

## 3. 监听系统设计

### 3.1 监听器接口设计

#### 3.1.1 基础监听器接口
```kotlin
interface DownloadListener {
    fun onTaskStart(task: DownloadTask)
    fun onTaskProgress(task: DownloadTask, progress: Int, speed: Long)
    fun onTaskComplete(task: DownloadTask, file: File)
    fun onTaskError(task: DownloadTask, error: Throwable)
    fun onTaskPause(task: DownloadTask)
    fun onTaskResume(task: DownloadTask)
    fun onTaskCancel(task: DownloadTask)
}

// 简化版监听器，只关注进度
interface ProgressListener {
    fun onProgress(taskId: String, progress: Int, speed: Long)
    fun onComplete(taskId: String, file: File)
    fun onError(taskId: String, error: Throwable)
}

// 状态监听器，只关注状态变化
interface StatusListener {
    fun onStatusChanged(taskId: String, oldStatus: DownloadStatus, newStatus: DownloadStatus)
}
```

#### 3.1.2 监听器管理器
```kotlin
class DownloadListenerManager {
    private val globalListeners = mutableListOf<DownloadListener>()
    private val taskListeners = ConcurrentHashMap<String, MutableList<DownloadListener>>()
    private val progressListeners = ConcurrentHashMap<String, MutableList<ProgressListener>>()
    private val statusListeners = ConcurrentHashMap<String, MutableList<StatusListener>>()
    
    // 全局监听器管理
    fun addGlobalListener(listener: DownloadListener)
    fun removeGlobalListener(listener: DownloadListener)
    
    // 任务特定监听器管理
    fun addTaskListener(taskId: String, listener: DownloadListener)
    fun removeTaskListener(taskId: String, listener: DownloadListener)
    
    // 进度监听器管理
    fun addProgressListener(taskId: String, listener: ProgressListener)
    fun removeProgressListener(taskId: String, listener: ProgressListener)
    
    // 状态监听器管理
    fun addStatusListener(taskId: String, listener: StatusListener)
    fun removeStatusListener(taskId: String, listener: StatusListener)
    
    // 批量监听器管理
    fun addBatchListener(taskIds: List<String>, listener: DownloadListener)
    fun removeBatchListener(taskIds: List<String>, listener: DownloadListener)
    
    // 事件分发
    fun notifyTaskStart(task: DownloadTask)
    fun notifyTaskProgress(task: DownloadTask, progress: Int, speed: Long)
    fun notifyTaskComplete(task: DownloadTask, file: File)
    fun notifyTaskError(task: DownloadTask, error: Throwable)
    fun notifyStatusChanged(taskId: String, oldStatus: DownloadStatus, newStatus: DownloadStatus)
}
```

### 3.2 响应式监听系统

#### 3.2.1 Flow监听器
```kotlin
class DownloadFlowManager {
    private val _taskFlow = MutableSharedFlow<DownloadTask>()
    private val _progressFlow = MutableSharedFlow<DownloadProgress>()
    private val _statusFlow = MutableSharedFlow<DownloadStatusEvent>()
    
    val taskFlow: SharedFlow<DownloadTask> = _taskFlow.asSharedFlow()
    val progressFlow: SharedFlow<DownloadProgress> = _progressFlow.asSharedFlow()
    val statusFlow: SharedFlow<DownloadStatusEvent> = _statusFlow.asSharedFlow()
    
    // 特定任务的Flow
    fun getTaskFlow(taskId: String): Flow<DownloadTask>
    fun getProgressFlow(taskId: String): Flow<DownloadProgress>
    fun getStatusFlow(taskId: String): Flow<DownloadStatusEvent>
    
    // 批量任务Flow
    fun getBatchFlow(taskIds: List<String>): Flow<List<DownloadTask>>
    fun getBatchProgressFlow(taskIds: List<String>): Flow<List<DownloadProgress>>
}

data class DownloadProgress(
    val taskId: String,
    val progress: Int,
    val currentSize: Long,
    val totalSize: Long,
    val speed: Long,
    val estimatedTime: Long
)

data class DownloadStatusEvent(
    val taskId: String,
    val oldStatus: DownloadStatus,
    val newStatus: DownloadStatus,
    val timestamp: Long
)
```

#### 3.2.2 LiveData监听器
```kotlin
class DownloadLiveDataManager {
    private val _allTasks = MutableLiveData<List<DownloadTask>>()
    private val _runningTasks = MutableLiveData<List<DownloadTask>>()
    private val _completedTasks = MutableLiveData<List<DownloadTask>>()
    
    val allTasks: LiveData<List<DownloadTask>> = _allTasks
    val runningTasks: LiveData<List<DownloadTask>> = _runningTasks
    val completedTasks: LiveData<List<DownloadTask>> = _completedTasks
    
    // 特定任务的LiveData
    fun getTaskLiveData(taskId: String): LiveData<DownloadTask?>
    fun getProgressLiveData(taskId: String): LiveData<DownloadProgress?>
    
    // 批量任务的LiveData
    fun getBatchLiveData(taskIds: List<String>): LiveData<List<DownloadTask>>
}
```

### 3.3 UI监听器封装

#### 3.3.1 生命周期感知监听器
```kotlin
class LifecycleAwareListener(
    private val lifecycleOwner: LifecycleOwner,
    private val listener: DownloadListener
) : LifecycleObserver {
    
    init {
        lifecycleOwner.lifecycle.addObserver(this)
    }
    
    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    fun onDestroy() {
        // 自动移除监听器，避免内存泄漏
        DownloadManager.removeListener(listener)
    }
}

// 扩展函数，简化使用
fun DownloadManager.addLifecycleAwareListener(
    lifecycleOwner: LifecycleOwner,
    listener: DownloadListener
) {
    addGlobalListener(LifecycleAwareListener(lifecycleOwner, listener))
}
```

#### 3.3.2 防抖监听器
```kotlin
class DebouncedProgressListener(
    private val debounceTime: Long = 100, // 100ms防抖
    private val listener: ProgressListener
) : ProgressListener {
    
    private val scope = CoroutineScope(Dispatchers.Main)
    private var lastProgress = -1
    
    override fun onProgress(taskId: String, progress: Int, speed: Long) {
        if (progress == lastProgress) return
        
        scope.launch {
            delay(debounceTime)
            listener.onProgress(taskId, progress, speed)
            lastProgress = progress
        }
    }
    
    override fun onComplete(taskId: String, file: File) {
        listener.onComplete(taskId, file)
    }
    
    override fun onError(taskId: String, error: Throwable) {
        listener.onError(taskId, error)
    }
}
```

### 3.4 监听器使用示例

#### 3.4.1 基础监听器使用
```kotlin
// 全局监听所有下载
DownloadManager.addGlobalListener(object : DownloadListener {
    override fun onTaskStart(task: DownloadTask) {
        Log.d("Download", "开始下载: ${task.fileName}")
    }
    
    override fun onTaskProgress(task: DownloadTask, progress: Int, speed: Long) {
        Log.d("Download", "进度: $progress%, 速度: ${speed}KB/s")
    }
    
    override fun onTaskComplete(task: DownloadTask, file: File) {
        Log.d("Download", "下载完成: ${file.absolutePath}")
    }
    
    override fun onTaskError(task: DownloadTask, error: Throwable) {
        Log.e("Download", "下载失败: ${error.message}")
    }
    
    override fun onTaskPause(task: DownloadTask) {
        Log.d("Download", "下载暂停: ${task.fileName}")
    }
    
    override fun onTaskResume(task: DownloadTask) {
        Log.d("Download", "下载恢复: ${task.fileName}")
    }
    
    override fun onTaskCancel(task: DownloadTask) {
        Log.d("Download", "下载取消: ${task.fileName}")
    }
})
```

#### 3.4.2 特定任务监听
```kotlin
// 监听特定任务
val taskId = "my_download_task"
DownloadManager.addTaskListener(taskId, object : DownloadListener {
    override fun onTaskProgress(task: DownloadTask, progress: Int, speed: Long) {
        // 更新特定任务的UI
        updateProgressBar(progress)
        updateSpeedText(speed)
    }
    
    override fun onTaskComplete(task: DownloadTask, file: File) {
        // 处理特定任务完成
        installApk(file)
    }
})
```

#### 3.4.3 批量任务监听
```kotlin
// 监听多个任务
val taskIds = listOf("task1", "task2", "task3")
DownloadManager.addBatchListener(taskIds, object : DownloadListener {
    override fun onTaskProgress(task: DownloadTask, progress: Int, speed: Long) {
        // 更新批量下载的UI
        updateBatchProgress(taskIds, progress)
    }
})
```

#### 3.4.4 Flow响应式监听
```kotlin
class DownloadActivity : AppCompatActivity() {
    private val scope = lifecycleScope
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 监听所有任务
        scope.launch {
            DownloadManager.taskFlow.collect { task ->
                updateTaskList(task)
            }
        }
        
        // 监听特定任务进度
        scope.launch {
            DownloadManager.getProgressFlow("task_id")
                .debounce(100) // 防抖
                .collect { progress ->
                    updateProgressUI(progress)
                }
        }
        
        // 监听状态变化
        scope.launch {
            DownloadManager.statusFlow.collect { event ->
                handleStatusChange(event)
            }
        }
    }
}
```

#### 3.4.5 LiveData监听
```kotlin
class DownloadViewModel : ViewModel() {
    private val downloadManager = DownloadManager
    
    val allTasks = downloadManager.allTasks
    val runningTasks = downloadManager.runningTasks
    val completedTasks = downloadManager.completedTasks
    
    // 特定任务
    val currentTask = downloadManager.getTaskLiveData("task_id")
    val currentProgress = downloadManager.getProgressLiveData("task_id")
}

class DownloadFragment : Fragment() {
    private val viewModel: DownloadViewModel by viewModels()
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // 观察所有任务
        viewModel.allTasks.observe(viewLifecycleOwner) { tasks ->
            adapter.submitList(tasks)
        }
        
        // 观察运行中的任务
        viewModel.runningTasks.observe(viewLifecycleOwner) { tasks ->
            updateRunningTasksUI(tasks)
        }
        
        // 观察特定任务进度
        viewModel.currentProgress.observe(viewLifecycleOwner) { progress ->
            progress?.let { updateProgressBar(it) }
        }
    }
}
```

#### 3.4.6 防抖监听器使用
```kotlin
// 使用防抖监听器，避免UI频繁更新
DownloadManager.addProgressListener("task_id", 
    DebouncedProgressListener(100) { taskId, progress, speed ->
        // 这个回调最多100ms执行一次，避免UI卡顿
        updateProgressBar(progress)
        updateSpeedText(speed)
    }
)
```

#### 3.4.7 生命周期感知监听器
```kotlin
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 自动管理生命周期，Activity销毁时自动移除监听器
        DownloadManager.addLifecycleAwareListener(this, object : DownloadListener {
            override fun onTaskProgress(task: DownloadTask, progress: Int, speed: Long) {
                updateUI(progress, speed)
            }
            
            override fun onTaskComplete(task: DownloadTask, file: File) {
                showCompleteDialog(file)
            }
        })
    }
}
```

## 4. 核心功能实现

### 4.1 自动任务队列管理

#### 4.1.1 队列调度器
```kotlin
class DownloadQueueDispatcher {
    private val taskQueue = PriorityBlockingQueue<DownloadTask>()
    private val runningTasks = ConcurrentHashMap<String, DownloadTask>()
    private val maxConcurrentTasks = AtomicInteger(3)
    
    fun enqueue(task: DownloadTask)
    fun dequeue(): DownloadTask?
    fun remove(taskId: String)
    fun getRunningTasks(): List<DownloadTask>
    fun getWaitingTasks(): List<DownloadTask>
}
```

#### 4.1.2 优先级策略
```kotlin
enum class DownloadPriority {
    LOW(0),      // 后台下载
    NORMAL(1),   // 普通下载
    HIGH(2),     // 用户主动下载
    URGENT(3)    // 系统关键下载
}
```

### 4.2 断点续传实现

#### 4.2.1 断点管理器
```kotlin
class BreakpointManager {
    private val database: DownloadDatabase
    
    // 保存断点信息
    suspend fun saveBreakpoint(task: DownloadTask, chunks: List<DownloadChunk>)
    
    // 恢复断点信息
    suspend fun restoreBreakpoint(taskId: String): BreakpointInfo?
    
    // 更新断点进度
    suspend fun updateChunkProgress(taskId: String, chunkIndex: Int, downloaded: Long)
    
    // 清理断点信息
    suspend fun clearBreakpoint(taskId: String)
}
```

#### 4.2.2 分片下载
```kotlin
data class DownloadChunk(
    val taskId: String,
    val index: Int,
    val startByte: Long,
    val endByte: Long,
    val downloaded: Long,
    val status: ChunkStatus
)

class MultiThreadDownloader {
    fun downloadWithChunks(task: DownloadTask, chunkCount: Int = 3): Flow<DownloadProgress>
    
    private fun createChunks(totalSize: Long, chunkCount: Int): List<DownloadChunk>
    
    private fun downloadChunk(chunk: DownloadChunk): Flow<ChunkProgress>
}
```

### 4.3 多线程下载优化

#### 4.3.1 线程池管理
```kotlin
class DownloadThreadPool {
    private val ioExecutor = Executors.newFixedThreadPool(5)
    private val networkExecutor = Executors.newCachedThreadPool()
    
    fun executeIO(block: () -> Unit)
    fun executeNetwork(block: () -> Unit)
    fun shutdown()
}
```

#### 4.3.2 并发下载策略
```kotlin
class ConcurrentDownloadStrategy {
    // 根据文件大小动态调整线程数
    fun calculateOptimalThreadCount(fileSize: Long): Int {
        return when {
            fileSize < 1024 * 1024 -> 1      // 1MB以下单线程
            fileSize < 10 * 1024 * 1024 -> 2 // 10MB以下2线程
            fileSize < 100 * 1024 * 1024 -> 3 // 100MB以下3线程
            else -> 4                         // 100MB以上4线程
        }
    }
}
```

### 4.4 下载加速技术

#### 4.4.1 智能缓冲
```kotlin
class SmartBuffer {
    private val bufferSize = 8192 // 8KB缓冲区
    private val bufferPool = ArrayDeque<ByteArray>()
    
    fun getBuffer(): ByteArray
    fun recycleBuffer(buffer: ByteArray)
    fun adjustBufferSize(speed: Long)
}
```

#### 4.4.2 连接复用
```kotlin
class ConnectionManager {
    private val connectionPool = ConnectionPool(5, 5, TimeUnit.MINUTES)
    
    fun getConnection(url: String): HttpURLConnection
    fun releaseConnection(connection: HttpURLConnection)
}
```

#### 4.4.3 CDN优化
```kotlin
class CDNOptimizer {
    fun selectOptimalServer(url: String): String
    fun detectCDN(url: String): CDNType
    fun getFallbackUrls(url: String): List<String>
}
```

## 5. 数据持久化

### 5.1 数据库设计
```sql
-- 下载任务表
CREATE TABLE download_tasks (
    task_id TEXT PRIMARY KEY,
    url TEXT NOT NULL,
    file_name TEXT NOT NULL,
    file_path TEXT NOT NULL,
    total_size INTEGER DEFAULT 0,
    current_size INTEGER DEFAULT 0,
    status INTEGER DEFAULT 0,
    priority INTEGER DEFAULT 1,
    create_time INTEGER,
    update_time INTEGER,
    extra_data TEXT
);

-- 下载分片表
CREATE TABLE download_chunks (
    task_id TEXT,
    chunk_index INTEGER,
    start_byte INTEGER,
    end_byte INTEGER,
    downloaded INTEGER DEFAULT 0,
    status INTEGER DEFAULT 0,
    PRIMARY KEY (task_id, chunk_index)
);
```

### 5.2 Repository模式
```kotlin
class DownloadRepository(
    private val taskDao: DownloadTaskDao,
    private val chunkDao: DownloadChunkDao,
    private val cache: DownloadCache
) {
    suspend fun saveTask(task: DownloadTask)
    suspend fun getTask(taskId: String): DownloadTask?
    suspend fun updateTaskProgress(taskId: String, currentSize: Long, progress: Int)
    suspend fun saveChunks(taskId: String, chunks: List<DownloadChunk>)
    suspend fun getChunks(taskId: String): List<DownloadChunk>
}
```

## 6. 错误处理和恢复

### 6.1 错误分类
```kotlin
sealed class DownloadError : Exception() {
    object NetworkError : DownloadError()
    object FileSystemError : DownloadError()
    object ValidationError : DownloadError()
    object BusinessError : DownloadError()
    
    data class HttpError(val code: Int, val message: String) : DownloadError()
    data class StorageError(val reason: String) : DownloadError()
}
```

### 6.2 重试机制
```kotlin
class RetryManager {
    private val maxRetries = 3
    private val retryDelays = listOf(1000L, 3000L, 5000L) // 指数退避
    
    suspend fun <T> retry(
        maxAttempts: Int = maxRetries,
        delays: List<Long> = retryDelays,
        block: suspend () -> T
    ): T
}
```

### 6.3 自动恢复
```kotlin
class AutoRecoveryManager {
    fun handleNetworkRecovery()
    fun handleStorageRecovery()
    fun handleAppRestart()
}
```

## 7. 性能优化

### 7.1 内存优化
- **对象池**: 复用ByteArray、String等对象
- **弱引用**: 避免内存泄漏
- **分页加载**: 大量任务的分页显示
- **缓存策略**: LRU缓存任务信息

### 7.2 网络优化
- **连接池**: 复用HTTP连接
- **请求合并**: 批量处理相似请求
- **压缩传输**: 支持gzip压缩
- **预加载**: 智能预加载机制

### 7.3 存储优化
- **异步IO**: 非阻塞文件操作
- **批量写入**: 减少磁盘IO次数
- **索引优化**: 数据库查询优化
- **文件分片**: 大文件分片存储

## 8. 安全考虑

### 8.1 网络安全
- **HTTPS强制**: 所有下载使用HTTPS
- **证书验证**: 严格的证书验证
- **请求签名**: 防止请求篡改
- **内容验证**: MD5/SHA256校验

### 8.2 文件安全
- **路径验证**: 防止目录遍历攻击
- **权限检查**: 运行时权限处理
- **沙箱隔离**: 应用间数据隔离
- **病毒扫描**: 集成安全扫描

## 9. 测试策略

### 9.1 单元测试
```kotlin
class DownloadManagerTest {
    @Test
    fun testDownloadCreation()
    @Test
    fun testTaskQueueManagement()
    @Test
    fun testBreakpointResume()
    @Test
    fun testConcurrentDownloads()
}
```

### 9.2 集成测试
- **网络测试**: 各种网络环境下的表现
- **存储测试**: 不同存储设备的兼容性
- **并发测试**: 多任务并发下载
- **压力测试**: 大量任务的处理能力

### 9.3 性能测试
- **内存使用**: 长时间运行的内存占用
- **CPU使用**: 下载过程中的CPU消耗
- **网络效率**: 带宽利用率
- **电池消耗**: 对设备电池的影响

## 10. 使用示例

### 10.1 基础使用
```kotlin
// 最简单的下载
DownloadManager.download("https://example.com/app.apk")
    .to("/sdcard/Download/")
    .start()

// 带监听器的下载
DownloadManager.download("https://example.com/app.apk")
    .to("/sdcard/Download/", "myapp.apk")
    .priority(DownloadPriority.HIGH)
    .onProgress { progress, speed -> 
        updateProgressBar(progress)
        updateSpeedText(speed)
    }
    .onComplete { file ->
        installApk(file)
    }
    .onError { error ->
        showErrorDialog(error)
    }
    .bindLifecycle(this)
    .start()
```

### 10.2 批量下载
```kotlin
// 批量下载多个文件
val urls = listOf(
    "https://example.com/app1.apk",
    "https://example.com/app2.apk",
    "https://example.com/app3.apk"
)

urls.forEachIndexed { index, url ->
    DownloadManager.download(url)
        .to("/sdcard/Download/", "app$index.apk")
        .priority(DownloadPriority.NORMAL)
        .tag("batch_download")
        .start()
}
```

### 10.3 任务管理
```kotlin
// 获取所有任务
val allTasks = DownloadManager.getAllTasks()
val runningTasks = DownloadManager.getRunningTasks()

// 任务操作
val task = DownloadManager.getTask("task_id")
task?.pause()?.resume()?.cancel()

// 批量操作
DownloadManager.pauseAll()
DownloadManager.resumeAll()
DownloadManager.cancelAll()
```

## 11. 开发计划

### 11.1 第一阶段：核心架构 (2周)
- [ ] 设计核心架构和接口
- [ ] 实现DownloadManager单例
- [ ] 实现DownloadBuilder构建器
- [ ] 实现基础的任务管理

### 11.2 第二阶段：下载引擎 (3周)
- [ ] 实现多线程下载
- [ ] 实现断点续传
- [ ] 实现任务队列调度
- [ ] 实现进度跟踪

### 11.3 第三阶段：数据持久化 (2周)
- [ ] 设计数据库结构
- [ ] 实现Repository层
- [ ] 实现缓存机制
- [ ] 实现数据迁移

### 11.4 第四阶段：优化和测试 (2周)
- [ ] 性能优化
- [ ] 错误处理完善
- [ ] 单元测试编写
- [ ] 集成测试

### 11.5 第五阶段：文档和示例 (1周)
- [ ] API文档编写
- [ ] 使用示例完善
- [ ] 性能测试报告
- [ ] 发布准备

## 12. 风险评估

### 12.1 技术风险
- **网络兼容性**: 不同网络环境下的稳定性
- **存储权限**: Android权限系统的变化
- **系统限制**: 不同Android版本的兼容性
- **性能瓶颈**: 大量并发下载的性能问题

### 12.2 缓解措施
- **充分测试**: 在各种环境下进行测试
- **渐进式开发**: 分阶段实现和验证
- **监控机制**: 实时监控下载状态
- **降级策略**: 提供降级方案

## 13. 总结

这个Android下载库设计充分考虑了应用市场的需求，具有以下特点：

1. **API简洁**: 一行代码即可开始下载，支持流畅的链式调用
2. **功能完整**: 支持断点续传、多线程下载、任务队列管理
3. **性能优化**: 智能缓冲、连接复用、CDN优化
4. **稳定可靠**: 完善的错误处理和自动恢复机制
5. **易于使用**: 自动生命周期管理，减少内存泄漏
6. **高度可扩展**: 模块化设计，支持自定义扩展

通过这个架构，可以构建出一个企业级的下载库，满足应用市场等大型应用的需求。

## 14. 列表监听使用样例

### 14.1 单个任务监听示例

#### 14.1.1 基础单个任务监听
```kotlin
class SingleDownloadActivity : AppCompatActivity() {
    private lateinit var progressBar: ProgressBar
    private lateinit var speedText: TextView
    private lateinit var statusText: TextView
    private lateinit var pauseButton: Button
    private lateinit var resumeButton: Button
    
    private var currentTaskId: String? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_single_download)
        
        initViews()
        startDownload()
    }
    
    private fun initViews() {
        progressBar = findViewById(R.id.progress_bar)
        speedText = findViewById(R.id.speed_text)
        statusText = findViewById(R.id.status_text)
        pauseButton = findViewById(R.id.pause_button)
        resumeButton = findViewById(R.id.resume_button)
        
        pauseButton.setOnClickListener { pauseDownload() }
        resumeButton.setOnClickListener { resumeDownload() }
    }
    
    private fun startDownload() {
        val task = DownloadManager.download("https://example.com/app.apk")
            .to("/sdcard/Download/", "myapp.apk")
            .tag("single_download")
            .onProgress { progress, speed ->
                updateProgress(progress, speed)
            }
            .onComplete { file ->
                handleDownloadComplete(file)
            }
            .onError { error ->
                handleDownloadError(error)
            }
            .start()
        
        currentTaskId = task.id
        setupTaskListener(task.id)
    }
    
    private fun setupTaskListener(taskId: String) {
        // 监听特定任务的详细状态
        DownloadManager.addTaskListener(taskId, object : DownloadListener {
            override fun onTaskStart(task: DownloadTask) {
                runOnUiThread {
                    statusText.text = "开始下载"
                    pauseButton.isEnabled = true
                    resumeButton.isEnabled = false
                }
            }
            
            override fun onTaskProgress(task: DownloadTask, progress: Int, speed: Long) {
                runOnUiThread {
                    updateProgress(progress, speed)
                    updateStatus("下载中...")
                }
            }
            
            override fun onTaskPause(task: DownloadTask) {
                runOnUiThread {
                    updateStatus("已暂停")
                    pauseButton.isEnabled = false
                    resumeButton.isEnabled = true
                }
            }
            
            override fun onTaskResume(task: DownloadTask) {
                runOnUiThread {
                    updateStatus("下载中...")
                    pauseButton.isEnabled = true
                    resumeButton.isEnabled = false
                }
            }
            
            override fun onTaskComplete(task: DownloadTask, file: File) {
                runOnUiThread {
                    updateStatus("下载完成")
                    pauseButton.isEnabled = false
                    resumeButton.isEnabled = false
                    showCompleteDialog(file)
                }
            }
            
            override fun onTaskError(task: DownloadTask, error: Throwable) {
                runOnUiThread {
                    updateStatus("下载失败: ${error.message}")
                    pauseButton.isEnabled = false
                    resumeButton.isEnabled = true
                    showErrorDialog(error)
                }
            }
            
            override fun onTaskCancel(task: DownloadTask) {
                runOnUiThread {
                    updateStatus("已取消")
                    pauseButton.isEnabled = false
                    resumeButton.isEnabled = false
                }
            }
        })
    }
    
    private fun updateProgress(progress: Int, speed: Long) {
        progressBar.progress = progress
        speedText.text = "${speed}KB/s"
    }
    
    private fun updateStatus(status: String) {
        statusText.text = status
    }
    
    private fun pauseDownload() {
        currentTaskId?.let { taskId ->
            DownloadManager.getTask(taskId)?.pause()
        }
    }
    
    private fun resumeDownload() {
        currentTaskId?.let { taskId ->
            DownloadManager.getTask(taskId)?.resume()
        }
    }
    
    private fun handleDownloadComplete(file: File) {
        // 处理下载完成逻辑
        Toast.makeText(this, "下载完成: ${file.name}", Toast.LENGTH_SHORT).show()
    }
    
    private fun handleDownloadError(error: Throwable) {
        // 处理下载错误逻辑
        Toast.makeText(this, "下载失败: ${error.message}", Toast.LENGTH_SHORT).show()
    }
    
    private fun showCompleteDialog(file: File) {
        AlertDialog.Builder(this)
            .setTitle("下载完成")
            .setMessage("文件已下载到: ${file.absolutePath}")
            .setPositiveButton("安装") { _, _ ->
                installApk(file)
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    private fun showErrorDialog(error: Throwable) {
        AlertDialog.Builder(this)
            .setTitle("下载失败")
            .setMessage("错误信息: ${error.message}")
            .setPositiveButton("重试") { _, _ ->
                resumeDownload()
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    private fun installApk(file: File) {
        // 安装APK的逻辑
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setDataAndType(Uri.fromFile(file), "application/vnd.android.package-archive")
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
    }
}
```

#### 14.1.2 使用Flow监听单个任务
```kotlin
class SingleDownloadFlowActivity : AppCompatActivity() {
    private lateinit var progressBar: ProgressBar
    private lateinit var speedText: TextView
    private lateinit var statusText: TextView
    
    private val scope = lifecycleScope
    private var currentTaskId: String? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_single_download)
        
        initViews()
        startDownload()
    }
    
    private fun initViews() {
        progressBar = findViewById(R.id.progress_bar)
        speedText = findViewById(R.id.speed_text)
        statusText = findViewById(R.id.status_text)
    }
    
    private fun startDownload() {
        val task = DownloadManager.download("https://example.com/app.apk")
            .to("/sdcard/Download/", "myapp.apk")
            .start()
        
        currentTaskId = task.id
        observeTaskFlow(task.id)
    }
    
    private fun observeTaskFlow(taskId: String) {
        // 监听任务进度Flow
        scope.launch {
            DownloadManager.getProgressFlow(taskId)
                .debounce(100) // 防抖，避免UI频繁更新
                .collect { progress ->
                    updateProgressUI(progress)
                }
        }
        
        // 监听任务状态Flow
        scope.launch {
            DownloadManager.getStatusFlow(taskId)
                .collect { statusEvent ->
                    handleStatusChange(statusEvent)
                }
        }
    }
    
    private fun updateProgressUI(progress: DownloadProgress) {
        progressBar.progress = progress.progress
        speedText.text = "${progress.speed}KB/s"
        
        // 显示预计剩余时间
        val remainingTime = progress.estimatedTime
        if (remainingTime > 0) {
            val minutes = remainingTime / 60
            val seconds = remainingTime % 60
            statusText.text = "预计剩余时间: ${minutes}分${seconds}秒"
        }
    }
    
    private fun handleStatusChange(statusEvent: DownloadStatusEvent) {
        when (statusEvent.newStatus) {
            DownloadStatus.DOWNLOADING -> {
                statusText.text = "下载中..."
            }
            DownloadStatus.PAUSED -> {
                statusText.text = "已暂停"
            }
            DownloadStatus.COMPLETED -> {
                statusText.text = "下载完成"
                showCompleteNotification()
            }
            DownloadStatus.FAILED -> {
                statusText.text = "下载失败"
                showErrorNotification()
            }
            DownloadStatus.CANCELLED -> {
                statusText.text = "已取消"
            }
            else -> {
                statusText.text = "等待中..."
            }
        }
    }
    
    private fun showCompleteNotification() {
        // 显示完成通知
        Toast.makeText(this, "下载完成！", Toast.LENGTH_SHORT).show()
    }
    
    private fun showErrorNotification() {
        // 显示错误通知
        Toast.makeText(this, "下载失败，请重试", Toast.LENGTH_SHORT).show()
    }
}
```

### 14.2 下载列表监听示例

#### 14.2.1 RecyclerView列表监听（分开展示下载中和已完成）

```kotlin
// 下载任务数据类
data class DownloadTaskItem(
    val taskId: String,
    val fileName: String,
    val progress: Int,
    val speed: Long,
    val status: DownloadStatus,
    val totalSize: Long,
    val currentSize: Long,
    val createTime: Long
)

// 下载中任务适配器
class DownloadingTaskAdapter : ListAdapter<DownloadTaskItem, DownloadingTaskAdapter.ViewHolder>(DownloadDiffCallback()) {
    
    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val fileNameText: TextView = itemView.findViewById(R.id.file_name)
        val progressBar: ProgressBar = itemView.findViewById(R.id.progress_bar)
        val progressText: TextView = itemView.findViewById(R.id.progress_text)
        val speedText: TextView = itemView.findViewById(R.id.speed_text)
        val statusText: TextView = itemView.findViewById(R.id.status_text)
        val pauseButton: ImageButton = itemView.findViewById(R.id.pause_button)
        val resumeButton: ImageButton = itemView.findViewById(R.id.resume_button)
        val cancelButton: ImageButton = itemView.findViewById(R.id.cancel_button)
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_downloading_task, parent, false)
        return ViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        
        holder.fileNameText.text = item.fileName
        holder.progressBar.progress = item.progress
        holder.progressText.text = "${item.progress}%"
        holder.speedText.text = "${item.speed}KB/s"
        holder.statusText.text = getStatusText(item.status)
        
        // 根据状态控制按钮显示
        updateButtonVisibility(holder, item.status)
        
        // 设置按钮点击事件
        holder.pauseButton.setOnClickListener { 
            DownloadManager.getTask(item.taskId)?.pause()
        }
        holder.resumeButton.setOnClickListener { 
            DownloadManager.getTask(item.taskId)?.resume()
        }
        holder.cancelButton.setOnClickListener { 
            DownloadManager.getTask(item.taskId)?.cancel()
        }
    }
    
    private fun getStatusText(status: DownloadStatus): String {
        return when (status) {
            DownloadStatus.PENDING -> "等待中"
            DownloadStatus.DOWNLOADING -> "下载中"
            DownloadStatus.PAUSED -> "已暂停"
            DownloadStatus.FAILED -> "下载失败"
            else -> "未知状态"
        }
    }
    
    private fun updateButtonVisibility(holder: ViewHolder, status: DownloadStatus) {
        when (status) {
            DownloadStatus.DOWNLOADING -> {
                holder.pauseButton.visibility = View.VISIBLE
                holder.resumeButton.visibility = View.GONE
                holder.cancelButton.visibility = View.VISIBLE
            }
            DownloadStatus.PAUSED -> {
                holder.pauseButton.visibility = View.GONE
                holder.resumeButton.visibility = View.VISIBLE
                holder.cancelButton.visibility = View.VISIBLE
            }
            DownloadStatus.FAILED -> {
                holder.pauseButton.visibility = View.GONE
                holder.resumeButton.visibility = View.VISIBLE
                holder.cancelButton.visibility = View.VISIBLE
            }
            else -> {
                holder.pauseButton.visibility = View.GONE
                holder.resumeButton.visibility = View.GONE
                holder.cancelButton.visibility = View.VISIBLE
            }
        }
    }
}

// 已完成任务适配器
class CompletedTaskAdapter : ListAdapter<DownloadTaskItem, CompletedTaskAdapter.ViewHolder>(DownloadDiffCallback()) {
    
    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val fileNameText: TextView = itemView.findViewById(R.id.file_name)
        val fileSizeText: TextView = itemView.findViewById(R.id.file_size)
        val completeTimeText: TextView = itemView.findViewById(R.id.complete_time)
        val installButton: Button = itemView.findViewById(R.id.install_button)
        val deleteButton: ImageButton = itemView.findViewById(R.id.delete_button)
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_completed_task, parent, false)
        return ViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        
        holder.fileNameText.text = item.fileName
        holder.fileSizeText.text = formatFileSize(item.totalSize)
        holder.completeTimeText.text = formatTime(item.createTime)
        
        // 设置按钮点击事件
        holder.installButton.setOnClickListener {
            installApk(item)
        }
        holder.deleteButton.setOnClickListener {
            deleteTask(item.taskId)
        }
    }
    
    private fun formatFileSize(size: Long): String {
        return when {
            size < 1024 -> "${size}B"
            size < 1024 * 1024 -> "${size / 1024}KB"
            else -> "${size / (1024 * 1024)}MB"
        }
    }
    
    private fun formatTime(timestamp: Long): String {
        val date = Date(timestamp)
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return format.format(date)
    }
    
    private fun installApk(item: DownloadTaskItem) {
        val file = File(item.fileName)
        if (file.exists()) {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(Uri.fromFile(file), "application/vnd.android.package-archive")
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            holder.itemView.context.startActivity(intent)
        }
    }
    
    private fun deleteTask(taskId: String) {
        // 删除任务和文件
        DownloadManager.getTask(taskId)?.let { task ->
            val file = File(task.filePath, task.fileName)
            if (file.exists()) {
                file.delete()
            }
        }
    }
}

class DownloadDiffCallback : DiffUtil.ItemCallback<DownloadTaskItem>() {
    override fun areItemsTheSame(oldItem: DownloadTaskItem, newItem: DownloadTaskItem): Boolean {
        return oldItem.taskId == newItem.taskId
    }
    
    override fun areContentsTheSame(oldItem: DownloadTaskItem, newItem: DownloadTaskItem): Boolean {
        return oldItem == newItem
    }
}

// 下载列表Activity
class DownloadListActivity : AppCompatActivity() {
    private lateinit var downloadingRecyclerView: RecyclerView
    private lateinit var completedRecyclerView: RecyclerView
    private lateinit var downloadingAdapter: DownloadingTaskAdapter
    private lateinit var completedAdapter: CompletedTaskAdapter
    private lateinit var downloadingEmptyView: TextView
    private lateinit var completedEmptyView: TextView
    private lateinit var addButton: FloatingActionButton
    private lateinit var pauseAllButton: Button
    private lateinit var resumeAllButton: Button
    
    private val scope = lifecycleScope
    private val downloadingTasks = mutableListOf<DownloadTaskItem>()
    private val completedTasks = mutableListOf<DownloadTaskItem>()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_download_list)
        
        initViews()
        setupRecyclerViews()
        setupGlobalListener()
        loadExistingTasks()
    }
    
    private fun initViews() {
        downloadingRecyclerView = findViewById(R.id.downloading_recycler_view)
        completedRecyclerView = findViewById(R.id.completed_recycler_view)
        downloadingEmptyView = findViewById(R.id.downloading_empty_view)
        completedEmptyView = findViewById(R.id.completed_empty_view)
        addButton = findViewById(R.id.add_button)
        pauseAllButton = findViewById(R.id.pause_all_button)
        resumeAllButton = findViewById(R.id.resume_all_button)
        
        addButton.setOnClickListener { showAddDownloadDialog() }
        pauseAllButton.setOnClickListener { DownloadManager.pauseAll() }
        resumeAllButton.setOnClickListener { DownloadManager.resumeAll() }
    }
    
    private fun setupRecyclerViews() {
        // 设置下载中列表
        downloadingAdapter = DownloadingTaskAdapter()
        downloadingRecyclerView.adapter = downloadingAdapter
        downloadingRecyclerView.layoutManager = LinearLayoutManager(this)
        
        // 设置已完成列表
        completedAdapter = CompletedTaskAdapter()
        completedRecyclerView.adapter = completedAdapter
        completedRecyclerView.layoutManager = LinearLayoutManager(this)
    }
    
    private fun setupGlobalListener() {
        // 使用生命周期感知监听器，自动管理内存
        DownloadManager.addLifecycleAwareListener(this, object : DownloadListener {
            override fun onTaskStart(task: DownloadTask) {
                addToDownloadingList(task)
            }
            
            override fun onTaskProgress(task: DownloadTask, progress: Int, speed: Long) {
                updateDownloadingTaskProgress(task.id, progress, speed)
            }
            
            override fun onTaskComplete(task: DownloadTask, file: File) {
                moveToCompletedList(task)
                showCompleteNotification(task.fileName)
            }
            
            override fun onTaskError(task: DownloadTask, error: Throwable) {
                updateDownloadingTaskStatus(task.id, DownloadStatus.FAILED)
                showErrorNotification(task.fileName, error.message)
            }
            
            override fun onTaskPause(task: DownloadTask) {
                updateDownloadingTaskStatus(task.id, DownloadStatus.PAUSED)
            }
            
            override fun onTaskResume(task: DownloadTask) {
                updateDownloadingTaskStatus(task.id, DownloadStatus.DOWNLOADING)
            }
            
            override fun onTaskCancel(task: DownloadTask) {
                removeFromDownloadingList(task.id)
            }
        })
    }
    
    private fun loadExistingTasks() {
        // 加载已存在的下载任务
        val existingTasks = DownloadManager.getAllTasks()
        existingTasks.forEach { task ->
            when (task.status) {
                DownloadStatus.COMPLETED -> addToCompletedList(task)
                else -> addToDownloadingList(task)
            }
        }
        updateEmptyViews()
    }
    
    private fun addToDownloadingList(task: DownloadTask) {
        val taskItem = task.toTaskItem()
        
        val existingIndex = downloadingTasks.indexOfFirst { it.taskId == task.id }
        if (existingIndex >= 0) {
            downloadingTasks[existingIndex] = taskItem
            downloadingAdapter.notifyItemChanged(existingIndex)
        } else {
            downloadingTasks.add(taskItem)
            downloadingAdapter.submitList(downloadingTasks.toList())
        }
        
        updateEmptyViews()
    }
    
    private fun addToCompletedList(task: DownloadTask) {
        val taskItem = task.toTaskItem()
        
        val existingIndex = completedTasks.indexOfFirst { it.taskId == task.id }
        if (existingIndex >= 0) {
            completedTasks[existingIndex] = taskItem
            completedAdapter.notifyItemChanged(existingIndex)
        } else {
            completedTasks.add(0, taskItem) // 新完成的放在最前面
            completedAdapter.submitList(completedTasks.toList())
        }
        
        updateEmptyViews()
    }
    
    private fun moveToCompletedList(task: DownloadTask) {
        // 从下载中列表移除
        removeFromDownloadingList(task.id)
        // 添加到已完成列表
        addToCompletedList(task)
    }
    
    private fun removeFromDownloadingList(taskId: String) {
        val index = downloadingTasks.indexOfFirst { it.taskId == taskId }
        if (index >= 0) {
            downloadingTasks.removeAt(index)
            downloadingAdapter.submitList(downloadingTasks.toList())
            updateEmptyViews()
        }
    }
    
    private fun updateDownloadingTaskProgress(taskId: String, progress: Int, speed: Long) {
        val index = downloadingTasks.indexOfFirst { it.taskId == taskId }
        if (index >= 0) {
            downloadingTasks[index] = downloadingTasks[index].copy(
                progress = progress,
                speed = speed
            )
            downloadingAdapter.notifyItemChanged(index)
        }
    }
    
    private fun updateDownloadingTaskStatus(taskId: String, status: DownloadStatus) {
        val index = downloadingTasks.indexOfFirst { it.taskId == taskId }
        if (index >= 0) {
            downloadingTasks[index] = downloadingTasks[index].copy(status = status)
            downloadingAdapter.notifyItemChanged(index)
        }
    }
    
    private fun updateEmptyViews() {
        // 更新下载中列表空状态
        if (downloadingTasks.isEmpty()) {
            downloadingEmptyView.visibility = View.VISIBLE
            downloadingRecyclerView.visibility = View.GONE
        } else {
            downloadingEmptyView.visibility = View.GONE
            downloadingRecyclerView.visibility = View.VISIBLE
        }
        
        // 更新已完成列表空状态
        if (completedTasks.isEmpty()) {
            completedEmptyView.visibility = View.VISIBLE
            completedRecyclerView.visibility = View.GONE
        } else {
            completedEmptyView.visibility = View.GONE
            completedRecyclerView.visibility = View.VISIBLE
        }
        
        // 更新批量操作按钮状态
        val hasDownloadingTasks = downloadingTasks.isNotEmpty()
        pauseAllButton.isEnabled = hasDownloadingTasks
        resumeAllButton.isEnabled = hasDownloadingTasks
    }
    
    private fun showAddDownloadDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_download, null)
        val urlEditText = dialogView.findViewById<EditText>(R.id.url_edit_text)
        val fileNameEditText = dialogView.findViewById<EditText>(R.id.file_name_edit_text)
        
        AlertDialog.Builder(this)
            .setTitle("添加下载任务")
            .setView(dialogView)
            .setPositiveButton("开始下载") { _, _ ->
                val url = urlEditText.text.toString()
                val fileName = fileNameEditText.text.toString()
                
                if (url.isNotEmpty()) {
                    startNewDownload(url, fileName)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    private fun startNewDownload(url: String, fileName: String) {
        DownloadManager.download(url)
            .to("/sdcard/Download/", fileName)
            .tag("list_download")
            .start()
    }
    
    private fun showCompleteNotification(fileName: String) {
        Toast.makeText(this, "下载完成: $fileName", Toast.LENGTH_SHORT).show()
    }
    
    private fun showErrorNotification(fileName: String, errorMessage: String?) {
        Toast.makeText(this, "下载失败: $fileName - $errorMessage", Toast.LENGTH_SHORT).show()
    }
    
    private fun DownloadTask.toTaskItem(): DownloadTaskItem {
        return DownloadTaskItem(
            taskId = this.id,
            fileName = this.fileName,
            progress = this.progress,
            speed = this.speed,
            status = this.status,
            totalSize = this.totalSize,
            currentSize = this.currentSize,
            createTime = this.createTime
        )
    }
}
```

#### 14.2.2 使用LiveData监听列表
```kotlin
// ViewModel
class DownloadListViewModel : ViewModel() {
    private val _downloadTasks = MutableLiveData<List<DownloadTaskItem>>()
    val downloadTasks: LiveData<List<DownloadTaskItem>> = _downloadTasks
    
    private val _runningTasks = MutableLiveData<List<DownloadTaskItem>>()
    val runningTasks: LiveData<List<DownloadTaskItem>> = _runningTasks
    
    private val _completedTasks = MutableLiveData<List<DownloadTaskItem>>()
    val completedTasks: LiveData<List<DownloadTaskItem>> = _completedTasks
    
    init {
        loadTasks()
        observeDownloadManager()
    }
    
    private fun loadTasks() {
        val allTasks = DownloadManager.getAllTasks().map { it.toTaskItem() }
        _downloadTasks.value = allTasks
        updateTaskCategories(allTasks)
    }
    
    private fun observeDownloadManager() {
        // 观察所有任务变化
        DownloadManager.allTasks.observeForever { tasks ->
            val taskItems = tasks.map { it.toTaskItem() }
            _downloadTasks.value = taskItems
            updateTaskCategories(taskItems)
        }
        
        // 观察运行中的任务
        DownloadManager.runningTasks.observeForever { tasks ->
            val taskItems = tasks.map { it.toTaskItem() }
            _runningTasks.value = taskItems
        }
        
        // 观察已完成的任务
        DownloadManager.completedTasks.observeForever { tasks ->
            val taskItems = tasks.map { it.toTaskItem() }
            _completedTasks.value = taskItems
        }
    }
    
    private fun updateTaskCategories(tasks: List<DownloadTaskItem>) {
        _runningTasks.value = tasks.filter { it.status == DownloadStatus.DOWNLOADING }
        _completedTasks.value = tasks.filter { it.status == DownloadStatus.COMPLETED }
    }
    
    fun addDownload(url: String, fileName: String) {
        DownloadManager.download(url)
            .to("/sdcard/Download/", fileName)
            .tag("viewmodel_download")
            .start()
    }
    
    fun pauseTask(taskId: String) {
        DownloadManager.getTask(taskId)?.pause()
    }
    
    fun resumeTask(taskId: String) {
        DownloadManager.getTask(taskId)?.resume()
    }
    
    fun cancelTask(taskId: String) {
        DownloadManager.getTask(taskId)?.cancel()
    }
    
    fun pauseAllTasks() {
        DownloadManager.pauseAll()
    }
    
    fun resumeAllTasks() {
        DownloadManager.resumeAll()
    }
    
    fun cancelAllTasks() {
        DownloadManager.cancelAll()
    }
    
    private fun DownloadTask.toTaskItem(): DownloadTaskItem {
        return DownloadTaskItem(
            taskId = this.id,
            fileName = this.fileName,
            progress = this.progress,
            speed = this.speed,
            status = this.status,
            totalSize = this.totalSize,
            currentSize = this.currentSize
        )
    }
}

// Fragment
class DownloadListFragment : Fragment() {
    private lateinit var binding: FragmentDownloadListBinding
    private val viewModel: DownloadListViewModel by viewModels()
    private lateinit var adapter: DownloadListAdapter
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentDownloadListBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupRecyclerView()
        setupObservers()
        setupButtons()
    }
    
    private fun setupRecyclerView() {
        adapter = DownloadListAdapter()
        binding.recyclerView.adapter = adapter
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
    }
    
    private fun setupObservers() {
        // 观察所有下载任务
        viewModel.downloadTasks.observe(viewLifecycleOwner) { tasks ->
            adapter.submitList(tasks)
            updateEmptyView(tasks.isEmpty())
        }
        
        // 观察运行中的任务
        viewModel.runningTasks.observe(viewLifecycleOwner) { tasks ->
            updateRunningTasksCount(tasks.size)
        }
        
        // 观察已完成的任务
        viewModel.completedTasks.observe(viewLifecycleOwner) { tasks ->
            updateCompletedTasksCount(tasks.size)
        }
    }
    
    private fun setupButtons() {
        binding.addButton.setOnClickListener {
            showAddDownloadDialog()
        }
        
        binding.pauseAllButton.setOnClickListener {
            viewModel.pauseAllTasks()
        }
        
        binding.resumeAllButton.setOnClickListener {
            viewModel.resumeAllTasks()
        }
        
        binding.cancelAllButton.setOnClickListener {
            showCancelAllDialog()
        }
    }
    
    private fun updateEmptyView(isEmpty: Boolean) {
        binding.emptyView.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.recyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }
    
    private fun updateRunningTasksCount(count: Int) {
        binding.runningCountText.text = "运行中: $count"
    }
    
    private fun updateCompletedTasksCount(count: Int) {
        binding.completedCountText.text = "已完成: $count"
    }
    
    private fun showAddDownloadDialog() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_add_download, null)
        val urlEditText = dialogView.findViewById<EditText>(R.id.url_edit_text)
        val fileNameEditText = dialogView.findViewById<EditText>(R.id.file_name_edit_text)
        
        AlertDialog.Builder(requireContext())
            .setTitle("添加下载任务")
            .setView(dialogView)
            .setPositiveButton("开始下载") { _, _ ->
                val url = urlEditText.text.toString()
                val fileName = fileNameEditText.text.toString()
                
                if (url.isNotEmpty()) {
                    viewModel.addDownload(url, fileName)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    private fun showCancelAllDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("确认取消")
            .setMessage("确定要取消所有下载任务吗？")
            .setPositiveButton("确定") { _, _ ->
                viewModel.cancelAllTasks()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
```

#### 14.2.3 批量任务监听示例
```kotlin
class BatchDownloadActivity : AppCompatActivity() {
    private lateinit var progressBar: ProgressBar
    private lateinit var totalProgressText: TextView
    private lateinit var speedText: TextView
    private lateinit var statusText: TextView
    private lateinit var taskListView: ListView
    
    private val scope = lifecycleScope
    private val batchTaskIds = mutableListOf<String>()
    private val taskProgressMap = mutableMapOf<String, Int>()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_batch_download)
        
        initViews()
        startBatchDownload()
    }
    
    private fun initViews() {
        progressBar = findViewById(R.id.total_progress_bar)
        totalProgressText = findViewById(R.id.total_progress_text)
        speedText = findViewById(R.id.total_speed_text)
        statusText = findViewById(R.id.batch_status_text)
        taskListView = findViewById(R.id.task_list_view)
    }
    
    private fun startBatchDownload() {
        val urls = listOf(
            "https://example.com/app1.apk",
            "https://example.com/app2.apk",
            "https://example.com/app3.apk",
            "https://example.com/app4.apk"
        )
        
        urls.forEachIndexed { index, url ->
            val task = DownloadManager.download(url)
                .to("/sdcard/Download/", "app$index.apk")
                .tag("batch_download")
                .start()
            
            batchTaskIds.add(task.id)
            taskProgressMap[task.id] = 0
        }
        
        setupBatchListener()
    }
    
    private fun setupBatchListener() {
        // 监听批量任务
        DownloadManager.addBatchListener(batchTaskIds, object : DownloadListener {
            override fun onTaskStart(task: DownloadTask) {
                updateBatchStatus("开始下载: ${task.fileName}")
            }
            
            override fun onTaskProgress(task: DownloadTask, progress: Int, speed: Long) {
                taskProgressMap[task.id] = progress
                updateTotalProgress()
                updateTotalSpeed(speed)
            }
            
            override fun onTaskComplete(task: DownloadTask, file: File) {
                updateBatchStatus("完成: ${task.fileName}")
                checkBatchCompletion()
            }
            
            override fun onTaskError(task: DownloadTask, error: Throwable) {
                updateBatchStatus("失败: ${task.fileName} - ${error.message}")
            }
        })
        
        // 监听总体进度Flow
        scope.launch {
            DownloadManager.getBatchProgressFlow(batchTaskIds)
                .debounce(200) // 防抖
                .collect { progressList ->
                    updateBatchProgressUI(progressList)
                }
        }
    }
    
    private fun updateTotalProgress() {
        val totalProgress = taskProgressMap.values.sum() / taskProgressMap.size
        progressBar.progress = totalProgress
        totalProgressText.text = "$totalProgress%"
    }
    
    private fun updateTotalSpeed(speed: Long) {
        speedText.text = "${speed}KB/s"
    }
    
    private fun updateBatchStatus(status: String) {
        statusText.text = status
    }
    
    private fun updateBatchProgressUI(progressList: List<DownloadProgress>) {
        // 更新列表UI显示每个任务的进度
        val adapter = BatchTaskAdapter(progressList)
        taskListView.adapter = adapter
    }
    
    private fun checkBatchCompletion() {
        val completedCount = batchTaskIds.count { taskId ->
            val task = DownloadManager.getTask(taskId)
            task?.status == DownloadStatus.COMPLETED
        }
        
        if (completedCount == batchTaskIds.size) {
            updateBatchStatus("所有任务下载完成！")
            showBatchCompleteDialog()
        }
    }
    
    private fun showBatchCompleteDialog() {
        AlertDialog.Builder(this)
            .setTitle("批量下载完成")
            .setMessage("所有文件下载完成，是否安装？")
            .setPositiveButton("安装") { _, _ ->
                installAllApks()
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    private fun installAllApks() {
        batchTaskIds.forEach { taskId ->
            val task = DownloadManager.getTask(taskId)
            task?.let {
                val file = File(it.filePath, it.fileName)
                if (file.exists()) {
                    installApk(file)
                }
            }
        }
    }
    
    private fun installApk(file: File) {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setDataAndType(Uri.fromFile(file), "application/vnd.android.package-archive")
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
    }
}

// 批量任务适配器
class BatchTaskAdapter(private val progressList: List<DownloadProgress>) : BaseAdapter() {
    
    override fun getCount(): Int = progressList.size
    
    override fun getItem(position: Int): DownloadProgress = progressList[position]
    
    override fun getItemId(position: Int): Long = position.toLong()
    
    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView ?: LayoutInflater.from(parent?.context)
            .inflate(R.layout.item_batch_task, parent, false)
        
        val progress = getItem(position)
        
        val fileNameText = view.findViewById<TextView>(R.id.file_name)
        val progressBar = view.findViewById<ProgressBar>(R.id.progress_bar)
        val progressText = view.findViewById<TextView>(R.id.progress_text)
        val speedText = view.findViewById<TextView>(R.id.speed_text)
        
        fileNameText.text = "任务 ${position + 1}"
        progressBar.progress = progress.progress
        progressText.text = "${progress.progress}%"
        speedText.text = "${progress.speed}KB/s"
        
        return view
    }
}
```

这些示例展示了如何在不同的场景中使用监听系统：

1. **单个任务监听**：适合详情页面，可以监听特定任务的完整生命周期
2. **列表监听**：适合下载管理页面，可以同时监听多个任务的状态变化
3. **批量任务监听**：适合批量下载场景，可以监听一组任务的总体进度

每种监听方式都提供了完整的UI更新逻辑，确保用户界面能够实时反映下载状态的变化。 