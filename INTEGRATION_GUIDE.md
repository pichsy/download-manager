# Download Manager - Android 多线程下载管理库接入文档

一个功能强大、高性能的Android多线程下载管理库，支持断点续传、任务队列管理、优先级调度等企业级特性。

## 🚀 核心特性

### 📥 多线程分片下载
- **智能分片策略**：根据文件大小自动选择最优线程数（1MB以下单线程，10MB以下2线程，100MB以下3线程，100MB以上4线程）
- **并发下载**：多个分片同时下载，大幅提升下载速度
- **断点续传**：支持HTTP Range请求，网络中断后可从断点继续下载
- **文件完整性**：使用RandomAccessFile确保分片写入的正确性

### 🎯 任务管理
- **优先级调度**：支持URGENT、HIGH、NORMAL、LOW四个优先级
- **队列管理**：智能任务队列，支持并发控制
- **状态管理**：WAITING、PENDING、DOWNLOADING、PAUSED、COMPLETED、FAILED、CANCELLED七种状态
- **任务去重**：自动检测重复任务，避免重复下载

### 💾 数据持久化
- **Room数据库**：使用Android Room进行数据持久化
- **分片管理**：每个下载任务的分片信息独立存储
- **状态恢复**：应用重启后自动恢复历史任务状态
- **原子操作**：确保数据一致性

### 🔄 响应式监听
- **Flow监听器**：基于Kotlin Flow的响应式事件监听
- **生命周期绑定**：自动管理监听器生命周期
- **实时进度**：支持实时进度和速度更新
- **防抖机制**：避免过于频繁的UI更新

### 🛠️ 高级功能
- **存储管理**：智能存储空间监控和管理
- **缓存管理**：热任务缓存，提升查询性能
- **保留策略**：自动清理过期任务
- **网络适配**：支持移动网络和WiFi网络控制

## 📦 项目结构

```
download-manager/
├── app/                    # 示例应用
│   ├── src/main/java/
│   │   └── com/pichs/download/demo/
│   │       ├── MainActivity.kt          # 主界面示例
│   │       ├── DownloadManagerActivity.kt # 下载管理界面
│   │       └── AppDetailActivity.kt     # 应用详情界面
│   └── build.gradle.kts
├── base/                   # 基础库模块
│   ├── src/main/java/
│   │   └── com/pichs/shanhai/base/     # 基础工具类
│   └── build.gradle.kts
├── download/               # 核心下载库模块
│   ├── src/main/java/
│   │   └── com/pichs/download/
│   │       ├── core/                   # 核心下载引擎
│   │       │   ├── DownloadManager.kt         # 下载管理器
│   │       │   ├── MultiThreadDownloadEngine.kt # 多线程下载引擎
│   │       │   ├── DownloadRequestBuilder.kt   # 请求构建器
│   │       │   ├── FlowDownloadListener.kt     # Flow监听器
│   │       │   ├── AdvancedDownloadQueueDispatcher.kt # 高级队列调度器
│   │       │   └── DownloadScheduler.kt        # 下载调度器
│   │       ├── model/                  # 数据模型
│   │       │   ├── DownloadTask.kt            # 下载任务模型
│   │       │   ├── DownloadStatus.kt          # 下载状态枚举
│   │       │   ├── DownloadChunk.kt           # 分片模型
│   │       │   └── DownloadPriority.kt       # 优先级枚举
│   │       ├── store/                 # 数据存储
│   │       │   ├── db/                        # Room数据库
│   │       │   │   ├── DownloadDatabase.kt    # 数据库定义
│   │       │   │   ├── DownloadEntity.kt     # 任务实体
│   │       │   │   ├── DownloadChunkEntity.kt # 分片实体
│   │       │   │   ├── DownloadDao.kt        # 任务DAO
│   │       │   │   └── DownloadChunkDao.kt   # 分片DAO
│   │       │   ├── TaskRepository.kt         # 任务仓库
│   │       │   └── InMemoryTaskStore.kt      # 内存任务存储
│   │       ├── config/               # 配置类
│   │       │   └── DownloadConfig.kt         # 下载配置
│   │       └── utils/                # 工具类
│   │           ├── OkHttpHelper.kt           # HTTP工具
│   │           ├── FileUtils.kt              # 文件工具
│   │           └── DownloadLog.kt            # 日志工具
│   └── build.gradle.kts
└── build.gradle.kts
```

## 🛠️ 快速开始 - 接入文档

### 📋 依赖配置

在项目的 `build.gradle.kts` 中添加依赖：

```kotlin
dependencies {
    implementation(project(":download"))
    implementation(project(":base"))
}
```

### 🚀 基础接入

#### 1. 初始化下载管理器

在 `Application` 类中初始化：

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // 初始化下载管理器
        DownloadManager.init(this)
        
        // 可选：配置下载参数
        DownloadManager.config {
            maxConcurrentTasks = 3        // 最大并发下载数
            connectTimeoutSec = 60        // 连接超时
            readTimeoutSec = 60          // 读取超时
            writeTimeoutSec = 60         // 写入超时
            allowMetered = true          // 允许移动网络下载
            callbackOnMain = true        // 回调在主线程
        }
    }
}
```

#### 2. 创建下载任务

**基础下载：**
```kotlin
val task = DownloadManager.download("https://example.com/file.apk")
    .path(getExternalFilesDir(null)?.absolutePath ?: "")
    .fileName("app.apk")
    .start()
```

**带优先级的下载：**
```kotlin
// 高优先级下载（用户主动下载）
val task = DownloadManager.downloadWithPriority(url, DownloadPriority.HIGH)
    .path(downloadPath)
    .fileName("important.apk")
    .start()

// 紧急下载
val urgentTask = DownloadManager.downloadUrgent(url)
    .path(downloadPath)
    .fileName("critical.apk")
    .start()

// 后台下载
val backgroundTask = DownloadManager.downloadBackground(url)
    .path(downloadPath)
    .fileName("background.apk")
    .start()
```

**带自定义请求头的下载：**
```kotlin
val task = DownloadManager.download(url)
    .path(downloadPath)
    .fileName("app.apk")
    .headers(mapOf(
        "Authorization" to "Bearer token",
        "User-Agent" to "MyApp/1.0"
    ))
    .start()
```

#### 3. 任务管理

```kotlin
// 暂停任务
DownloadManager.pause(taskId)

// 恢复任务
DownloadManager.resume(taskId)

// 取消任务
DownloadManager.cancel(taskId)

// 删除任务
DownloadManager.deleteTask(taskId, deleteFile = true)

// 批量操作
DownloadManager.pauseAll()
DownloadManager.resumeAll()
DownloadManager.cancelAll()
```

#### 4. 查询任务

```kotlin
// 获取所有任务
val allTasks = DownloadManager.getAllTasks()

// 获取特定任务
val task = DownloadManager.getTask(taskId)

// 获取正在下载的任务
val runningTasks = DownloadManager.getRunningTasks()

// 按优先级查询
val urgentTasks = DownloadManager.getUrgentTasks()
val normalTasks = DownloadManager.getNormalTasks()
val backgroundTasks = DownloadManager.getBackgroundTasks()
```

### 📱 响应式监听

#### Flow监听器使用

```kotlin
class MainActivity : AppCompatActivity() {
    
    private val flowListener = DownloadManager.flowListener
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 绑定生命周期监听
        bindFlowListener()
    }
    
    private fun bindFlowListener() {
        flowListener.bindToLifecycle(
            lifecycleOwner = this,
            onTaskProgress = { task, progress, speed ->
                // 更新进度显示
                updateProgress(task.id, progress, speed)
            },
            onTaskComplete = { task, file ->
                // 下载完成
                showDownloadComplete(task, file)
            },
            onTaskError = { task, error ->
                // 下载失败
                showDownloadError(task, error)
            },
            onTaskPaused = { task ->
                // 任务暂停
                updateTaskStatus(task)
            },
            onTaskResumed = { task ->
                // 任务恢复
                updateTaskStatus(task)
            },
            onTaskCancelled = { task ->
                // 任务取消
                updateTaskStatus(task)
            }
        )
    }
}
```

#### 手动监听特定任务

```kotlin
// 监听特定任务状态
flowListener.observeTaskStatus(taskId, lifecycleScope) { task ->
    task?.let { updateUI(it) }
}

// 监听特定任务进度
flowListener.observeTaskProgress(taskId, lifecycleScope) { progress, speed ->
    updateProgressBar(progress)
    updateSpeedText(speed)
}
```

#### 监听任务列表

```kotlin
// 监听所有任务
flowListener.observeAllTasks().collect { tasks ->
    updateTaskList(tasks)
}

// 监听正在下载的任务
flowListener.observeRunningTasks().collect { tasks ->
    updateDownloadingList(tasks)
}

// 监听已完成的任务
flowListener.observeCompletedTasks().collect { tasks ->
    updateCompletedList(tasks)
}
```

### 🎯 高级功能

#### 存储管理

```kotlin
// 检查存储空间
val storageInfo = DownloadManager.getStorageInfo()
val isLowStorage = DownloadManager.isLowStorage()

// 获取推荐下载路径
val recommendedPath = DownloadManager.getRecommendedPath()

// 检查路径是否允许
val isPathAllowed = DownloadManager.isPathAllowed("/path/to/download")
```

#### 缓存管理

```kotlin
// 获取缓存统计
val cacheStats = DownloadManager.getCacheStats()

// 获取热任务（最近访问）
val hotTasks = DownloadManager.getHotTasks()

// 获取冷任务（较少访问）
val coldTasks = DownloadManager.getColdTasks()
```

#### 保留策略

```kotlin
// 执行清理策略
DownloadManager.executeRetentionPolicy()

// 获取保留统计
val retentionStats = DownloadManager.getRetentionStats()

// 手动清理已完成任务
DownloadManager.cleanCompleted(
    deleteFiles = false,
    beforeTime = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000, // 7天前
    limit = 50
)
```

### 🔧 配置选项

#### DownloadConfig 配置

```kotlin
DownloadManager.config {
    // 并发控制
    maxConcurrentTasks = 3
    
    // 超时设置
    connectTimeoutSec = 60
    readTimeoutSec = 60
    writeTimeoutSec = 60
    
    // 网络控制
    allowMetered = true
    
    // 回调线程
    callbackOnMain = true
    
    // 文件校验（可选）
    checksum = Checksum(
        type = Checksum.Type.MD5,
        value = "expected_md5_hash",
        onFail = Checksum.OnFail.Retry
    )
    
    // 保留策略
    retention = Retention(
        keepDays = 30,                    // 保留30天
        keepLatestCompleted = 100         // 最多保留100个已完成任务
    )
}
```

#### 优先级说明

```kotlin
enum class DownloadPriority(val value: Int) {
    LOW(0),      // 后台下载 - 低优先级
    NORMAL(1),   // 普通下载 - 默认优先级
    HIGH(2),     // 用户主动下载 - 高优先级
    URGENT(3)    // 系统关键下载 - 最高优先级
}
```

### 📋 权限配置

在 `AndroidManifest.xml` 中添加必要权限：

```xml
<!-- 网络权限 -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

<!-- 存储权限 -->
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />

<!-- Android 11+ 存储权限 -->
<uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />
```

### 🎨 UI集成建议

#### RecyclerView适配器示例

```kotlin
class DownloadTaskAdapter : RecyclerView.Adapter<DownloadTaskViewHolder>() {
    
    private val tasks = mutableListOf<DownloadTask>()
    private val flowListener = DownloadManager.flowListener
    
    fun bindToLifecycle(lifecycleOwner: LifecycleOwner) {
        flowListener.bindToLifecycle(
            lifecycleOwner = lifecycleOwner,
            onTaskProgress = { task, progress, speed ->
                updateTaskProgress(task.id, progress, speed)
            },
            onTaskComplete = { task, file ->
                updateTaskStatus(task)
            },
            onTaskError = { task, error ->
                updateTaskStatus(task)
            }
        )
    }
    
    private fun updateTaskProgress(taskId: String, progress: Int, speed: Long) {
        val index = tasks.indexOfFirst { it.id == taskId }
        if (index >= 0) {
            tasks[index] = tasks[index].copy(progress = progress, speed = speed)
            notifyItemChanged(index)
        }
    }
    
    private fun updateTaskStatus(task: DownloadTask) {
        val index = tasks.indexOfFirst { it.id == task.id }
        if (index >= 0) {
            tasks[index] = task
            notifyItemChanged(index)
        }
    }
}
```

## 📱 监听列表刷新处理建议

基于项目中的实际使用经验，以下是监听列表刷新的最佳实践：

### 🎯 核心原则

1. **生命周期绑定**：始终使用 `bindToLifecycle()` 确保监听器与Activity/Fragment生命周期绑定
2. **防抖机制**：对频繁的进度更新进行防抖处理，避免UI卡顿
3. **状态同步**：保持本地数据与下载任务状态同步
4. **分组管理**：将任务按状态分组（下载中、已完成、失败等）

### 🔄 列表刷新策略

#### 1. 全量刷新 vs 增量更新

**全量刷新场景：**
- 任务状态发生跨组变化（如从下载中变为已完成）
- 任务数量变化（新增、删除任务）
- 页面首次加载

**增量更新场景：**
- 任务进度变化
- 任务状态在同一组内变化（如从等待变为下载中）

#### 2. 防抖处理

```kotlin
class DownloadListManager {
    
    // 进度更新防抖
    private val lastProgressUpdateTimeMap = mutableMapOf<String, Long>()
    private val progressUpdateInterval = 300L // 300ms防抖间隔
    
    private fun updateTaskProgress(taskId: String, progress: Int, speed: Long) {
        val now = System.currentTimeMillis()
        val lastUpdateTime = lastProgressUpdateTimeMap[taskId] ?: 0L
        
        if (now - lastUpdateTime < progressUpdateInterval) {
            return // 跳过此次更新
        }
        
        lastProgressUpdateTimeMap[taskId] = now
        // 执行实际的UI更新
        performProgressUpdate(taskId, progress, speed)
    }
}
```

#### 3. 状态分组管理

```kotlin
class TaskListManager {
    
    private val downloading = mutableListOf<DownloadTask>()
    private val completed = mutableListOf<DownloadTask>()
    private val failed = mutableListOf<DownloadTask>()
    
    fun updateTask(task: DownloadTask) {
        val shouldBeInDownloading = task.status in listOf(
            DownloadStatus.DOWNLOADING,
            DownloadStatus.PAUSED,
            DownloadStatus.PENDING,
            DownloadStatus.WAITING
        )
        val shouldBeInCompleted = task.status == DownloadStatus.COMPLETED
        val shouldBeInFailed = task.status == DownloadStatus.FAILED
        
        val crossGroup = (downloading.contains(task) && shouldBeInCompleted) ||
                        (completed.contains(task) && shouldBeInDownloading)
        
        if (crossGroup) {
            // 跨组变化，需要全量刷新
            refreshAllLists()
        } else {
            // 同组内变化，增量更新
            updateSingleTask(task)
        }
    }
    
    private fun updateSingleTask(task: DownloadTask) {
        when {
            downloading.any { it.id == task.id } -> {
                val index = downloading.indexOfFirst { it.id == task.id }
                if (index >= 0) {
                    downloading[index] = task
                    downloadingAdapter.notifyItemChanged(index)
                }
            }
            completed.any { it.id == task.id } -> {
                val index = completed.indexOfFirst { it.id == task.id }
                if (index >= 0) {
                    completed[index] = task
                    completedAdapter.notifyItemChanged(index)
                }
            }
            // ... 其他分组
        }
    }
}
```

### 🎨 UI更新最佳实践

#### 1. 按钮状态管理

```kotlin
private fun bindButtonUI(task: DownloadTask) {
    when (task.status) {
        DownloadStatus.DOWNLOADING -> {
            button.text = "${task.progress}%"
            button.setProgress(task.progress)
            button.isEnabled = true
        }
        DownloadStatus.PAUSED -> {
            button.text = "继续"
            button.setProgress(task.progress)
            button.isEnabled = true
        }
        DownloadStatus.WAITING, DownloadStatus.PENDING -> {
            button.text = "等待中"
            button.isEnabled = true
        }
        DownloadStatus.COMPLETED -> {
            button.text = "安装"
            button.setProgress(100)
            button.isEnabled = true
        }
        DownloadStatus.FAILED -> {
            button.text = "重试"
            button.isEnabled = true
        }
        else -> {
            button.text = "下载"
            button.setProgress(0)
            button.isEnabled = true
        }
    }
}
```

#### 2. 进度显示优化

```kotlin
private fun updateProgressDisplay(task: DownloadTask, progress: Int, speed: Long) {
    // 更新进度条
    progressBar.progress = progress
    
    // 更新速度显示（格式化）
    speedText.text = formatSpeed(speed)
    
    // 更新剩余时间
    val remainingTime = calculateRemainingTime(task.totalSize, task.currentSize, speed)
    timeText.text = formatTime(remainingTime)
}

private fun formatSpeed(bytesPerSecond: Long): String {
    return when {
        bytesPerSecond >= 1024 * 1024 -> "${bytesPerSecond / (1024 * 1024)} MB/s"
        bytesPerSecond >= 1024 -> "${bytesPerSecond / 1024} KB/s"
        else -> "$bytesPerSecond B/s"
    }
}
```

#### 3. 错误处理

```kotlin
private fun handleDownloadError(task: DownloadTask, error: DownloadError) {
    when (error) {
        DownloadError.NetworkError -> {
            showErrorDialog("网络错误", "请检查网络连接后重试")
        }
        DownloadError.StorageError -> {
            showErrorDialog("存储空间不足", "请清理存储空间后重试")
        }
        DownloadError.FileError -> {
            showErrorDialog("文件错误", "文件可能已损坏，请重新下载")
        }
        else -> {
            showErrorDialog("下载失败", "未知错误，请重试")
        }
    }
}
```

### 🚀 性能优化建议

#### 1. 内存管理

```kotlin
class DownloadActivity : AppCompatActivity() {
    
    private var flowListener: FlowDownloadListener? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 延迟初始化监听器
        flowListener = DownloadManager.flowListener
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Flow监听器会自动管理生命周期，无需手动清理
        flowListener = null
    }
}
```

#### 2. 列表优化

```kotlin
// 使用 DiffUtil 优化列表更新
class DownloadTaskDiffCallback : DiffUtil.Callback() {
    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        return oldList[oldItemPosition].id == newList[newItemPosition].id
    }
    
    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        val oldTask = oldList[oldItemPosition]
        val newTask = newList[newItemPosition]
        return oldTask.status == newTask.status && 
               oldTask.progress == newTask.progress &&
               oldTask.speed == newTask.speed
    }
}
```

#### 3. 网络状态监听

**重要说明**：下载库不再内部监听网络状态，将网络监听的责任交给接入者，这样更安全、更灵活。

```kotlin
// 方式1：使用ConnectivityManager.NetworkCallback（推荐）
private fun setupNetworkListener() {
    val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // 网络恢复，通知下载管理器恢复网络异常暂停的任务
            DownloadManager.onNetworkRestored()
        }
        
        override fun onLost(network: Network) {
            // 网络断开，只暂停正在下载的任务，不影响用户手动暂停的任务
            DownloadManager.pauseAllForNetworkError()
        }
    }
    
    connectivityManager.registerDefaultNetworkCallback(networkCallback)
}

// 方式2：使用广播接收器
class NetworkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == ConnectivityManager.CONNECTIVITY_ACTION) {
            val connectivityManager = context?.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            val isConnected = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            
            if (isConnected) {
                // 网络恢复，通知下载管理器
                DownloadManager.onNetworkRestored()
            }
        }
    }
}

// 方式3：使用第三方网络监听库
NetStateReceiver(
    onNetConnected = { isWifi ->
        // 网络恢复
        DownloadManager.onNetworkRestored()
    },
    onNetDisConnected = {
        // 网络断开，只暂停正在下载的任务，不影响用户手动暂停的任务
        DownloadManager.pauseAllForNetworkError()
    }
).register(this)
```

**网络状态检查API**：

```kotlin
// 检查网络是否可用
val isNetworkAvailable = DownloadManager.isNetworkAvailable()

// 检查是否连接到WiFi
val isWifiAvailable = DownloadManager.isWifiAvailable()

// 检查是否连接到移动网络
val isCellularAvailable = DownloadManager.isCellularAvailable()

// 获取当前网络类型
val networkType = DownloadManager.getNetworkType() // "WiFi", "Cellular", "Ethernet", "Unknown", "No Network"

// 检查是否为计费网络
val isMetered = DownloadManager.isMeteredNetwork()
```

**网络恢复API说明**：

```kotlin
// 检查是否有因网络异常暂停的任务
val count = DownloadManager.getNetworkPausedTaskCount()

// 获取因网络异常暂停的任务列表
val pausedTasks = DownloadManager.getNetworkPausedTasks()

// 手动触发网络恢复检查
DownloadManager.onNetworkRestored()
```

### ⚠️ 重要：暂停原因的区别

下载管理器支持多种暂停原因，正确区分它们很重要：

```kotlin
// 1. 用户手动暂停（默认）
DownloadManager.pause(taskId) // pauseReason = USER_MANUAL

// 2. 网络异常暂停
DownloadManager.pauseTask(taskId, PauseReason.NETWORK_ERROR)

// 3. 存储空间不足暂停
DownloadManager.pauseTask(taskId, PauseReason.STORAGE_FULL)

// 4. 批量操作
DownloadManager.pauseAll() // 所有任务标记为 USER_MANUAL
DownloadManager.pauseAll(PauseReason.NETWORK_ERROR) // 所有任务标记为 NETWORK_ERROR
DownloadManager.pauseAllForNetworkError() // 只暂停正在下载的任务，标记为 NETWORK_ERROR
```

**网络恢复时的行为**：
- `DownloadManager.onNetworkRestored()` 只会恢复 `PauseReason.NETWORK_ERROR` 的任务
- 用户手动暂停的任务不会被自动恢复
- 存储空间不足暂停的任务不会被网络恢复影响

## 🌐 网络监听最佳实践

### 🎯 设计原则

1. **职责分离**：下载库专注于下载功能，网络监听由应用层负责
2. **安全性**：避免下载库内部注册广播接收器，减少权限需求
3. **灵活性**：接入者可以根据业务需求自定义网络监听逻辑
4. **可控性**：接入者完全控制何时恢复网络异常暂停的任务

### 🔧 实现方式对比

| 方式 | 优点 | 缺点 | 适用场景 |
|------|------|------|----------|
| NetworkCallback | 实时性好，API现代 | 需要API 21+ | 现代应用推荐 |
| BroadcastReceiver | 兼容性好 | 性能较差，已废弃 | 兼容老版本 |
| 第三方库 | 功能丰富 | 增加依赖 | 复杂业务需求 |

### 📱 完整示例

```kotlin
class DownloadActivity : AppCompatActivity() {
    
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 初始化下载管理器
        DownloadManager.init(this)
        
        // 设置网络监听
        setupNetworkListener()
        
        // 绑定下载监听
        bindDownloadListener()
    }
    
    private fun setupNetworkListener() {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread {
                    // 网络恢复，检查并恢复网络异常暂停的任务
                    lifecycleScope.launch {
                        val pausedCount = DownloadManager.getNetworkPausedTaskCount()
                        if (pausedCount > 0) {
                            DownloadManager.onNetworkRestored()
                            showToast("网络恢复，已自动恢复 $pausedCount 个下载任务")
                        }
                    }
                }
            }
            
            override fun onLost(network: Network) {
                runOnUiThread {
                    // 网络断开，暂停所有下载任务
                    DownloadManager.pauseAll()
                    showToast("网络断开，已暂停所有下载任务")
                }
            }
            
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                // 网络能力变化，可以根据网络类型调整下载策略
                val isWifi = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                val isCellular = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                
                if (isWifi) {
                    // WiFi网络，可以增加并发数
                    DownloadManager.config {
                        maxConcurrentTasks = 5
                    }
                } else if (isCellular) {
                    // 移动网络，减少并发数
                    DownloadManager.config {
                        maxConcurrentTasks = 2
                    }
                }
            }
        }
        
        connectivityManager.registerDefaultNetworkCallback(networkCallback!!)
    }
    
    private fun bindDownloadListener() {
        DownloadManager.flowListener.bindToLifecycle(
            lifecycleOwner = this,
            onTaskProgress = { task, progress, speed ->
                updateTaskProgress(task.id, progress, speed)
            },
            onTaskComplete = { task, file ->
                showToast("下载完成：${task.fileName}")
            },
            onTaskError = { task, error ->
                when (error) {
                    DownloadError.NetworkError -> {
                        // 网络错误，任务会自动暂停，等待网络恢复
                        showToast("网络错误，任务已暂停")
                    }
                    else -> {
                        showToast("下载失败：${task.fileName}")
                    }
                }
            }
        )
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // 取消网络监听
        networkCallback?.let { callback ->
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }
}
```

### 🚀 高级用法

#### 1. 智能网络恢复

```kotlin
class SmartNetworkManager {
    
    private var lastNetworkLostTime = 0L
    private var networkLostCount = 0
    
    fun onNetworkRestored() {
        val now = System.currentTimeMillis()
        val timeSinceLastLost = now - lastNetworkLostTime
        
        // 如果网络频繁断开，延迟恢复
        if (timeSinceLastLost < 5000) {
            networkLostCount++
            if (networkLostCount > 3) {
                // 延迟10秒恢复，避免频繁重试
                Handler(Looper.getMainLooper()).postDelayed({
                    DownloadManager.onNetworkRestored()
                }, 10000)
                return
            }
        } else {
            networkLostCount = 0
        }
        
        // 立即恢复
        DownloadManager.onNetworkRestored()
    }
    
    fun onNetworkLost() {
        lastNetworkLostTime = System.currentTimeMillis()
        DownloadManager.pauseAll()
    }
}
```

#### 2. 网络类型感知

```kotlin
private fun handleNetworkCapabilitiesChanged(networkCapabilities: NetworkCapabilities) {
    val isWifi = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    val isCellular = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    val isEthernet = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    
    when {
        isWifi -> {
            // WiFi网络，最优下载体验
            DownloadManager.config {
                maxConcurrentTasks = 5
                allowMetered = true
            }
        }
        isEthernet -> {
            // 有线网络，稳定高速
            DownloadManager.config {
                maxConcurrentTasks = 8
                allowMetered = true
            }
        }
        isCellular -> {
            // 移动网络，保守策略
            DownloadManager.config {
                maxConcurrentTasks = 2
                allowMetered = false // 根据用户设置决定
            }
        }
    }
}

// 或者使用DownloadManager提供的网络检查API
private fun adjustDownloadStrategy() {
    when {
        DownloadManager.isWifiAvailable() -> {
            // WiFi网络，最优下载体验
            DownloadManager.config {
                maxConcurrentTasks = 5
                allowMetered = true
            }
        }
        DownloadManager.isCellularAvailable() -> {
            // 移动网络，保守策略
            DownloadManager.config {
                maxConcurrentTasks = 2
                allowMetered = false
            }
        }
        !DownloadManager.isNetworkAvailable() -> {
            // 无网络，暂停所有下载
            DownloadManager.pauseAll()
        }
    }
}
```

### 📝 总结

通过以上接入文档和最佳实践，你可以：

1. **快速集成**：按照文档步骤快速集成下载管理器
2. **高效监听**：使用Flow监听器实现响应式UI更新
3. **优化性能**：通过防抖、分组管理等策略优化列表刷新性能
4. **提升体验**：通过合理的状态管理和错误处理提升用户体验
5. **网络优化**：通过自定义网络监听实现智能的网络恢复策略

这个下载管理器提供了企业级的功能特性，支持多线程分片下载、断点续传、优先级调度等，能够满足各种复杂的下载场景需求。同时，通过将网络监听责任交给接入者，确保了更好的安全性和灵活性。
