# 快速接入指南 (Quick Start)

本文档旨在帮助你以最快的方式接入 `downloader` 下载库。

## 1. 添加依赖 (Add Dependency)

确保在你的 `app/build.gradle` 中添加了模块依赖：

```kotlin
dependencies {
    implementation(project(":downloader"))
    // 或者如果是发布版本
    // implementation("com.pichs.download:downloader:x.y.z")
}
```

## 2. 初始化 (Initialization)

在你的 `Application` 的 `onCreate` 方法中进行初始化。推荐同时调用 `restoreInterruptedTasks()` 以便在 App 重启后恢复之前的任务。

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // 1. 初始化下载管理器
        DownloadManager.init(this)
        
        // 2. 恢复中断的任务 (重要：确保 App 重启后任务能继续或保持状态)
        DownloadManager.restoreInterruptedTasks()
        
        // 3. (可选但推荐) 设置网络确认回调
        // 如果你不希望在流量下自动暂停，也不想全局允许流量，则需要实现此回调来弹窗询问用户
        DownloadManager.setCheckAfterCallback(object : CheckAfterCallback {
            override fun requestConfirmation(
                scenario: NetworkScenario,
                pendingTasks: List<DownloadTask>,
                totalSize: Long,
                onConnectWifi: () -> Unit,
                onUseCellular: () -> Unit
            ) {
                // 在这里弹出你的 Dialog
                // Show your dialog here
                AlertDialog.Builder(applicationContext) // 注意：Context 需要是 Activity 的 Context，实际上这里建不议直接写 UI 逻辑，建议使用 ActivityLifecycleCallbacks 或 EventBus 发送事件到当前 Activity 处理
                    .setTitle("网络提示")
                    .setMessage("当前处于移动网络，是否继续下载？大小：${Formatter.formatFileSize(applicationContext, totalSize)}")
                    .setPositiveButton("继续") { _, _ -> onUseCellular() }
                    .setNegativeButton("取消") { _, _ -> onConnectWifi() } // 或只取消不操作
                    .show()
            }

            override fun showWifiOnlyHint(task: DownloadTask?) {
                Toast.makeText(applicationContext, "当前仅 WiFi 模式，任务已暂停", Toast.LENGTH_SHORT).show()
            }
        })
    }
}
```

## 3. 开始下载 (Start Download)

最简单的启动下载方式：

```kotlin
// 只需要传入 URL 即可
val task = DownloadManager.download("https://example.com/file.apk")
    .start()

// task.id 即为任务 ID，可用于后续监听
```

如果你需要自定义文件名或路径：

```kotlin
DownloadManager.download("https://example.com/file.apk")
    .fileName("custom_name.apk") // 可选：自定义文件名
    .path(externalCacheDir?.absolutePath ?: "") // 可选：自定义路径
    .start()
```

## 4. 监听下载进度 (Listen to Progress)

推荐使用 `flowDownloadListener` 进行响应式监听，它支持协程和生命周期感知。

### 方式一：自动管理生命周期 (推荐)

在 Activity 或 Fragment 中调用 `bindToLifecycle`，它会自动处理生命周期，无需手动取消注册。

```kotlin
// 在 onCreate 中调用
DownloadManager.flowDownloadListener.bindToLifecycle(this,
    onTaskProgress = { task, progress, speed ->
        // 更新进度条
        progressBar.progress = progress
        speedText.text = "${speed / 1024} KB/s"
    },
    onTaskComplete = { task, file ->
        // 下载完成
        Toast.makeText(this, "下载完成: ${file.absolutePath}", Toast.LENGTH_SHORT).show()
    },
    onTaskError = { task, error ->
        // 处理错误
    }
)
```

### 方式二：监听单个任务

如果你只想监听特定的任务进度：

```kotlin
DownloadManager.flowDownloadListener.observeSingleTaskProgress(taskId, lifecycleScope) { progress, speed ->
    // 更新进度
    Log.d("Download", "Progress: $progress%, Speed: $speed")
}
```

## 5. 权限配置 (Permissions)

确保在 `AndroidManifest.xml` 中添加了网络权限：

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

- 如果应用需要支持 HTTP (非 HTTPS)，请在 `application` 标签中配置 `android:usesCleartextTraffic="true"`。
- 如果你需要下载到外部公共目录，请确保申请了存储权限。默认情况下，建议下载到 `context.getExternalFilesDir()` 或 `context.getExternalCacheDir()`，无需额外权限。

## 6. 网络策略与检查 (Network Policy & Checks)

下载库包含两层网络检查机制，确保用户不会意外消耗大量流量。

### 6.1 前置检查 (Pre-check) - `checkBeforeCreate`
**发生在：** 任务创建 **"之前"**。
**作用：** 在任务生成前就拦截，避免生成垃圾任务记录。
**默认：** 关闭 (`config.checkBeforeCreate = false`)。

如果开启前置检查，你需要手动调用：
```kotlin
// 1. 开启配置
DownloadManager.setNetworkConfig(NetworkDownloadConfig(checkBeforeCreate = true))

// 2. 在 UI 层下载前手动检查
val result = DownloadManager.checkBeforeCreate(totalSize = 1024 * 1024 * 100) // 假设 100MB
when (result) {
    is CheckBeforeResult.NeedConfirmation -> {
        // 弹窗询问，用户同意后再调用 DownloadManager.download(...).start()
    }
    CheckBeforeResult.Allow -> {
        // 直接下载
        DownloadManager.download(...).start()
    }
    else -> { /* 处理其他拒绝情况 */ }
}
```

### 6.2 后置检查 (Post-check) - `checkAfterCreate`
**发生在：** `task.start()` 调用 **"之后"**。
**作用：** 任务已创建，但立即暂停，等待用户确认。
**默认：** 开启 (`config.checkAfterCreate = true`)。

**行为逻辑：**
1.  **WiFi 环境**：直接开始下载。
2.  **流量环境**：
    *   **默认行为**：框架内置了 `DefaultCheckAfterCallback`，它会 **自动允许** 流量下载（Logcat 会有日志），**不会** 阻断下载。
    *   **自定义行为**：如果你希望在流量下弹窗询问，**必须** 调用 `DownloadManager.setCheckAfterCallback(...)` 设置你自己的实现（参考第2节代码）。
    *   **静默行为**：如果你既不想弹窗也不想下载，可以配置 `NetworkDownloadConfig` 的 `wifiOnly = true`。

**总结：**
*   **最简模式**：直接调用 `.start()` 即可。默认情况下，流量环境也会自动开始下载，不会静默暂停。
*   **生产模式**：建议实现 `CheckAfterCallback` 来给用户更好的体验（弹窗提示消耗流量）。
