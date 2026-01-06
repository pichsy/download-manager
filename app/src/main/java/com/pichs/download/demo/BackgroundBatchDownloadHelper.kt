package com.pichs.download.demo

import android.content.Context
import com.pichs.download.core.DownloadManager
import com.pichs.download.core.DownloadPriority
import com.pichs.download.model.CheckBeforeResult
import com.pichs.download.model.DownloadStatus
import com.pichs.download.model.PauseReason
import com.pichs.shanhai.base.api.ShanHaiApi
import com.pichs.shanhai.base.api.UpdateAppBody
import com.pichs.shanhai.base.api.entity.UpdateAppInfo
import com.pichs.shanhai.base.api.entity.qiniuHostUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 后台批量下载助手（单例）
 * 用于执行后台静默批量下载，不依赖任何 UI 组件
 * 需要弹窗时通过 DownloadUiEventManager 通知 MainActivity
 */
object BackgroundBatchDownloadHelper {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 执行后台批量下载
     * @param context Application Context
     * @param categoryType 分类类型，默认 "1,3" (TYPE_MUST_DOWNLOAD)
     */
    fun startBatchDownload(context: Context, categoryType: String = "1,3") {
        scope.launch {
            try {
                android.util.Log.d("BackgroundBatch", "开始后台批量下载，categoryType=$categoryType")

                // 1. 从服务器获取应用列表
                val response = ShanHaiApi.getApi().loadUpdateAppList(
                    UpdateAppBody(type = 0, category_type = categoryType)
                )

                val freshAppList = response?.result?.data
                if (freshAppList.isNullOrEmpty()) {
                    android.util.Log.d("BackgroundBatch", "服务器返回空列表，跳过")
                    return@launch
                }

                // 2. 同步已有任务状态
                val allTasks = DownloadManager.getAllTasks()
                freshAppList.forEach { appInfo ->
                    val fullUrl = appInfo.apk_url?.qiniuHostUrl
                    val matchedTask = allTasks.find { it.url == fullUrl }
                    appInfo.task = matchedTask
                }

                // 3. 过滤需要下载的应用（考虑已安装版本、任务状态、文件健康）
                val appsToDownload = freshAppList.filter { appInfo ->
                    val task = appInfo.task
                    val pkg = appInfo.package_name ?: ""
                    val storeVC = appInfo.version_code ?: 0L
                    
                    // 已安装且版本 >= 商店版本，跳过下载
                    if (pkg.isNotBlank() && AppUtils.isInstalledAndUpToDate(context, pkg, storeVC)) {
                        android.util.Log.d("BackgroundBatch", "跳过已安装最新版: ${appInfo.app_name}")
                        return@filter false
                    }
                    
                    // 下载任务已完成，检查文件健康
                    if (task?.status == DownloadStatus.COMPLETED) {
                        val health = AppUtils.checkFileHealth(task)
                        // 文件损坏或缺失，需要重新下载
                        if (health != AppUtils.FileHealth.OK) {
                            android.util.Log.d("BackgroundBatch", "文件损坏/缺失，重新下载: ${appInfo.app_name}")
                            return@filter true
                        }
                        // 文件健康，跳过
                        return@filter false
                    }
                    
                    // 正在下载/等待中，跳过
                    if (task?.status == DownloadStatus.DOWNLOADING || 
                        task?.status == DownloadStatus.WAITING ||
                        task?.status == DownloadStatus.PENDING ||
                        task?.status == DownloadStatus.PAUSED) {
                        return@filter false
                    }
                    
                    // 无任务 / 失败 / 取消，需要下载
                    task == null || task.status == DownloadStatus.FAILED || task.status == DownloadStatus.CANCELLED
                }

                if (appsToDownload.isEmpty()) {
                    android.util.Log.d("BackgroundBatch", "所有应用已下载完成，跳过")
                    return@launch
                }

                // 4. 计算总大小
                val totalSize = appsToDownload.sumOf { it.size ?: 0L }
                android.util.Log.d("BackgroundBatch", "需要下载 ${appsToDownload.size} 个应用, 总大小: $totalSize")

                // 5. 预检查
                when (val result = DownloadManager.checkBeforeCreate(totalSize, appsToDownload.size)) {
                    is CheckBeforeResult.Allow -> {
                        // 允许下载，直接开始（静默）
                        doStartBatchDownload(context, appsToDownload)
                    }

                    is CheckBeforeResult.NoNetwork -> {
                        // 无网络：通过全局事件弹窗
                        DownloadUiEventManager.emit(
                            DownloadUiEvent.ShowNoNetworkDialog(
                                totalSize = totalSize,
                                count = appsToDownload.size,
                                onConfirm = {
                                    scope.launch {
                                        startBatchDownloadAndPauseForNetwork(context, appsToDownload)
                                    }
                                }
                            )
                        )
                    }

                    is CheckBeforeResult.WifiOnly -> {
                        // 仅WiFi模式：通过全局事件弹窗
                        DownloadUiEventManager.emit(
                            DownloadUiEvent.ShowWifiOnlyDialog(
                                totalSize = totalSize,
                                count = appsToDownload.size,
                                onConfirm = {
                                    scope.launch {
                                        startBatchDownloadAndPause(context, appsToDownload)
                                    }
                                }
                            )
                        )
                    }

                    is CheckBeforeResult.NeedConfirmation -> {
                        // 需要确认：通过全局事件弹窗
                        DownloadUiEventManager.emit(
                            DownloadUiEvent.ShowCellularConfirmDialog(
                                totalSize = result.estimatedSize,
                                count = appsToDownload.size,
                                onConfirm = {
                                    scope.launch {
                                        doStartBatchDownload(context, appsToDownload, cellularConfirmed = true)
                                    }
                                }
                            )
                        )
                    }

                    is CheckBeforeResult.UserControlled -> {
                        if (CellularThresholdManager.shouldPrompt(result.estimatedSize)) {
                            DownloadUiEventManager.emit(
                                DownloadUiEvent.ShowCellularConfirmDialog(
                                    totalSize = result.estimatedSize,
                                    count = appsToDownload.size,
                                    onConfirm = {
                                        scope.launch {
                                            doStartBatchDownload(context, appsToDownload, cellularConfirmed = true)
                                        }
                                    }
                                )
                            )
                        } else {
                            // 未超阈值，静默下载
                            doStartBatchDownload(context, appsToDownload, cellularConfirmed = true)
                        }
                    }
                }

            } catch (e: Exception) {
                e.printStackTrace()
                android.util.Log.e("BackgroundBatch", "后台批量下载失败: ${e.message}")
            }
        }
    }

    /**
     * 实际执行批量下载
     */
    private fun doStartBatchDownload(
        context: Context,
        apps: List<UpdateAppInfo>,
        cellularConfirmed: Boolean = false
    ) {
        val dir = context.externalCacheDir?.absolutePath ?: context.cacheDir.absolutePath

        android.util.Log.d("BackgroundBatch", "开始创建 ${apps.size} 个批量下载任务")

        apps.forEach { appInfo ->
            val url = appInfo.apk_url?.qiniuHostUrl ?: return@forEach

            val extras = ExtraMeta(
                name = appInfo.app_name,
                packageName = appInfo.package_name,
                versionCode = appInfo.version_code,
                icon = appInfo.app_icon,
                size = appInfo.size
            ).toJson()

            val task = DownloadManager.downloadWithPriority(url, DownloadPriority.NORMAL)
                .path(dir)
                .fileName("${appInfo.package_name}_${appInfo.version_code}.apk")
                .extras(extras)
                .estimatedSize(appInfo.size ?: 0L)
                .cellularConfirmed(cellularConfirmed)
                .start()

            android.util.Log.d("BackgroundBatch", "任务创建: ${appInfo.app_name}, taskId=${task.id}")
        }
    }

    /**
     * 等待WiFi下载：创建任务并暂停
     */
    private fun startBatchDownloadAndPause(context: Context, apps: List<UpdateAppInfo>) {
        val dir = context.externalCacheDir?.absolutePath ?: context.cacheDir.absolutePath

        apps.forEach { appInfo ->
            val url = appInfo.apk_url?.qiniuHostUrl ?: return@forEach

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
        }

        android.util.Log.d("BackgroundBatch", "已创建 ${apps.size} 个任务并暂停，等待WiFi")
    }

    /**
     * 等待网络下载：创建任务并暂停
     */
    private fun startBatchDownloadAndPauseForNetwork(context: Context, apps: List<UpdateAppInfo>) {
        val dir = context.externalCacheDir?.absolutePath ?: context.cacheDir.absolutePath

        apps.forEach { appInfo ->
            val url = appInfo.apk_url?.qiniuHostUrl ?: return@forEach

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
        }

        android.util.Log.d("BackgroundBatch", "已创建 ${apps.size} 个任务并暂停，等待网络")
    }
}
