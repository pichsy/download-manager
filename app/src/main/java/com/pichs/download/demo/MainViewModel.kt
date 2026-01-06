package com.pichs.download.demo

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pichs.download.core.DownloadManager
import com.pichs.download.core.DownloadPriority
import com.pichs.download.model.DownloadTask
import com.pichs.download.utils.DownloadLog
import com.pichs.xbase.utils.GsonUtils
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * MainActivity 的 ViewModel
 * 管理应用列表、下载任务和网络状态
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    // 应用列表
    private val _appList = MutableStateFlow<List<DownloadItem>>(emptyList())
    val appList: StateFlow<List<DownloadItem>> = _appList.asStateFlow()

    // UI 事件（用于通知 Activity 显示 Toast、弹窗等）
    private val _uiEvent = MutableSharedFlow<UiEvent>()
    val uiEvent: SharedFlow<UiEvent> = _uiEvent.asSharedFlow()

    // 下载目录
    private val downloadDir: String by lazy {
        getApplication<Application>().getExternalFilesDir(null)?.absolutePath
            ?: getApplication<Application>().filesDir.absolutePath
    }

    /**
     * 从 Assets 加载应用列表
     */
    fun loadAppListFromAssets() {
        viewModelScope.launch {
            val jsonString = getApplication<Application>().assets
                .open("app_list.json")
                .bufferedReader()
                .use { it.readText() }
            loadAppList(jsonString)
        }
    }

    /**
     * 加载应用列表
     */
    private fun loadAppList(jsonString: String) {
        val appListBean = GsonUtils.fromJson<AppListBean>(jsonString, AppListBean::class.java)
        val list = appListBean.appList ?: emptyList()

        // 自动关联已有任务
        list.forEach { item ->
            if (item.task == null) {
                val existingTask = DownloadManager.getTaskByUrl(item.url)
                if (existingTask != null) item.task = existingTask
            }
        }

        _appList.value = list
    }

    /**
     * 刷新列表中的任务状态
     */
    fun refreshTaskStates() {
        val currentList = _appList.value.toMutableList()
        currentList.forEachIndexed { index, item ->
            val existingTask = DownloadManager.getTaskByUrl(item.url)
            if (existingTask != null && item.task?.id != existingTask.id) {
                currentList[index].task = existingTask
            }
        }
        _appList.value = currentList
    }

    /**
     * 更新指定任务
     */
    fun updateTask(task: DownloadTask) {
        val currentList = _appList.value.toMutableList()
        val index = currentList.indexOfFirst { it.url == task.url }
        if (index >= 0) {
            currentList[index].task = task
            _appList.value = currentList
        }
    }

    /**
     * 模拟批量后台下载
     * 使用新的 checkBeforeCreate API 实现"先决策后创建任务"
     */
    fun simulateBatchDownload() {
        viewModelScope.launch {
            // 获取批量下载的应用
            val appsToDownload = getBatchDownloadApps()

            if (appsToDownload.isEmpty()) {
                DownloadLog.d("模拟批量下载", "未找到目标应用")
                return@launch
            }

            // 计算总大小
            val totalSize = appsToDownload.sumOf { it.size }

            DownloadLog.d("模拟批量下载", "准备下载 ${appsToDownload.size} 个应用，总大小: $totalSize")

            // 使用新的预检查 API
            when (val result = DownloadManager.checkBeforeCreate(totalSize)) {
                is com.pichs.download.model.CheckBeforeResult.Allow -> {
                    // 允许下载，直接开始（WiFi 或已放行）
                    startBatchDownload(appsToDownload)
                    _uiEvent.emit(UiEvent.ShowToast("批量下载已开始：${appsToDownload.size} 个应用"))
                }

                is com.pichs.download.model.CheckBeforeResult.NoNetwork -> {
                    _uiEvent.emit(UiEvent.ShowToast("无网络连接"))
                }

                is com.pichs.download.model.CheckBeforeResult.WifiOnly -> {
                    // 仅WiFi模式，弹窗让用户选择
                    _uiEvent.emit(UiEvent.ShowWifiOnlyDialog(appsToDownload, totalSize))
                }

                is com.pichs.download.model.CheckBeforeResult.NeedConfirmation -> {
                    // 需要确认，弹窗
                    _uiEvent.emit(UiEvent.ShowCellularConfirmDialog(appsToDownload, result.estimatedSize))
                }

                is com.pichs.download.model.CheckBeforeResult.UserControlled -> {
                    // 用户控制模式，使用端判断阈值
                    if (CellularThresholdManager.shouldPrompt(result.estimatedSize)) {
                        _uiEvent.emit(UiEvent.ShowCellularConfirmDialog(appsToDownload, result.estimatedSize))
                    } else {
                        // 未超阈值，静默下载
                        DownloadLog.d("模拟批量下载", "智能提醒：未超阈值，静默下载")
                        startBatchDownload(appsToDownload, cellularConfirmed = true)
                        _uiEvent.emit(UiEvent.ShowToast("批量下载已开始：${appsToDownload.size} 个应用"))
                    }
                }
            }
        }
    }

    /**
     * 用户确认后执行批量下载
     */
    fun confirmBatchDownload(apps: List<DownloadItem>) {
        viewModelScope.launch {
            startBatchDownload(apps)
            _uiEvent.emit(UiEvent.ShowToast("批量下载已开始：${apps.size} 个应用"))
        }
    }

    /**
     * 等待WiFi下载：创建任务并暂停
     * 框架层会在WiFi连接后自动恢复
     */
    fun startDownloadAndPause(apps: List<DownloadItem>) {
        viewModelScope.launch {
            apps.forEach { app ->
                val extrasJson = buildExtrasJson(app)
                val fileName = buildFileName(app.name)

                val task = DownloadManager.download(app.url)
                    .path(downloadDir)
                    .fileName(fileName)
                    .estimatedSize(app.size)
                    .extras(extrasJson)
                    .start()

                // 立即暂停，等待WiFi
                DownloadManager.pauseTask(task.id, com.pichs.download.model.PauseReason.WIFI_UNAVAILABLE)
                app.task = task.copy(status = com.pichs.download.model.DownloadStatus.PAUSED)
            }

            // 触发列表更新
            _appList.value = _appList.value.toList()

            val msg = if (apps.size == 1) "已加入下载队列，等待WiFi连接" else "已加入下载队列（${apps.size}个），等待WiFi连接"
            _uiEvent.emit(UiEvent.ShowToast(msg))
        }
    }
    
    /**
     * 等待网络下载：创建任务并暂停（无网络时使用）
     * 框架层会在网络恢复后自动恢复
     */
    fun startDownloadAndPauseForNetwork(apps: List<DownloadItem>) {
        viewModelScope.launch {
            apps.forEach { app ->
                val extrasJson = buildExtrasJson(app)
                val fileName = buildFileName(app.name)

                val task = DownloadManager.download(app.url)
                    .path(downloadDir)
                    .fileName(fileName)
                    .estimatedSize(app.size)
                    .extras(extrasJson)
                    .start()

                // 立即暂停，设置为网络异常原因
                DownloadManager.pauseTask(task.id, com.pichs.download.model.PauseReason.NETWORK_ERROR)
                app.task = task.copy(
                    status = com.pichs.download.model.DownloadStatus.PAUSED,
                    pauseReason = com.pichs.download.model.PauseReason.NETWORK_ERROR
                )
            }

            // 触发列表更新
            _appList.value = _appList.value.toList()

            val msg = if (apps.size == 1) "已加入下载队列，等待网络连接" else "已加入下载队列（${apps.size}个），等待网络连接"
            _uiEvent.emit(UiEvent.ShowToast(msg))
        }
    }

    /**
     * 开始下载（带预检查）
     * 用户主动点击下载按钮时调用
     */
    fun requestDownload(item: DownloadItem) {
        viewModelScope.launch {
            val result = DownloadManager.checkBeforeCreate(item.size)

            when (result) {
                is com.pichs.download.model.CheckBeforeResult.Allow -> {
                    doStartDownload(item)
                }

                is com.pichs.download.model.CheckBeforeResult.NoNetwork -> {
                    // 无网络：弹窗让用户选择【连接WiFi】或【等待网络下载】
                    _uiEvent.emit(UiEvent.ShowNoNetworkDialog(listOf(item), item.size))
                }

                is com.pichs.download.model.CheckBeforeResult.WifiOnly -> {
                    // 仅WiFi模式，弹窗让用户选择
                    _uiEvent.emit(UiEvent.ShowWifiOnlyDialog(listOf(item), item.size))
                }

                is com.pichs.download.model.CheckBeforeResult.NeedConfirmation -> {
                    // 单个下载需要确认
                    _uiEvent.emit(UiEvent.ShowCellularConfirmDialog(listOf(item), result.estimatedSize))
                }

                is com.pichs.download.model.CheckBeforeResult.UserControlled -> {
                    if (CellularThresholdManager.shouldPrompt(result.estimatedSize)) {
                        _uiEvent.emit(UiEvent.ShowCellularConfirmDialog(listOf(item), result.estimatedSize))
                    } else {
                        doStartDownload(item, cellularConfirmed = true)
                    }
                }
            }
        }
    }

    /**
     * 内部方法：实际执行下载
     */
    private fun doStartDownload(item: DownloadItem, cellularConfirmed: Boolean = false): DownloadTask {
        val extrasJson = buildExtrasJson(item)
        val fileName = buildFileName(item.name)

        val task = DownloadManager.downloadWithPriority(item.url, DownloadPriority.HIGH)
            .path(downloadDir)
            .fileName(fileName)
            .estimatedSize(item.size)
            .extras(extrasJson)
            .cellularConfirmed(cellularConfirmed)
            .start()

        // 更新列表
        updateItemTask(item, task)
        return task
    }

    /**
     * 批量下载（内部方法）
     */
    private fun startBatchDownload(apps: List<DownloadItem>, cellularConfirmed: Boolean = false) {
        DownloadLog.d("模拟批量下载", "开始创建 ${apps.size} 个任务")
        apps.forEach { app ->
            val extrasJson = buildExtrasJson(app)
            val fileName = buildFileName(app.name)

            val task = DownloadManager.download(app.url)
                .path(downloadDir)
                .fileName(fileName)
                .estimatedSize(app.size)
                .extras(extrasJson)
                .cellularConfirmed(cellularConfirmed)
                .start()

            app.task = task
        }
        // 触发列表更新
        _appList.value = _appList.value.toList()
    }

    /**
     * 暂停下载
     */
    fun pauseDownload(taskId: String) {
        DownloadManager.pauseTask(taskId, com.pichs.download.model.PauseReason.USER_MANUAL)
    }

    /**
     * 恢复下载
     */
    fun resumeDownload(taskId: String) {
        DownloadManager.resume(taskId)
    }

    /**
     * 获取批量下载的应用
     */
    private fun getBatchDownloadApps(): List<DownloadItem> {
        val targetPackages = listOf(
            "com.phoenix.read",
            "com.kugou.android",
            "tv.danmaku.bili",
            "com.ss.android.ugc.aweme",
            "com.tencent.mm"
        )
        return _appList.value.filter { it.packageName in targetPackages }
    }

    private fun updateItemTask(item: DownloadItem, task: DownloadTask) {
        val currentList = _appList.value.toMutableList()
        val index = currentList.indexOfFirst { it.url == item.url }
        if (index >= 0) {
            currentList[index].task = task
            _appList.value = currentList
        }
    }

    private fun buildExtrasJson(item: DownloadItem): String {
        return ExtraMeta(
            name = item.name,
            packageName = item.packageName,
            versionCode = item.versionCode,
            icon = item.icon,
            size = item.size
        ).toJson()
    }

    private fun buildFileName(name: String): String {
        return if (name.isEmpty()) {
            "1.apk"
        } else if (name.endsWith(".apk", ignoreCase = true)) {
            name
        } else {
            "${name}.apk"
        }
    }
}

/**
 * UI 事件
 */
sealed class UiEvent {
    data class ShowToast(val message: String) : UiEvent()
    data class ShowCellularConfirmDialog(val apps: List<DownloadItem>, val totalSize: Long) : UiEvent()

    /** 仅WiFi模式提示弹窗 */
    data class ShowWifiOnlyDialog(val apps: List<DownloadItem>, val totalSize: Long) : UiEvent()
    
    /** 无网络提示弹窗 */
    data class ShowNoNetworkDialog(val apps: List<DownloadItem>, val totalSize: Long) : UiEvent()
}
