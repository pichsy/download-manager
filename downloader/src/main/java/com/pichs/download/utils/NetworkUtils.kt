package com.pichs.download.utils

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build

/**
 * 网络工具类
 * 提供网络状态检查功能，供下载库内部使用
 */
object NetworkUtils {
    
    /**
     * 检查网络是否可用
     * @param context 上下文
     * @return true 如果网络可用，false 否则
     */
    @SuppressLint("WrongConstant")
    fun isNetworkAvailable(context: Context): Boolean {
        return try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = connectivityManager.activeNetwork
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } else {
                @Suppress("DEPRECATION")
                val networkInfo = connectivityManager.activeNetworkInfo
                networkInfo?.isConnected == true
            }
        } catch (e: Exception) {
            DownloadLog.e("NetworkUtils", "检查网络状态失败", e)
            false
        }
    }
    
    /**
     * 检查是否连接到WiFi网络
     * @param context 上下文
     * @return true 如果连接到WiFi，false 否则
     */
    @SuppressLint("WrongConstant")
    fun isWifiAvailable(context: Context): Boolean {
        return try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = connectivityManager.activeNetwork
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            } else {
                @Suppress("DEPRECATION")
                val networkInfo = connectivityManager.activeNetworkInfo
                networkInfo?.type == ConnectivityManager.TYPE_WIFI && networkInfo.isConnected
            }
        } catch (e: Exception) {
            DownloadLog.e("NetworkUtils", "检查WiFi状态失败", e)
            false
        }
    }
    
    /**
     * 检查是否连接到移动网络
     * @param context 上下文
     * @return true 如果连接到移动网络，false 否则
     */
    @SuppressLint("WrongConstant")
    fun isCellularAvailable(context: Context): Boolean {
        return try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = connectivityManager.activeNetwork
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
            } else {
                @Suppress("DEPRECATION")
                val networkInfo = connectivityManager.activeNetworkInfo
                networkInfo?.type == ConnectivityManager.TYPE_MOBILE && networkInfo.isConnected
            }
        } catch (e: Exception) {
            DownloadLog.e("NetworkUtils", "检查移动网络状态失败", e)
            false
        }
    }
    
    /**
     * 检查是否连接到以太网
     * @param context 上下文
     * @return true 如果连接到以太网，false 否则
     */
    @SuppressLint("WrongConstant")
    fun isEthernetAvailable(context: Context): Boolean {
        return try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = connectivityManager.activeNetwork
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
            } else {
                @Suppress("DEPRECATION")
                val networkInfo = connectivityManager.activeNetworkInfo
                networkInfo?.type == ConnectivityManager.TYPE_ETHERNET && networkInfo.isConnected
            }
        } catch (e: Exception) {
            DownloadLog.e("NetworkUtils", "检查以太网状态失败", e)
            false
        }
    }
    
    /**
     * 获取当前网络类型
     * @param context 上下文
     * @return 网络类型字符串
     */
    fun getNetworkType(context: Context): String {
        return when {
            isWifiAvailable(context) -> "WiFi"
            isCellularAvailable(context) -> "Cellular"
            isEthernetAvailable(context) -> "Ethernet"
            isNetworkAvailable(context) -> "Unknown"
            else -> "No Network"
        }
    }
    
    /**
     * 检查网络是否计费（移动网络）
     * @param context 上下文
     * @return true 如果是计费网络，false 否则
     */
    @SuppressLint("WrongConstant")
    fun isMeteredNetwork(context: Context): Boolean {
        return try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = connectivityManager.activeNetwork
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true
            } else {
                @Suppress("DEPRECATION")
                connectivityManager.isActiveNetworkMetered
            }
        } catch (e: Exception) {
            DownloadLog.e("NetworkUtils", "检查网络计费状态失败", e)
            true // 默认认为是计费网络，保守处理
        }
    }
}
