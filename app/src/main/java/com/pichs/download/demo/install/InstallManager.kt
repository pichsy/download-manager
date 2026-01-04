package com.pichs.download.demo.install

import android.os.Handler
import android.os.Looper
import com.pichs.download.demo.MDMController
import com.pichs.shanhai.base.utils.LogUtils
import com.pichs.xbase.utils.SysOsUtils
import com.pichs.xbase.utils.UiKit
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 安装队列管理器
 * 串行安装，一个一个执行，防止系统卡死
 */
object InstallManager {

    private const val TAG = "InstallManager"
    
    // 安装队列
    private val installQueue = ConcurrentLinkedQueue<InstallTask>()
    
    // 当前正在安装的任务
    private var currentTask: InstallTask? = null
    
    // 是否正在安装
    private var isInstalling = false
    
    // 超时检测 Handler
    private val timeoutHandler = Handler(Looper.getMainLooper())
    
    // 超时检测间隔（20秒）
    private const val TIMEOUT_INTERVAL = 20_000L
    
    // 最大重试次数
    private const val MAX_RETRY_COUNT = 3
    
    // 安装监听器列表
    private val listeners = mutableListOf<InstallListener>()
    
    /**
     * 安装监听器
     */
    interface InstallListener {
        fun onInstallStatusChanged(packageName: String, status: InstallStatus)
    }
    
    /**
     * 添加监听器
     */
    fun addListener(listener: InstallListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }
    
    /**
     * 移除监听器
     */
    fun removeListener(listener: InstallListener) {
        listeners.remove(listener)
    }
    
    /**
     * 添加安装任务到队列
     */
    fun addToQueue(task: InstallTask) {
        LogUtils.d(TAG, "添加安装任务: ${task.packageName}, apkPath: ${task.apkPath}")
        
        // 检查是否已在队列中
        val exists = installQueue.any { it.packageName == task.packageName } 
            || currentTask?.packageName == task.packageName
        
        if (exists) {
            LogUtils.d(TAG, "任务已在队列中: ${task.packageName}")
            return
        }
        
        // 如果队列为空且没有正在安装，直接开始安装（跳过 PENDING 状态）
        if (installQueue.isEmpty() && !isInstalling) {
            currentTask = task
            isInstalling = true
            task.status = InstallStatus.INSTALLING
            
            LogUtils.d(TAG, "开始安装: ${task.packageName}")
            notifyListeners(task.packageName, InstallStatus.INSTALLING)
            
            MDMController.installSilent(task.apkPath)
            startTimeoutCheck()
        } else {
            // 队列不为空，加入队列等待
            installQueue.offer(task)
            notifyListeners(task.packageName, InstallStatus.PENDING)
        }
    }
    
    /**
     * 处理下一个安装任务
     */
    private fun processNext() {
        if (isInstalling) {
            LogUtils.d(TAG, "当前有任务正在安装，等待...")
            return
        }
        
        val task = installQueue.poll()
        if (task == null) {
            LogUtils.d(TAG, "安装队列已空")
            currentTask = null
            return
        }
        
        currentTask = task
        isInstalling = true
        task.status = InstallStatus.INSTALLING
        
        LogUtils.d(TAG, "开始安装: ${task.packageName}")
        notifyListeners(task.packageName, InstallStatus.INSTALLING)
        
        // 调用静默安装
        MDMController.installSilent(task.apkPath)
        
        // 启动超时检测
        startTimeoutCheck()
    }
    
    /**
     * 启动超时检测
     */
    private fun startTimeoutCheck() {
        timeoutHandler.removeCallbacks(timeoutRunnable)
        timeoutHandler.postDelayed(timeoutRunnable, TIMEOUT_INTERVAL)
    }
    
    /**
     * 停止超时检测
     */
    private fun stopTimeoutCheck() {
        timeoutHandler.removeCallbacks(timeoutRunnable)
    }
    
    /**
     * 超时检测 Runnable
     */
    private val timeoutRunnable = object : Runnable {
        override fun run() {
            currentTask?.let { task ->
                LogUtils.d(TAG, "超时检测: ${task.packageName}, 重试次数: ${task.retryCount}")
                
                // 检测本地版本号
                val localVC = SysOsUtils.getVersionCode(UiKit.getApplication(), task.packageName)
                
                if (localVC >= task.versionCode) {
                    // 安装成功
                    LogUtils.d(TAG, "超时检测发现安装成功: ${task.packageName}")
                    onInstallSuccess(task.packageName)
                } else {
                    task.retryCount++
                    if (task.retryCount >= MAX_RETRY_COUNT) {
                        // 失败3次，移除队列，让用户手动重试
                        LogUtils.d(TAG, "安装超时3次，移除队列: ${task.packageName}")
                        task.status = InstallStatus.FAILED
                        notifyListeners(task.packageName, InstallStatus.FAILED)
                        
                        // 处理下一个（不再加回队尾）
                        isInstalling = false
                        currentTask = null
                        processNext()
                    } else {
                        // 继续等待，20秒后再检测
                        LogUtils.d(TAG, "继续等待安装: ${task.packageName}, 第${task.retryCount}次检测")
                        timeoutHandler.postDelayed(this, TIMEOUT_INTERVAL)
                    }
                }
            }
        }
    }
    
    /**
     * 安装成功回调（由广播接收器调用）
     */
    fun onInstallSuccess(packageName: String) {
        LogUtils.d(TAG, "安装成功: $packageName")
        
        if (currentTask?.packageName == packageName) {
            stopTimeoutCheck()
            currentTask?.status = InstallStatus.SUCCESS
            notifyListeners(packageName, InstallStatus.SUCCESS)
            
            // 处理下一个
            isInstalling = false
            currentTask = null
            processNext()
        }
    }
    
    /**
     * 检查包名是否在安装队列中或正在安装
     */
    fun isInQueue(packageName: String): Boolean {
        return installQueue.any { it.packageName == packageName } 
            || currentTask?.packageName == packageName
    }
    
    /**
     * 获取安装状态
     */
    fun getInstallStatus(packageName: String): InstallStatus? {
        if (currentTask?.packageName == packageName) {
            return currentTask?.status
        }
        return installQueue.find { it.packageName == packageName }?.status
    }
    
    /**
     * 通知所有监听器
     */
    private fun notifyListeners(packageName: String, status: InstallStatus) {
        listeners.forEach { listener ->
            try {
                listener.onInstallStatusChanged(packageName, status)
            } catch (e: Exception) {
                LogUtils.e(TAG, "通知监听器失败: ${e.message}", e)
            }
        }
    }
    
    /**
     * 清空队列
     */
    fun clear() {
        stopTimeoutCheck()
        installQueue.clear()
        currentTask = null
        isInstalling = false
    }
}
