package com.pichs.download.core

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import com.pichs.download.model.*
import com.pichs.download.store.InMemoryTaskStore
import com.pichs.download.utils.DownloadLog
import com.pichs.download.utils.NetworkUtils

/**
 * 网络规则管理器
 * 负责配置存储、规则判断、决策触发
 */
class NetworkRuleManager(
    private val context: Context,
    private val downloadManager: DownloadManager
) {
    companion object {
        private const val TAG = "NetworkRuleManager"
        private const val PREFS_NAME = "download_network_config"
        private const val KEY_WIFI_ONLY = "wifi_only"
        private const val KEY_CELLULAR_THRESHOLD = "cellular_threshold"
        private const val KEY_CHECK_BEFORE_CREATE = "check_before_create"
    }
    
    private val prefs: SharedPreferences = context.createDeviceProtectedStorageContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    private val mainHandler = Handler(Looper.getMainLooper())
    
    /** 决策回调（使用端设置） */
    var checkAfterCallback: CheckAfterCallback? = null
    
    /** 当前配置 */
    var config: NetworkDownloadConfig = loadConfig()
        set(value) {
            field = value
            saveConfig(value)
        }
    
    // ==================== 配置持久化 ====================
    
    private fun loadConfig(): NetworkDownloadConfig {
        val wifiOnly = prefs.getBoolean(KEY_WIFI_ONLY, false)
        val cellularThreshold = prefs.getLong(KEY_CELLULAR_THRESHOLD, CellularThreshold.ALWAYS_PROMPT)
        val checkBeforeCreate = prefs.getBoolean(KEY_CHECK_BEFORE_CREATE, false)
        return NetworkDownloadConfig(wifiOnly, cellularThreshold, checkBeforeCreate)
    }
    
    private fun saveConfig(config: NetworkDownloadConfig) {
        prefs.edit()
            .putBoolean(KEY_WIFI_ONLY, config.wifiOnly)
            .putLong(KEY_CELLULAR_THRESHOLD, config.cellularThreshold)
            .putBoolean(KEY_CHECK_BEFORE_CREATE, config.checkBeforeCreate)
            .apply()
        DownloadLog.d(TAG, "配置已保存: $config")
    }
    
    // ==================== 规则判断 ====================
    
    /**
     * 检查任务是否可以开始下载
     */
    fun checkCanDownload(task: DownloadTask): CheckAfterResult {
        val isWifi = NetworkUtils.isWifiAvailable(context)
        val isCellular = NetworkUtils.isCellularAvailable(context)
        
        DownloadLog.d(TAG, "检查下载权限: task=${task.fileName}, wifi=$isWifi, cellular=$isCellular, cellularConfirmed=${task.cellularConfirmed}")
        
        // 无网络
        if (!isWifi && !isCellular) {
            return CheckAfterResult.Deny(DenyReason.NO_NETWORK)
        }
        
        // WiFi 可用，直接允许
        if (isWifi) {
            return CheckAfterResult.Allow
        }
        
        // 以下是流量网络的情况
        
        // 仅 WiFi 模式，拒绝流量下载
        if (config.wifiOnly) {
            return CheckAfterResult.Deny(DenyReason.WIFI_ONLY_MODE)
        }
        
        // 任务级别确认：前置检查已确认使用流量，直接放行
        if (task.cellularConfirmed) {
            DownloadLog.d(TAG, "任务已确认使用流量，直接放行: ${task.fileName}")
            return CheckAfterResult.Allow
        }
        
        // 检查流量提醒策略（需要传入任务大小）
        val taskSize = getTaskEffectiveSize(task)
        return checkCellularDownloadPermission(taskSize)
    }
    
    // ==================== 预检查 API（先决策后创建任务） ====================
    
    /**
     * 创建前检查（在创建任务前调用）
     * 用于实现"先决策后创建任务"的流程
     * @param totalSize 预估下载总大小（字节）
     * @param count 任务数量
     * @return 检查结果
     */
    fun checkBeforeCreate(totalSize: Long, count: Int = 1): CheckBeforeResult {
        // 如果未启用创建前检查，直接返回 Allow
        if (!config.checkBeforeCreate) {
            DownloadLog.d(TAG, "创建前检查未启用，直接允许下载")
            return CheckBeforeResult.Allow
        }
        
        val isWifi = NetworkUtils.isWifiAvailable(context)
        val isCellular = NetworkUtils.isCellularAvailable(context)
        
        DownloadLog.d(TAG, "创建前检查: wifi=$isWifi, cellular=$isCellular, size=$totalSize, count=$count")
        
        // 无网络
        if (!isWifi && !isCellular) {
            return CheckBeforeResult.NoNetwork
        }
        
        // WiFi 可用，直接允许
        if (isWifi) {
            return CheckBeforeResult.Allow
        }
        
        // 仅 WiFi 模式
        if (config.wifiOnly) {
            return CheckBeforeResult.WifiOnly
        }
        
        // 根据阈值判断
        val threshold = config.cellularThreshold
        return when {
            // 不提醒：直接允许
            threshold == CellularThreshold.NEVER_PROMPT -> CheckBeforeResult.Allow
            // 每次提醒 或 超过阈值：需要确认
            threshold == CellularThreshold.ALWAYS_PROMPT || totalSize >= threshold -> {
                CheckBeforeResult.NeedConfirmation(totalSize)
            }
            // 未超阈值：静默允许
            else -> CheckBeforeResult.Allow
        }
    }
    
    private fun checkCellularDownloadPermission(totalSize: Long): CheckAfterResult {
        val threshold = config.cellularThreshold
        return when {
            // 不提醒：直接允许
            threshold == CellularThreshold.NEVER_PROMPT -> CheckAfterResult.Allow
            // 每次提醒 或 超过阈值：需要确认
            threshold == CellularThreshold.ALWAYS_PROMPT || totalSize >= threshold -> {
                CheckAfterResult.NeedConfirmation
            }
            // 未超阈值：静默允许
            else -> CheckAfterResult.Allow
        }
    }
    
    /**
     * 获取任务的有效大小（用于流量判断）
     * 优先级：剩余待下载大小 > 预估大小 > 总大小
     */
    private fun getTaskEffectiveSize(task: DownloadTask): Long {
        return when {
            // 1. 优先使用剩余大小（最准确）- 适用于恢复任务、WiFi断开等场景
            task.totalSize > 0 && task.currentSize >= 0 -> {
                (task.totalSize - task.currentSize).coerceAtLeast(0)
            }
            // 2. 其次使用预估大小（创建任务时 totalSize 还未获取）
            task.estimatedSize > 0 -> task.estimatedSize
            // 3. 最后使用总大小（兜底）
            else -> task.totalSize
        }
    }
    
    // ==================== 决策执行 ====================
    
    /**
     * 执行流量确认流程
     */
    fun requestCellularConfirmation(
        tasks: List<DownloadTask>,
        onAllowed: () -> Unit
    ) {
        val totalSize = tasks.sumOf { getTaskEffectiveSize(it) }
        
        mainHandler.post {
            checkAfterCallback?.requestCellularConfirmation(
                pendingTasks = tasks,
                totalSize = totalSize,
                onConnectWifi = {
                    // 暂停所有待确认任务
                    tasks.forEach { task ->
                        downloadManager.pauseTask(task.id, PauseReason.CELLULAR_PENDING)
                    }
                },
                onUseCellular = {
                    // 标记任务已确认使用流量
                    tasks.forEach { task ->
                        downloadManager.updateTaskCellularConfirmed(task.id, true)
                    }
                    // 恢复所有 CELLULAR_PENDING 的任务
                    resumeCellularPendingTasks()
                    // 执行允许回调
                    onAllowed()
                }
            ) ?: run {
                // 未设置回调，默认拒绝
                DownloadLog.w(TAG, "未设置 decisionCallback，默认拒绝流量下载")
                tasks.forEach { task ->
                    downloadManager.pauseTask(task.id, PauseReason.CELLULAR_PENDING)
                }
            }
        }
    }
    
    /**
     * 批量下载前置检查
     */
    fun checkBeforeBatchDownloadPermission(
        totalSize: Long,
        taskCount: Int,
        onAllow: () -> Unit,
        onDeny: () -> Unit
    ) {
        val isWifi = NetworkUtils.isWifiAvailable(context)
        
        when {
            // WiFi 可用，直接允许
            isWifi -> {
                onAllow()
            }
            // 仅 WiFi 模式，拒绝
            config.wifiOnly -> {
                mainHandler.post {
                    checkAfterCallback?.showWifiOnlyHint(null)
                }
                onDeny()
            }
            else -> {
                // 根据阈值判断
                val threshold = config.cellularThreshold
                when {
                    // 不提醒：直接允许
                    threshold == CellularThreshold.NEVER_PROMPT -> onAllow()
                    // 每次提醒 或 超过阈值：弹窗确认
                    threshold == CellularThreshold.ALWAYS_PROMPT || totalSize >= threshold -> {
                        mainHandler.post {
                            checkAfterCallback?.requestBatchCellularConfirmation(
                                totalSize = totalSize,
                                taskCount = taskCount,
                                onConnectWifi = { onDeny() },
                                onUseCellular = { onAllow() }
                            ) ?: run {
                                DownloadLog.w(TAG, "未设置 decisionCallback，默认拒绝批量流量下载")
                                onDeny()
                            }
                        }
                    }
                    // 未超阈值：静默允许
                    else -> onAllow()
                }
            }
        }
    }
    
    /**
     * 显示仅 WiFi 提示
     */
    fun showWifiOnlyHint(task: DownloadTask?) {
        mainHandler.post {
            checkAfterCallback?.showWifiOnlyHint(task) ?: run {
                DownloadLog.w(TAG, "未设置 decisionCallback，无法显示 WiFi 提示")
            }
        }
    }
    
    // ==================== 事件处理 ====================
    
    /**
     * WiFi 连接事件
     */
    fun onWifiConnected() {
        DownloadLog.d(TAG, "WiFi 已连接")
        // 恢复因 WiFi 不可用暂停的任务
        resumeWifiUnavailableTasks()
        // 恢复等待流量确认的任务（现在有 WiFi 了）
        resumeCellularPendingTasks()
        // 恢复因网络异常暂停的任务（WiFi 连接也是网络恢复）
        resumeNetworkErrorTasks()
        // 恢复其他系统原因暂停的任务（电量、存储、资源等）
        resumeOtherSystemPausedTasks()
    }
    
    /**
     * WiFi 断开事件
     */
    fun onWifiDisconnected() {
        DownloadLog.d(TAG, "WiFi 已断开")
        
        // 首先检查是否有流量网络可用
        val isCellular = NetworkUtils.isCellularAvailable(context)
        
        if (config.wifiOnly) {
            // 仅 WiFi 模式，暂停所有任务
            val count = pauseAllForWifiUnavailable()
            mainHandler.post {
                checkAfterCallback?.showWifiDisconnectedHint(count)
            }
        } else if (!isCellular) {
            // WiFi断开且无流量，暂停任务等待网络恢复
            val count = pauseAllForNetworkUnavailable()
            val tasks = getActiveDownloadTasks()
            val totalSize = tasks.sumOf { it.totalSize }
            // 通知使用端，让使用端决定如何展示（Toast、弹窗或不处理）
            mainHandler.post {
                checkAfterCallback?.requestConfirmation(
                    scenario = NetworkScenario.NO_NETWORK,
                    pendingTasks = tasks,
                    totalSize = totalSize,
                    onConnectWifi = { /* 用户选择去连接WiFi */ },
                    onUseCellular = { /* 等待网络后自动恢复 */ }
                )
            }
            DownloadLog.d(TAG, "WiFi断开且无流量，任务已暂停等待网络恢复")
        } else {
            // 有流量网络可用
            val activeTasks = getActiveDownloadTasks()
            val unconfirmedTasks = activeTasks.filter { !it.cellularConfirmed }
            
            if (unconfirmedTasks.isNotEmpty()) {
                // 关键修复：先暂停所有未确认流量的任务，防止它们在用户确认前继续用流量下载
                unconfirmedTasks.forEach { task ->
                    downloadManager.pauseTask(task.id, PauseReason.CELLULAR_PENDING)
                }
                DownloadLog.d(TAG, "WiFi断开，暂停 ${unconfirmedTasks.size} 个未确认流量的任务")
                
                // 根据阈值判断是否弹窗
                val totalSize = unconfirmedTasks.sumOf { getTaskEffectiveSize(it) }
                val threshold = config.cellularThreshold
                when {
                    // 不提醒：直接恢复
                    threshold == CellularThreshold.NEVER_PROMPT -> {
                        unconfirmedTasks.forEach { task ->
                            downloadManager.updateTaskCellularConfirmed(task.id, true)
                            downloadManager.resume(task.id)
                        }
                        DownloadLog.d(TAG, "不提醒模式，自动确认并恢复 ${unconfirmedTasks.size} 个任务")
                    }
                    // 每次提醒 或 超过阈值：弹窗确认
                    threshold == CellularThreshold.ALWAYS_PROMPT || totalSize >= threshold -> {
                        requestCellularConfirmation(unconfirmedTasks) {
                            // 用户确认后任务会自动继续
                        }
                    }
                    // 未超阈值：静默恢复
                    else -> {
                        unconfirmedTasks.forEach { task ->
                            downloadManager.updateTaskCellularConfirmed(task.id, true)
                            downloadManager.resume(task.id)
                        }
                        DownloadLog.d(TAG, "未超阈值，自动确认并恢复 ${unconfirmedTasks.size} 个任务")
                    }
                }
            }
            // 已确认流量的任务（cellularConfirmed = true）继续下载
        }
    }
    
    private fun getActiveDownloadTasks(): List<DownloadTask> {
        return InMemoryTaskStore.getAll().filter {
            it.status == DownloadStatus.DOWNLOADING ||
            it.status == DownloadStatus.WAITING ||
            it.status == DownloadStatus.PENDING
        }
    }
    
    private fun pauseAllForWifiUnavailable(): Int {
        val tasks = getActiveDownloadTasks()
        tasks.forEach { task ->
            downloadManager.pauseTask(task.id, PauseReason.WIFI_UNAVAILABLE)
        }
        return tasks.size
    }
    
    private fun pauseAllForNetworkUnavailable(): Int {
        val tasks = getActiveDownloadTasks()
        tasks.forEach { task ->
            downloadManager.pauseTask(task.id, PauseReason.NETWORK_ERROR)
        }
        return tasks.size
    }
    
    private fun resumeWifiUnavailableTasks() {
        val tasks = InMemoryTaskStore.getAll()
            .filter { it.status == DownloadStatus.PAUSED && it.pauseReason == PauseReason.WIFI_UNAVAILABLE }
        
        if (tasks.isNotEmpty()) {
            downloadManager.resumeTasks(tasks)
            DownloadLog.d(TAG, "批量恢复 ${tasks.size} 个 WiFi 不可用暂停的任务")
        }
    }
    
    private fun resumeCellularPendingTasks() {
        val pendingTasks = InMemoryTaskStore.getAll()
            .filter { it.status == DownloadStatus.PAUSED && it.pauseReason == PauseReason.CELLULAR_PENDING }
        
        if (pendingTasks.isNotEmpty()) {
            // 批量恢复，只触发一次确认弹窗
            downloadManager.resumeTasks(pendingTasks)
            DownloadLog.d(TAG, "批量恢复 ${pendingTasks.size} 个等待流量确认的任务")
        }
    }
    
    private fun resumeNetworkErrorTasks() {
        val tasks = InMemoryTaskStore.getAll()
            .filter { it.status == DownloadStatus.PAUSED && it.pauseReason == PauseReason.NETWORK_ERROR }
        
        if (tasks.isNotEmpty()) {
            downloadManager.resumeTasks(tasks)
            DownloadLog.d(TAG, "批量恢复 ${tasks.size} 个网络异常暂停的任务")
        }
    }
    
    private fun resumeOtherSystemPausedTasks() {
        val tasks = InMemoryTaskStore.getAll()
            .filter { 
                it.status == DownloadStatus.PAUSED && 
                (it.pauseReason == PauseReason.BATTERY_LOW || 
                 it.pauseReason == PauseReason.STORAGE_FULL || 
                 it.pauseReason == PauseReason.SYSTEM_RESOURCE_LOW)
            }
        
        if (tasks.isNotEmpty()) {
            downloadManager.resumeTasks(tasks)
            DownloadLog.d(TAG, "批量恢复 ${tasks.size} 个系统原因暂停的任务")
        }
    }
}

/**
 * 下载决策结果
 */
sealed class CheckAfterResult {
    /** 允许下载 */
    object Allow : CheckAfterResult()
    
    /** 需要用户确认 */
    object NeedConfirmation : CheckAfterResult()
    
    /** 拒绝下载 */
    data class Deny(val reason: DenyReason) : CheckAfterResult()
}

/**
 * 拒绝原因
 */
enum class DenyReason {
    NO_NETWORK,
    WIFI_ONLY_MODE
}
