# 更新日志

所有显著变更都会记录在此文件中。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/)，
本项目遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

---

## v2.1.3 (2026-01-19)

### 🐛 重要Bug修复
- **修复 APK 下载完成后被过早删除的问题**
  - 问题现象：下载完成的 APK 在安装过程中文件消失，导致安装失败
  - 根本原因：每次任务完成都触发 Retention Policy，刚完成的任务可能立即被删除
  - 解决方案：移除下载完成时的自动清理触发 + 添加保护期机制

### 🚀 新增功能
- **保护期（Protection Period）机制**
  - 刚完成的任务在保护期内**绝对不会被删除**
  - 默认保护期：24 小时（v2.1.2 及之前为 48 小时）
  - 支持通过 `setRetentionConfig()` API 自定义保护期时长
  - 确保 APK 有足够时间完成安装等后续操作

- **Retention Config API**
  - 新增 `setRetentionConfig(RetentionConfig)` - 配置保留策略参数
  - 新增 `getRetentionConfig()` - 获取当前配置
  - 支持自定义保护期、保留天数、保留数量等参数

### 🔧 优化改进
- **Retention Policy 执行时机优化**
  - 移除：下载完成时的自动清理触发（性能差且易误删）
  - 推荐：应用启动时执行清理（`App.onCreate()` 中调用 `executeRetentionPolicy()`）
  - 优势：避免下载过程中的性能影响，降低误删风险

- **清理逻辑优化**
  - 所有清理方法（按时间、按数量、低存储）都先排除保护期内的任务
  - 简化配置，移除 `tagConfigs` 功能（复杂且不常用）
  - 保留核心清理策略：按时间、按数量、低存储空间

- **默认配置调整**
  - 保护期默认值从 48 小时调整为 24 小时（更合理）
  - 其他默认值保持不变

### 📝 文档更新
- 新增详细的 Retention Policy 配置说明
- 新增保护期机制说明
- 新增使用场景示例（APK下载、快速清理等）
- 更新配置参数表格

### ⚠️ 破坏性变更
- 移除 `RetentionConfig` 中的 `tagConfigs` 和 `defaultTagConfig` 字段
  - 影响：如果你使用了按标签清理功能，需要改用其他清理策略
  - 建议：使用按时间或按数量清理替代

### 💡 使用建议

**配置示例**：
```kotlin
// 在 Application.onCreate() 中配置
DownloadManager.setRetentionConfig(
    RetentionConfig(
        protectionPeriodHours = 24,  // 24小时保护期
        keepCompletedDays = 30,       // 保留30天
        keepLatestCompleted = 100     // 最多100个
    )
)

// 应用启动时执行清理
lifecycleScope.launch {
    DownloadManager.executeRetentionPolicy()
}
```

---

## v2.0.9 (2026-01-13)

### 🚀 新增功能
- **`restoreInterruptedTasks()` API**
  - 进程重启后恢复中断的任务（僵尸任务 + 非用户手动暂停的任务）
  - 支持智能条件检查：网络恢复、WiFi 连接、存储空间充足等
  - 尊重用户意愿：`USER_MANUAL` 暂停的任务不会被自动恢复
  - 批量弹窗优化：多个待确认任务只触发一次流量确认弹窗

### 🔧 优化改进
- **初始化流程优化**
  - 从 `init()` 中移除 `restorePauseResumeState()` 直接调用
  - 推荐在 `setCheckAfterCallback()` 设置后调用 `restoreInterruptedTasks()`
  - 确保恢复任务时流量确认弹窗能正常触发

---

## v2.0.8 (2026-01-10)

### 🚀 新增功能
- **优先级抢占机制**
  - URGENT 任务可抢占 LOW/NORMAL/HIGH 任务立即执行
  - 被抢占任务状态变为 `WAITING` 自动重新入队
  - 等待队列按优先级降序 + 创建时间升序排列

### 🐛 Bug 修复
- **修复调度器无限循环问题**
  - 修复当多个 URGENT 任务同时存在时 `scheduleNextInternal()` 死循环
  - `tryPreempt()` 现返回抢占结果，失败时正确退出循环

- **修复 ANR 问题**
  - `handleClick()` 中 `isInstalledAndUpToDate()` 移至后台线程
  - `bindButtonUI()` 使用缓存的安装状态，避免主线程查询 PackageManager
  - `initCategoryLists()` 预计算应用安装状态
  - `downloadAllWithPriority()` 过滤逻辑移至后台线程

### 🔧 优化改进
- **抢占逻辑完善**
  - 同优先级任务不互相抢占，遵循先来先服务原则
  - 任务完成后自动触发调度，处理等待中的任务

---

## v2.0.7 (2026-01-08)

### 🚀 新增功能
- **批量恢复 API 增强**
  - `resumeAll()` - 优化为批量后置检查，流量环境只弹一次确认框
  - `resumeAll(pauseReason)` - 按暂停原因筛选恢复任务
  - `resumeTasks(tasks)` - 恢复指定任务列表

### 🔧 优化改进
- **批量恢复后置检查优化**
  - 原来：每个任务独立检查，10 个任务可能弹 10 次确认框
  - 现在：批量检查，只判断一次，只弹一次确认框
  - 提升用户体验，避免频繁弹窗干扰

- **任务状态安全性**
  - `resumeTasks(tasks)` 从内存获取最新状态，避免使用过期快照数据

---

## v2.0.6 (2026-01-07)

### 🚀 新增功能
- **三层文件验证体系** - 全方位保护下载数据完整性
  - **方案 3**：应用启动时自动清理无效任务
  - **方案 4**：下载引擎层自动检测并修复文件异常
  - **方案 2**（待实现）：UI 层点击前防御性检查

- **validateAndCleanTasks() 方法**
  - 检测已完成任务文件是否丢失
  - 检测下载中任务文件是否异常
  - 智能判断：只清理真正异常的任务，不影响排队中的任务

- **引擎层文件验证**
  - 下载前自动检测文件丢失或损坏
  - 自动重置进度并重新下载
  - 用户无感知，透明修复

### 🐛 Bug 修复
- **修复 cancel 和 deleteTask 死循环**
  - 重构了任务取消和删除逻辑
  - `cancel()` 现在完全删除任务和文件
  - 避免了互相调用导致的栈溢出

- **修复文件验证误判**
  - 增加 `currentSize > 0` 判断
  - 不再误删刚创建或排队中的任务
  - 只清理真正下载过但文件丢失的任务

### 🔧 优化改进
- **完善任务清理机制**
  - 应用启动时异步清理，不影响启动速度
  - 详细的日志输出便于问题排查
  - 支持检测并删除损坏的文件

---

## v2.0.5 (2026-01-07)

### 🚀 新增功能
- **新增 `NetworkMonitor` 类** - 使用 `NetworkCallback` API 替代 `BroadcastReceiver`
  - 可准确检测网络类型变化（流量 ↔ WiFi）
  - 解决了 `CONNECTIVITY_ACTION` 广播在网络类型切换时不触发的问题

### 🐛 Bug 修复
- **修复 WiFi 自动下载功能**
  - 修复了流量切换到 WiFi 时下载不自动恢复的问题
  - 完善了 `NetworkRuleManager` 的 WiFi 恢复逻辑
  - 新增 `resumeOtherSystemPausedTasks()` 恢复其他系统暂停的任务

- **修复网络恢复逻辑**
  - 修复应用重启后在流量网络下自动恢复未确认任务的问题
  - WiFi 网络：恢复所有网络相关暂停的任务
  - 流量网络：仅恢复 `cellularConfirmed=true` 的任务
  - 完善了对 `WIFI_UNAVAILABLE`、`CELLULAR_PENDING`、`NETWORK_ERROR` 三种暂停原因的处理

### 🔧 优化改进
- **完善暂停原因机制**
  - 明确区分用户手动暂停（`USER_MANUAL`）和系统暂停
  - 优化了不同暂停原因的自动恢复策略
  - 添加详细的日志输出便于调试

- **Demo 应用优化**
  - 统一所有用户手动暂停使用 `PauseReason.USER_MANUAL`
  - 统一等待 WiFi 暂停使用 `PauseReason.WIFI_UNAVAILABLE`

---

## v2.0.4 及更早版本

详见 [GitHub Releases](https://github.com/pichsy/download-manager/releases)
