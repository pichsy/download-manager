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
        private const val KEY_CELLULAR_PROMPT_MODE = "cellular_prompt_mode"
        private const val KEY_CHECK_BEFORE_CREATE = "check_before_create"
    }
    
    private val prefs: SharedPreferences = 
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
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
        val promptModeStr = prefs.getString(KEY_CELLULAR_PROMPT_MODE, null)
        val promptMode = promptModeStr?.let { 
            runCatching { CellularPromptMode.valueOf(it) }.getOrNull() 
        } ?: CellularPromptMode.ALWAYS
        val checkBeforeCreate = prefs.getBoolean(KEY_CHECK_BEFORE_CREATE, true)
        return NetworkDownloadConfig(wifiOnly, promptMode, checkBeforeCreate)
    }
    
    private fun saveConfig(config: NetworkDownloadConfig) {
        prefs.edit()
            .putBoolean(KEY_WIFI_ONLY, config.wifiOnly)
            .putString(KEY_CELLULAR_PROMPT_MODE, config.cellularPromptMode.name)
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
        
        // 检查流量提醒策略
        return checkCellularDownloadPermission()
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
        
        // 根据提醒模式决定（任务级别确认在后置检查判断）
        return when (config.cellularPromptMode) {
            CellularPromptMode.ALWAYS -> {
                CheckBeforeResult.NeedConfirmation(totalSize)
            }
            CellularPromptMode.NEVER -> {
                CheckBeforeResult.Allow
            }
            CellularPromptMode.USER_CONTROLLED -> {
                // 交给使用端判断阈值
                CheckBeforeResult.UserControlled(totalSize)
            }
        }
    }
    
    private fun checkCellularDownloadPermission(): CheckAfterResult {
        // 根据提醒模式决定（任务级别的 cellularConfirmed 已在 checkCanDownload 中判断）
        return when (config.cellularPromptMode) {
            CellularPromptMode.ALWAYS -> {
                // 每次提醒，需要确认
                CheckAfterResult.NeedConfirmation
            }
            CellularPromptMode.NEVER -> {
                // 不再提醒，直接允许
                CheckAfterResult.Allow
            }
            CellularPromptMode.USER_CONTROLLED -> {
                // 交给用户：检查是否已放行，未放行则拒绝（不弹窗）
                CheckAfterResult.Deny(DenyReason.USER_CONTROLLED_NOT_ALLOWED)
            }
        }
    }
    
    /**
     * 获取任务的有效大小（优先使用 estimatedSize，否则使用 totalSize）
     */
    private fun getTaskEffectiveSize(task: DownloadTask): Long {
        return if (task.estimatedSize > 0) task.estimatedSize else task.totalSize
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
                // 根据提醒模式决定
                when (config.cellularPromptMode) {
                    CellularPromptMode.ALWAYS -> {
                        // 每次提醒，通过回调弹窗
                        mainHandler.post {
                            checkAfterCallback?.requestBatchCellularConfirmation(
                                totalSize = totalSize,
                                taskCount = taskCount,
                                onConnectWifi = { onDeny() },
                                onUseCellular = {
                                    // 前置检查通过，任务创建时会标记 cellularConfirmed
                                    onAllow()
                                }
                            ) ?: run {
                                DownloadLog.w(TAG, "未设置 decisionCallback，默认拒绝批量流量下载")
                                onDeny()
                            }
                        }
                    }
                    CellularPromptMode.NEVER -> {
                        // 不再提醒，直接允许
                        onAllow()
                    }
                    CellularPromptMode.USER_CONTROLLED -> {
                        // 交给用户：未放行则拒绝
                        onDeny()
                    }
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
        } else if (config.cellularPromptMode == CellularPromptMode.ALWAYS) {
            // 有流量 + 每次提醒，检查活跃任务是否已确认使用流量
            val activeTasks = getActiveDownloadTasks()
            val unconfirmedTasks = activeTasks.filter { !it.cellularConfirmed }
            if (unconfirmedTasks.isNotEmpty()) {
                // 未确认的任务需要弹窗确认
                requestCellularConfirmation(unconfirmedTasks) {
                    // 用户确认后任务会自动继续
                }
            }
            // 已确认的任务继续下载
        }
        // else: 有流量 + NEVER模式，继续下载
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
        InMemoryTaskStore.getAll()
            .filter { it.status == DownloadStatus.PAUSED && it.pauseReason == PauseReason.WIFI_UNAVAILABLE }
            .forEach { task ->
                downloadManager.resume(task.id)
            }
    }
    
    private fun resumeCellularPendingTasks() {
        InMemoryTaskStore.getAll()
            .filter { it.status == DownloadStatus.PAUSED && it.pauseReason == PauseReason.CELLULAR_PENDING }
            .forEach { task ->
                downloadManager.resume(task.id)
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
    WIFI_ONLY_MODE,
    /** 交给用户模式下未放行 */
    USER_CONTROLLED_NOT_ALLOWED
}
