# Download Manager - Android 多线程下载管理库

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

## 🛠️ 快速开始

### 1. 添加依赖

在项目的 `build.gradle.kts` 中添加：

```kotlin
dependencies {
    implementation(project(":download"))
    implementation(project(":base"))
}
```

### 2. 初始化下载管理器

在Application类中初始化：

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DownloadManager.init(this)
    }
}
```

### 3. 基础下载使用

```kotlin
// 简单下载
val task = DownloadManager.download("https://example.com/file.apk")
    .path(getExternalFilesDir(null)?.absolutePath ?: "")
    .fileName("app.apk")
    .start()

// 带优先级的下载
val task = DownloadManager.downloadWithPriority("https://example.com/file.apk", DownloadPriority.HIGH)
    .path(getExternalFilesDir(null)?.absolutePath ?: "")
    .fileName("app.apk")
    .start()

// 带自定义请求头的下载
val task = DownloadManager.download("https://example.com/file.apk")
    .path(getExternalFilesDir(null)?.absolutePath ?: "")
    .fileName("app.apk")
    .headers(mapOf("Authorization" to "Bearer token"))
    .start()
```

### 4. 监听下载进度

```kotlin
class MainActivity : AppCompatActivity() {
    private val flowListener = DownloadManager.flowListener
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 绑定Flow监听器
        flowListener.bindToLifecycle(
            lifecycleOwner = this,
            onTaskProgress = { task, progress, speed ->
                // 更新进度UI
                updateProgressUI(task, progress, speed)
            },
            onTaskComplete = { task, file ->
                // 下载完成处理
                handleDownloadComplete(task, file)
            },
            onTaskError = { task, error ->
                // 下载错误处理
                handleDownloadError(task, error)
            },
            onTaskPaused = { task ->
                // 下载暂停处理
                handleDownloadPaused(task)
            },
            onTaskResumed = { task ->
                // 下载恢复处理
                handleDownloadResumed(task)
            },
            onTaskCancelled = { task ->
                // 下载取消处理
                handleDownloadCancelled(task)
            }
        )
    }
}
```

### 5. 任务管理

```kotlin
// 获取所有任务
val allTasks = DownloadManager.getAllTasks()

// 获取正在下载的任务
val runningTasks = DownloadManager.getRunningTasks()

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

## ⚙️ 高级配置

### 下载配置

```kotlin
DownloadManager.config {
    maxConcurrentTasks = 5        // 最大并发任务数
    connectTimeoutSec = 30         // 连接超时时间
    readTimeoutSec = 60            // 读取超时时间
    writeTimeoutSec = 60           // 写入超时时间
    allowMetered = false           // 是否允许移动网络下载
    callbackOnMain = true          // 回调是否在主线程
    retention = Retention(
        keepDays = 7,              // 保留7天
        keepLatestCompleted = 10    // 保留最近10个完成任务
    )
}
```

### 优先级使用

```kotlin
// 紧急下载（最高优先级）
val urgentTask = DownloadManager.downloadUrgent("https://example.com/urgent.apk")
    .path(path)
    .fileName("urgent.apk")
    .start()

// 高优先级下载
val highTask = DownloadManager.downloadWithPriority("https://example.com/high.apk", DownloadPriority.HIGH)
    .path(path)
    .fileName("high.apk")
    .start()

// 后台下载（低优先级）
val backgroundTask = DownloadManager.downloadBackground("https://example.com/background.apk")
    .path(path)
    .fileName("background.apk")
    .start()
```

## 🏗️ 架构设计

### 核心组件

1. **DownloadManager**：下载管理器，提供统一的API接口
2. **MultiThreadDownloadEngine**：多线程下载引擎，负责实际的文件下载
3. **AdvancedDownloadQueueDispatcher**：高级队列调度器，管理任务优先级和并发
4. **DownloadScheduler**：下载调度器，协调任务执行
5. **ChunkManager**：分片管理器，处理分片创建和状态管理
6. **TaskRepository**：任务仓库，提供数据持久化接口

### 数据流

```
用户请求 → DownloadRequestBuilder → DownloadManager → QueueDispatcher → DownloadScheduler → MultiThreadDownloadEngine → ChunkManager → Database
```

### 状态管理

- **WAITING**：等待调度
- **PENDING**：准备下载
- **DOWNLOADING**：下载中
- **PAUSED**：已暂停
- **COMPLETED**：已完成
- **FAILED**：下载失败
- **CANCELLED**：已取消

## 📊 性能特性

### 多线程优化
- 根据文件大小智能选择线程数
- 分片大小自适应
- 内存使用优化

### 网络优化
- HTTP Range请求支持
- 连接复用
- 超时控制
- 重试机制

### 存储优化
- Room数据库持久化
- 内存缓存
- 分片状态管理
- 原子操作保证

## 🔧 技术栈

- **Kotlin**：主要开发语言
- **Coroutines**：异步处理
- **Flow**：响应式编程
- **Room**：数据库持久化
- **OkHttp**：网络请求
- **Okio**：IO操作
- **KSP**：代码生成

## 📝 最佳实践

### 1. 生命周期管理
```kotlin
// 在Activity/Fragment中绑定生命周期
flowListener.bindToLifecycle(lifecycleOwner = this, ...)
```

### 2. 错误处理
```kotlin
flowListener.bindToLifecycle(
    lifecycleOwner = this,
    onTaskError = { task, error ->
        when (error) {
            is NetworkException -> // 网络错误处理
            is StorageException -> // 存储错误处理
            else -> // 其他错误处理
        }
    }
)
```

### 3. 存储管理
```kotlin
// 检查存储空间
if (DownloadManager.isLowStorage()) {
    // 处理低存储情况
}

// 获取推荐路径
val recommendedPath = DownloadManager.getRecommendedPath()
```

### 4. 任务去重
```kotlin
// 检查是否存在相同任务
val existingTask = DownloadManager.findExistingTask(url, path, fileName)
if (existingTask != null) {
    // 处理已存在的任务
}
```

## 🐛 故障排除

### 常见问题

1. **下载速度慢**
   - 检查网络连接
   - 调整并发任务数
   - 检查服务器是否支持Range请求

2. **断点续传失败**
   - 确保服务器支持HTTP Range请求
   - 检查文件是否发生变化
   - 验证ETag和Last-Modified头

3. **任务状态异常**
   - 检查数据库连接
   - 验证任务ID唯一性
   - 查看日志输出

### 调试日志

```kotlin
// 启用详细日志
DownloadLog.setLogLevel(LogLevel.DEBUG)
```

## 📄 许可证

本项目采用 [LICENSE](LICENSE) 许可证。

## 🤝 贡献

欢迎提交Issue和Pull Request来改进这个项目。

## 📞 联系方式

如有问题或建议，请通过以下方式联系：

- 提交Issue
- 发送邮件
- 微信交流群

---

**注意**：本库仍在积极开发中，API可能会有变化。建议在生产环境使用前进行充分测试。