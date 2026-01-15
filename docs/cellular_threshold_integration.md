# 流量阈值功能集成指南

> 本次修改将流量提醒逻辑统一到框架内部，废弃 `CellularPromptMode` 枚举，改用 `cellularThreshold: Long` 字段。

## 一、框架修改点

### 1. NetworkDownloadConfig.kt

**路径**: `downloader/src/main/java/com/pichs/download/model/NetworkDownloadConfig.kt`

**修改内容**:
- 新增 `CellularThreshold` 常量对象
- 新增 `cellularThreshold: Long` 配置字段
- 废弃 `CellularPromptMode` 枚举

```kotlin
// 新增常量
object CellularThreshold {
    const val NEVER_PROMPT = Long.MAX_VALUE  // 不提醒
    const val ALWAYS_PROMPT = 0L             // 每次提醒
}

// 新增配置字段
data class NetworkDownloadConfig(
    val wifiOnly: Boolean = false,
    val cellularThreshold: Long = CellularThreshold.ALWAYS_PROMPT,  // 替代原 cellularPromptMode
    val checkBeforeCreate: Boolean = false,
    val checkAfterCreate: Boolean = true
)
```

---

### 2. NetworkRuleManager.kt

**路径**: `downloader/src/main/java/com/pichs/download/core/NetworkRuleManager.kt`

**修改内容**:

#### 2.1 持久化 Key 更新
```kotlin
// 旧
private const val KEY_CELLULAR_PROMPT_MODE = "cellular_prompt_mode"

// 新
private const val KEY_CELLULAR_THRESHOLD = "cellular_threshold"
```

#### 2.2 loadConfig() / saveConfig()
```kotlin
// 读取
val cellularThreshold = prefs.getLong(KEY_CELLULAR_THRESHOLD, CellularThreshold.ALWAYS_PROMPT)

// 保存
prefs.putLong(KEY_CELLULAR_THRESHOLD, config.cellularThreshold)
```

#### 2.3 checkCellularDownloadPermission()
```kotlin
// 旧：根据枚举判断
when (config.cellularPromptMode) { ... }

// 新：根据阈值判断
when {
    threshold == CellularThreshold.NEVER_PROMPT -> Allow
    threshold == CellularThreshold.ALWAYS_PROMPT || totalSize >= threshold -> NeedConfirmation
    else -> Allow  // 未超阈值，静默放行
}
```

#### 2.4 onWifiDisconnected()
- 同样改为阈值判断逻辑
- 未超阈值时自动确认并恢复任务

---

### 3. DownloadManager.kt

**路径**: `downloader/src/main/java/com/pichs/download/core/DownloadManager.kt`

**修改内容**:
- 移除所有 `DenyReason.USER_CONTROLLED_NOT_ALLOWED` 分支
- 更新 `isCellularDownloadAllowed()` 使用阈值判断

---

### 4. DenyReason 枚举

**路径**: `downloader/src/main/java/com/pichs/download/core/NetworkRuleManager.kt`

**修改内容**: 移除 `USER_CONTROLLED_NOT_ALLOWED` 枚举值

```kotlin
// 旧
enum class DenyReason {
    NO_NETWORK, WIFI_ONLY_MODE, USER_CONTROLLED_NOT_ALLOWED
}

// 新
enum class DenyReason {
    NO_NETWORK, WIFI_ONLY_MODE
}
```

---

### 5. CheckBeforeResult.kt

**路径**: `downloader/src/main/java/com/pichs/download/model/CheckBeforeResult.kt`

**修改内容**: 标记 `UserControlled` 为废弃

```kotlin
@Deprecated("框架已支持阈值判断，使用 cellularThreshold 配置替代，此类型不再返回")
data class UserControlled(val estimatedSize: Long) : CheckBeforeResult()
```

---

## 二、使用端接入修改

### 配置方式

```kotlin
// 每次都提醒（默认）
DownloadManager.setNetworkConfig(NetworkDownloadConfig(
    cellularThreshold = CellularThreshold.ALWAYS_PROMPT  // 0L
))

// 不提醒
DownloadManager.setNetworkConfig(NetworkDownloadConfig(
    cellularThreshold = CellularThreshold.NEVER_PROMPT  // Long.MAX_VALUE
))

// 智能提醒：超过 100MB 才提醒
DownloadManager.setNetworkConfig(NetworkDownloadConfig(
    cellularThreshold = 100 * 1024 * 1024L
))
```

### 阈值行为表

| cellularThreshold 值 | 行为 |
|---------------------|------|
| `0L` (ALWAYS_PROMPT) | 每次流量下载都弹窗确认 |
| `Long.MAX_VALUE` (NEVER_PROMPT) | 流量下载不弹窗，直接下载 |
| 其他正值 (如 100MB) | 下载大小 >= 阈值时弹窗，否则静默下载 |

---

## 三、迁移指南

如果使用端之前使用了 `CellularPromptMode`，需要按以下对应关系迁移：

| 旧配置 | 新配置 |
|--------|--------|
| `CellularPromptMode.ALWAYS` | `cellularThreshold = 0L` |
| `CellularPromptMode.NEVER` | `cellularThreshold = Long.MAX_VALUE` |
| `CellularPromptMode.USER_CONTROLLED` | `cellularThreshold = 你的阈值` |

---

## 四、设置页面示例

```kotlin
// 监听流量提醒模式切换
binding.llDownloadDataUseGroup.setOnRadioCheckedListener { _, _, isChecked, position ->
    if (isChecked) {
        val config = DownloadManager.getNetworkConfig()
        val newThreshold = when (position) {
            0 -> CellularThreshold.ALWAYS_PROMPT     // 每次提醒
            1 -> CellularThreshold.NEVER_PROMPT      // 不再提醒
            else -> 100 * 1024 * 1024L               // 智能提醒 100MB
        }
        DownloadManager.setNetworkConfig(config.copy(cellularThreshold = newThreshold))
    }
}
```

---

## 五、智能阈值缓存

**路径**: `app/src/main/java/com/pichs/download/demo/AppUseDataSettingsActivity.kt`

当用户选择"智能提醒"模式并选择了阈值后，即使切换到其他模式（每次提醒/不再提醒），也会记住上次选择的阈值。下次再切换回"智能提醒"时，自动恢复上次的选择。

### 实现逻辑

```kotlin
companion object {
    private const val PREFS_NAME = "app_settings_cache"
    private const val KEY_LAST_SMART_THRESHOLD_INDEX = "last_smart_threshold_index"
}

// 获取上次选择的智能阈值索引（默认索引 2 = 100MB）
private fun getLastSmartThresholdIndex(): Int {
    return prefs.getInt(KEY_LAST_SMART_THRESHOLD_INDEX, 2)
}

// 保存智能阈值索引（不会因为切换模式而清理）
private fun saveLastSmartThresholdIndex(index: Int) {
    prefs.edit().putInt(KEY_LAST_SMART_THRESHOLD_INDEX, index).apply()
}
```

### 使用场景

1. 用户选择"智能提醒"→ 选择阈值 100MB → **缓存索引 2**
2. 用户切换到"每次提醒" → **缓存不清理**
3. 用户再次切换到"智能提醒" → **自动选中 100MB**

---

## 六、版本信息

- **修改日期**: 2026-01-15
- **影响版本**: 2.1.1+
- **向后兼容**: 保留旧枚举但标记为废弃
