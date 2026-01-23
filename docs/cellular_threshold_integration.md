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

## 三、Demo 对接流程

### 1. MainActivity 初始化

**路径**: `app/src/main/java/com/pichs/download/demo/MainActivity.kt`

```kotlin
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 1. 注册网络监听
        NetworkMonitor(
            onNetworkChanged = { isWifi ->
                if (isWifi) {
                    DownloadManager.onWifiConnected()
                }
                DownloadManager.onNetworkRestored()
            },
            onNetworkLost = {
                DownloadManager.onWifiDisconnected()
            }
        ).register(this)
        
        // 2. 设置回调（必须在 restoreInterruptedTasks 之前）
        DownloadManager.setCheckAfterCallback(MyCheckAfterCallback(this))
        
        // 3. 恢复中断的任务
        DownloadManager.restoreInterruptedTasks()
    }
}
```

---

### 2. MyCheckAfterCallback 实现

**路径**: `app/src/main/java/com/pichs/download/demo/MyCheckAfterCallback.kt`

```kotlin
class MyCheckAfterCallback(
    private val activity: FragmentActivity
) : CheckAfterCallback {

    private var pendingOnUseCellular: (() -> Unit)? = null
    private var pendingOnConnectWifi: (() -> Unit)? = null
    
    init {
        // 监听弹窗确认结果
        activity.lifecycleScope.launch {
            CellularConfirmViewModel.confirmEvent.collect { event ->
                when (event) {
                    is CellularConfirmEvent.Confirmed -> {
                        pendingOnUseCellular?.invoke()
                        pendingOnUseCellular = null
                        pendingOnConnectWifi = null
                    }
                    is CellularConfirmEvent.Denied -> {
                        pendingOnConnectWifi?.invoke()
                        pendingOnUseCellular = null
                        pendingOnConnectWifi = null
                    }
                }
            }
        }
    }

    override fun requestConfirmation(
        scenario: NetworkScenario,
        pendingTasks: List<DownloadTask>,
        totalSize: Long,
        onConnectWifi: () -> Unit,
        onUseCellular: () -> Unit
    ) {
        when (scenario) {
            NetworkScenario.CELLULAR_CONFIRMATION -> {
                // 流量确认：弹窗
                pendingOnUseCellular = onUseCellular
                pendingOnConnectWifi = onConnectWifi
                CellularConfirmDialogActivity.start(activity, totalSize, pendingTasks.size)
            }
            NetworkScenario.WIFI_ONLY_MODE -> {
                // 仅WiFi模式：弹窗
                pendingOnUseCellular = onUseCellular
                pendingOnConnectWifi = onConnectWifi
                CellularConfirmDialogActivity.start(
                    activity, totalSize, pendingTasks.size, 
                    CellularConfirmDialogActivity.MODE_WIFI_ONLY
                )
            }
            NetworkScenario.NO_NETWORK -> {
                // 无网络：Toast 提示
                Toast.makeText(activity, "等待网络下载", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun showWifiOnlyHint(task: DownloadTask?) {
        Toast.makeText(activity, "当前设置为仅 WiFi 下载", Toast.LENGTH_LONG).show()
    }
    
    override fun showWifiDisconnectedHint(pausedCount: Int) {
        if (pausedCount > 0) {
            Toast.makeText(activity, "WiFi 已断开，$pausedCount 个任务已暂停", Toast.LENGTH_SHORT).show()
        }
    }
}
```

---

### 3. ViewModel 中的下载请求

**路径**: `app/src/main/java/com/pichs/download/demo/ui/AppStoreViewModel.kt`

> [!NOTE]
> v2.1.1+ 后 `CheckBeforeResult.UserControlled` 不再返回，框架统一处理阈值判断。
> 旧代码中的 `UserControlled` 分支可以保留（向后兼容），但实际不会执行。

```kotlin
fun requestDownload(context: Context, appInfo: UpdateAppInfo) {
    viewModelScope.launch {
        val result = DownloadManager.checkBeforeCreate(appInfo.size ?: 0L)

        when (result) {
            is CheckBeforeResult.Allow -> {
                doStartDownload(context, appInfo)
            }
            is CheckBeforeResult.NoNetwork -> {
                _uiEvent.emit(AppStoreUiEvent.ShowNoNetworkDialog(appInfo))
            }
            is CheckBeforeResult.WifiOnly -> {
                _uiEvent.emit(AppStoreUiEvent.ShowWifiOnlyDialog(appInfo))
            }
            is CheckBeforeResult.NeedConfirmation -> {
                // 需要确认（框架已根据阈值判断）
                _uiEvent.emit(AppStoreUiEvent.ShowCellularConfirmDialog(appInfo, result.estimatedSize))
            }
            // 以下分支 v2.1.1+ 不再执行，可删除或保留兼容
            is CheckBeforeResult.UserControlled -> {
                // 旧逻辑，已废弃
            }
        }
    }
}
```

---

### 4. 后台批量下载

**路径**: `app/src/main/java/com/pichs/download/demo/BackgroundBatchDownloadHelper.kt`

```kotlin
suspend fun startBatchDownload(context: Context, apps: List<UpdateAppInfo>) {
    val totalSize = apps.sumOf { it.size ?: 0L }
    
    when (val result = DownloadManager.checkBeforeCreate(totalSize, apps.size)) {
        is CheckBeforeResult.Allow -> {
            doStartBatchDownload(context, apps)
        }
        is CheckBeforeResult.NeedConfirmation -> {
            // 框架已判断超阈值，需要弹窗
            DownloadUiEventManager.emit(
                DownloadUiEvent.ShowCellularConfirmDialog(
                    totalSize = result.estimatedSize,
                    count = apps.size,
                    onConfirm = { doStartBatchDownload(context, apps, cellularConfirmed = true) }
                )
            )
        }
        // 其他场景类似处理...
    }
}

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

**路径**: `app/src/main/java/com/pichs/download/demo/CellularSettingsActivity.kt`

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
