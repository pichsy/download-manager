# 实施计划：状态机守卫 (State Machine Guard)

## 目标
使用"状态机守卫"模式彻底解决下载暂停时的竞态条件问题，确保架构整洁，避免下层引擎反向依赖上层状态。

## 变更概述

### 1. `MultiThreadDownloadEngine.kt` [已撤销变更]
- 撤销之前的"双重检查"逻辑，保持引擎代码纯净。

### 2. `DownloadManager.kt` [Modify]
- 修改 `updateTaskInternal` 方法，增加状态拦截逻辑。
- **规则**：如果当前内存中的任务状态为 `PAUSED`、`CANCELLED` 或 `FAILED`（停止态），而传入的新状态为 `DOWNLOADING`，则视为无效的过期更新（Stale Update），直接丢弃。

## 验证计划 (Verification Plan)

### 手动验证 (Manual Verification)
1.  **暂停竞态测试**：
    - 启动一个大文件下载。
    - 点击"暂停"。
    - 观察 UI 状态：必须保持为 `PAUSED`，不能跳回 `DOWNLOADING` 或显示速度。
    - 检查日志：搜索 "拦截到过期的进度更新" 样式的日志，确认守卫机制生效。
2.  **快速恢复测试**：
    - 开始下载 -> 暂停 -> 立即恢复。
    - 确认下载能正常继续，且进度持续更新（因为此时状态不再是 PAUSED，守卫不会误拦有效更新）。
