package com.pichs.download.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.ContextCompat
import com.pichs.download.utils.DownloadLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 网络状态监听器
 * 使用广播监听网络连接状态变化，通知 NetworkAutoResumeManager
 */
class NetworkStateListener(
    private val context: Context,
    private val networkAutoResumeManager: NetworkAutoResumeManager
) {
    
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var isListening = false
    
    private val networkReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ConnectivityManager.CONNECTIVITY_ACTION) {
                val isConnected = isNetworkAvailable()
                DownloadLog.d("NetworkStateListener", "网络状态变化: isConnected=$isConnected")
                
                scope.launch {
                    networkAutoResumeManager.onNetworkStateChanged(isConnected)
                }
            }
        }
    }
    
    /**
     * 开始监听网络状态
     */
    fun startListening() {
        if (isListening) {
            DownloadLog.d("NetworkStateListener", "网络监听已经在运行")
            return
        }
        
        try {
            val intentFilter = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
            ContextCompat.registerReceiver(
                context,
                networkReceiver,
                intentFilter,
                ContextCompat.RECEIVER_EXPORTED
            )
            isListening = true
            DownloadLog.d("NetworkStateListener", "开始监听网络状态广播")
        } catch (e: Exception) {
            DownloadLog.e("NetworkStateListener", "注册网络广播监听失败", e)
        }
    }
    
    /**
     * 停止监听网络状态
     */
    fun stopListening() {
        if (!isListening) {
            DownloadLog.d("NetworkStateListener", "网络监听未在运行")
            return
        }
        
        try {
            context.unregisterReceiver(networkReceiver)
            isListening = false
            DownloadLog.d("NetworkStateListener", "停止监听网络状态广播")
        } catch (e: Exception) {
            DownloadLog.e("NetworkStateListener", "取消网络广播监听失败", e)
        }
    }
    
    /**
     * 检查当前网络状态
     */
    fun isNetworkAvailable(): Boolean {
        return try {
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (e: Exception) {
            DownloadLog.e("NetworkStateListener", "检查网络状态失败", e)
            false
        }
    }
}
