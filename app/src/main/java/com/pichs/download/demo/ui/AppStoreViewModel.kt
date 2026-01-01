package com.pichs.download.demo.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pichs.download.core.DownloadManager
import com.pichs.download.core.DownloadPriority
import com.pichs.download.demo.CellularThresholdManager
import com.pichs.download.demo.ui.AppStoreFragment.Companion.TYPE_MUST_DOWNLOAD
import com.pichs.download.demo.ui.AppStoreFragment.Companion.TYPE_USER_DOWNLOAD
import com.pichs.download.model.CheckBeforeResult
import com.pichs.download.model.DownloadStatus
import com.pichs.download.model.DownloadTask
import com.pichs.download.model.PauseReason
import com.pichs.shanhai.base.api.ShanHaiApi
import com.pichs.shanhai.base.api.UpdateAppBody
import com.pichs.shanhai.base.api.entity.UpdateAppInfo
import com.pichs.shanhai.base.api.entity.qiniuHostUrl
import com.pichs.download.demo.ExtraMeta
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AppStoreViewModel : ViewModel() {

    private val _appListInfoFlow = MutableStateFlow(mutableListOf<UpdateAppInfo>())
    val appListFlow = _appListInfoFlow.asStateFlow()

    // UI 事件
    private val _uiEvent = MutableSharedFlow<AppStoreUiEvent>()
    val uiEvent: SharedFlow<AppStoreUiEvent> = _uiEvent.asSharedFlow()

    fun loadUpdateAppList(type: Int = TYPE_MUST_DOWNLOAD) {
        viewModelScope.launch {
            try {
                val response = ShanHaiApi.getApi().loadUpdateAppList(
                    UpdateAppBody(
                        type = 0,
                        category_type = if (TYPE_USER_DOWNLOAD == type) "2" else "1,3"
                    )
                )
                response?.result?.data?.let { appList ->
                    // 获取已有的下载任务，匹配到 UpdateAppInfo
                    syncTasksToAppList(appList)
                    _appListInfoFlow.value = appList
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * 同步下载任务到应用列表
     */
    private suspend fun syncTasksToAppList(appList: MutableList<UpdateAppInfo>) {
        val allTasks = DownloadManager.getAllTasks()
        appList.forEach { appInfo ->
            // 通过 URL 匹配任务（注意：需要使用完整 URL 匹配）
            val fullUrl = appInfo.apk_url?.qiniuHostUrl
            val matchedTask = allTasks.find { it.url == fullUrl }
            appInfo.task = matchedTask
        }
    }

    /**
     * 请求下载（带预检查）
     * 用户主动点击下载按钮时调用
     */
    fun requestDownload(context: Context, appInfo: UpdateAppInfo) {
        viewModelScope.launch {
            val result = DownloadManager.checkBeforeCreate(appInfo.size ?: 0L)

            when (result) {
                is CheckBeforeResult.Allow -> {
                    doStartDownload(context, appInfo)
                }

                is CheckBeforeResult.NoNetwork -> {
                    // 无网络：弹窗让用户选择
                    _uiEvent.emit(AppStoreUiEvent.ShowNoNetworkDialog(appInfo))
                }

                is CheckBeforeResult.WifiOnly -> {
                    // 仅WiFi模式，弹窗让用户选择
                    _uiEvent.emit(AppStoreUiEvent.ShowWifiOnlyDialog(appInfo))
                }

                is CheckBeforeResult.NeedConfirmation -> {
                    // 需要确认
                    _uiEvent.emit(AppStoreUiEvent.ShowCellularConfirmDialog(appInfo, result.estimatedSize))
                }

                is CheckBeforeResult.UserControlled -> {
                    if (CellularThresholdManager.shouldPrompt(result.estimatedSize)) {
                        _uiEvent.emit(AppStoreUiEvent.ShowCellularConfirmDialog(appInfo, result.estimatedSize))
                    } else {
                        doStartDownload(context, appInfo, cellularConfirmed = true)
                    }
                }
            }
        }
    }

    /**
     * 内部方法：实际执行下载
     */
    private fun doStartDownload(context: Context, appInfo: UpdateAppInfo, cellularConfirmed: Boolean = false): DownloadTask? {
        val url = appInfo.apk_url?.qiniuHostUrl ?: return null
        val dir = context.externalCacheDir?.absolutePath ?: context.cacheDir.absolutePath

        // 构建扩展信息
        val extras = ExtraMeta(
            name = appInfo.app_name,
            packageName = appInfo.package_name,
            versionCode = appInfo.version_code,
            icon = appInfo.app_icon,
            size = appInfo.size
        ).toJson()

        val task = DownloadManager.downloadWithPriority(url, DownloadPriority.HIGH)
            .path(dir)
            .fileName("${appInfo.package_name}_${appInfo.version_code}.apk")
            .extras(extras)
            .estimatedSize(appInfo.size ?: 0L)
            .cellularConfirmed(cellularConfirmed)
            .start()

        // 更新列表中的任务
        updateTaskInList(appInfo.apk_url?.qiniuHostUrl, task)
        return task
    }

    /**
     * 用户确认后执行下载（流量确认弹窗）
     */
    fun confirmDownload(context: Context, appInfo: UpdateAppInfo) {
        viewModelScope.launch {
            doStartDownload(context, appInfo, cellularConfirmed = true)
            _uiEvent.emit(AppStoreUiEvent.ShowToast("开始下载：${appInfo.app_name}"))
        }
    }

    /**
     * 等待WiFi下载：创建任务并暂停
     */
    fun startDownloadAndPause(context: Context, appInfo: UpdateAppInfo) {
        viewModelScope.launch {
            val url = appInfo.apk_url?.qiniuHostUrl ?: return@launch
            val dir = context.externalCacheDir?.absolutePath ?: context.cacheDir.absolutePath

            val extras = ExtraMeta(
                name = appInfo.app_name,
                packageName = appInfo.package_name,
                versionCode = appInfo.version_code,
                icon = appInfo.app_icon,
                size = appInfo.size
            ).toJson()

            val task = DownloadManager.download(url)
                .path(dir)
                .fileName("${appInfo.package_name}_${appInfo.version_code}.apk")
                .extras(extras)
                .estimatedSize(appInfo.size ?: 0L)
                .start()

            // 立即暂停，等待WiFi
            DownloadManager.pause(task.id)
            // 直接修改 appInfo.task（与 MainActivity 保持一致）
            appInfo.task = task.copy(status = DownloadStatus.PAUSED)
            // 触发列表刷新
            _appListInfoFlow.value = _appListInfoFlow.value.toMutableList()

            _uiEvent.emit(AppStoreUiEvent.ShowToast("已加入下载队列，等待WiFi连接"))
        }
    }

    /**
     * 等待网络下载：创建任务并暂停（无网络时使用）
     */
    fun startDownloadAndPauseForNetwork(context: Context, appInfo: UpdateAppInfo) {
        viewModelScope.launch {
            val url = appInfo.apk_url?.qiniuHostUrl ?: return@launch
            val dir = context.externalCacheDir?.absolutePath ?: context.cacheDir.absolutePath

            val extras = ExtraMeta(
                name = appInfo.app_name,
                packageName = appInfo.package_name,
                versionCode = appInfo.version_code,
                icon = appInfo.app_icon,
                size = appInfo.size
            ).toJson()

            val task = DownloadManager.download(url)
                .path(dir)
                .fileName("${appInfo.package_name}_${appInfo.version_code}.apk")
                .extras(extras)
                .estimatedSize(appInfo.size ?: 0L)
                .start()

            // 立即暂停，设置为网络异常原因
            DownloadManager.pauseTask(task.id, PauseReason.NETWORK_ERROR)
            // 直接修改 appInfo.task（与 MainActivity 保持一致）
            appInfo.task = task.copy(
                status = DownloadStatus.PAUSED,
                pauseReason = PauseReason.NETWORK_ERROR
            )
            // 触发列表刷新
            _appListInfoFlow.value = _appListInfoFlow.value.toMutableList()

            _uiEvent.emit(AppStoreUiEvent.ShowToast("已加入下载队列，等待网络连接"))
        }
    }

    /**
     * 暂停下载
     */
    fun pauseDownload(taskId: String) {
        DownloadManager.pause(taskId)
    }

    /**
     * 恢复下载
     */
    fun resumeDownload(taskId: String) {
        DownloadManager.resume(taskId)
    }

    /**
     * 更新列表中指定 URL 的任务
     */
    fun updateTaskInList(url: String?, task: DownloadTask) {
        android.util.Log.d("AppStore", "updateTaskInList: url=$url, task.status=${task.status}")
        if (url.isNullOrBlank()) {
            android.util.Log.d("AppStore", "  -> url 为空，返回")
            return
        }
        val list = _appListInfoFlow.value.toMutableList()
        val index = list.indexOfFirst { it.apk_url?.qiniuHostUrl == url }
        android.util.Log.d("AppStore", "  匹配 index=$index")
        if (index >= 0) {
            // 创建新对象确保 StateFlow 能检测到变化
            val oldItem = list[index]
            val newItem = oldItem.copy(task = task)
            list[index] = newItem
            _appListInfoFlow.value = list
            android.util.Log.d("AppStore", "  -> 更新成功, newItem.task.status=${newItem.task?.status}")
        } else {
            android.util.Log.d("AppStore", "  -> 找不到匹配项")
        }
    }

    /**
     * 根据任务 ID 更新列表
     */
    fun updateTaskById(task: DownloadTask) {
        val list = _appListInfoFlow.value.toMutableList()
        val index = list.indexOfFirst { it.task?.id == task.id || it.apk_url?.qiniuHostUrl == task.url }
        if (index >= 0) {
            list[index].task = task  // 直接修改，与 MainActivity 保持一致
            _appListInfoFlow.value = list
        }
    }
}

/**
 * AppStore UI 事件
 */
sealed class AppStoreUiEvent {
    data class ShowToast(val message: String) : AppStoreUiEvent()
    data class ShowCellularConfirmDialog(val appInfo: UpdateAppInfo, val totalSize: Long) : AppStoreUiEvent()
    data class ShowWifiOnlyDialog(val appInfo: UpdateAppInfo) : AppStoreUiEvent()
    data class ShowNoNetworkDialog(val appInfo: UpdateAppInfo) : AppStoreUiEvent()
}