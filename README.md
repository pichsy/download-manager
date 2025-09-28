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

## 🛠️ 快速开始， 接入文档



