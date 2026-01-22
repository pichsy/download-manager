# 更新日志

所有显著变更都会记录在此文件中。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/)，
本项目遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

---

## v2.1.5 (2026-01-22)

### � 优化改进

- **网络监听优化**
    - 使用 `registerDefaultNetworkCallback` 替代 `NetworkRequest.Builder`
    - 自动监听系统默认网络（实际用于下载的网络）
    - 从 WiFi 切换到蜂窝网络时自动触发回调，提升网络状态感知的准确性
    - 简化代码逻辑，提升稳定性

### ⚠️ 破坏性变更

- 无

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