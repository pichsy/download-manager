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
    }
    
    private val prefs: SharedPreferences = 
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    private val mainHandler = Handler(Looper.getMainLooper())
    
    /** 决策回调（使用端设置） */
    var decisionCallback: DownloadDecisionCallback? = null
    
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
        return NetworkDownloadConfig(wifiOnly, promptMode)
    }
    
    private fun saveConfig(config: NetworkDownloadConfig) {
        prefs.edit()
            .putBoolean(KEY_WIFI_ONLY, config.wifiOnly)
            .putString(KEY_CELLULAR_PROMPT_MODE, config.cellularPromptMode.name)
            .apply()
        DownloadLog.d(TAG, "配置已保存: $config")
    }
    
    // ==================== 规则判断 ====================
    
    /**
     * 检查任务是否可以开始下载
     */
    fun checkCanDownload(task: DownloadTask): DownloadDecision {
        val isWifi = NetworkUtils.isWifiAvailable(context)
        val isCellular = NetworkUtils.isCellularAvailable(context)
        
        DownloadLog.d(TAG, "检查下载权限: task=${task.fileName}, wifi=$isWifi, cellular=$isCellular")
        
        // 无网络
        if (!isWifi && !isCellular) {
            return DownloadDecision.Deny(DenyReason.NO_NETWORK)
        }
        
        // WiFi 可用，直接允许
        if (isWifi) {
            return DownloadDecision.Allow
        }
        
        // 以下是流量网络的情况
        return if (config.wifiOnly) {
            // 仅 WiFi 模式，拒绝流量下载
            DownloadDecision.Deny(DenyReason.WIFI_ONLY_MODE)
        } else {
            // 允许流量模式，检查提醒策略
            checkCellularDownloadPermission()
        }
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
        
        // 已经临时放行
        if (CellularSessionManager.isCellularDownloadAllowed()) {
            return CheckBeforeResult.Allow
        }
        
        // 根据提醒模式决定
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
    
    private fun checkCellularDownloadPermission(): DownloadDecision {
        // 已经临时放行
        if (CellularSessionManager.isCellularDownloadAllowed()) {
            return DownloadDecision.Allow
        }
        
        // 根据提醒模式决定
        return when (config.cellularPromptMode) {
            CellularPromptMode.ALWAYS -> {
                // 每次提醒，需要确认
                DownloadDecision.NeedConfirmation
            }
            CellularPromptMode.NEVER -> {
                // 不再提醒，直接允许
                DownloadDecision.Allow
            }
            CellularPromptMode.USER_CONTROLLED -> {
                // 交给用户：检查是否已放行，未放行则拒绝（不弹窗）
                DownloadDecision.Deny(DenyReason.USER_CONTROLLED_NOT_ALLOWED)
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
            decisionCallback?.requestCellularConfirmation(
                pendingTasks = tasks,
                totalSize = totalSize,
                onConnectWifi = {
                    // 暂停所有待确认任务
                    tasks.forEach { task ->
                        downloadManager.pauseTask(task.id, PauseReason.CELLULAR_PENDING)
                    }
                },
                onUseCellular = {
                    // 标记会话放行
                    CellularSessionManager.allowCellularDownload()
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
    fun checkBatchDownloadPermission(
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
                    decisionCallback?.showWifiOnlyHint(null)
                }
                onDeny()
            }
            // 允许流量 + 已放行
            CellularSessionManager.isCellularDownloadAllowed() -> {
                onAllow()
            }
            else -> {
                // 根据提醒模式决定
                when (config.cellularPromptMode) {
                    CellularPromptMode.ALWAYS -> {
                        // 每次提醒，通过回调弹窗
                        mainHandler.post {
                            decisionCallback?.requestBatchCellularConfirmation(
                                totalSize = totalSize,
                                taskCount = taskCount,
                                onConnectWifi = { onDeny() },
                                onUseCellular = {
                                    CellularSessionManager.allowCellularDownload()
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
            decisionCallback?.showWifiOnlyHint(task) ?: run {
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
        // 重置流量会话
        CellularSessionManager.reset()
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
        
        if (config.wifiOnly) {
            // 仅 WiFi 模式，暂停所有任务
            val count = pauseAllForWifiUnavailable()
            mainHandler.post {
                decisionCallback?.showWifiDisconnectedHint(count)
            }
        } else if (!CellularSessionManager.isCellularDownloadAllowed() && config.cellularPromptMode == CellularPromptMode.ALWAYS) {
            // 允许流量 + 未放行 + 每次提醒，需要确认
            val activeTasks = getActiveDownloadTasks()
            if (activeTasks.isNotEmpty()) {
                requestCellularConfirmation(activeTasks) {
                    // 用户确认后任务会自动继续
                }
            }
        }
        // else: 已放行 / 不提醒 / 交给用户，继续下载或由使用端处理
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
sealed class DownloadDecision {
    /** 允许下载 */
    object Allow : DownloadDecision()
    
    /** 需要用户确认 */
    object NeedConfirmation : DownloadDecision()
    
    /** 拒绝下载 */
    data class Deny(val reason: DenyReason) : DownloadDecision()
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
