package com.pichs.download.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class NetworkMonitor(private val context: Context) {
    
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    
    private val _networkType = MutableStateFlow(NetworkType.UNKNOWN)
    val networkType: StateFlow<NetworkType> = _networkType.asStateFlow()
    
    private val _isCharging = MutableStateFlow(false)
    val isCharging: StateFlow<Boolean> = _isCharging.asStateFlow()
    
    private val _batteryLevel = MutableStateFlow(100)
    val batteryLevel: StateFlow<Int> = _batteryLevel.asStateFlow()
    
    private val _isLowBattery = MutableStateFlow(false)
    val isLowBattery: StateFlow<Boolean> = _isLowBattery.asStateFlow()
    
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            updateNetworkType()
        }
        
        override fun onLost(network: Network) {
            updateNetworkType()
        }
        
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            updateNetworkType()
        }
    }
    
    fun startMonitoring() {
        try {
            // 使用 registerDefaultNetworkCallback 监听默认网络（实际用于下载的网络）
            // 当从 WiFi 切换到蜂窝网络时，会自动触发回调
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
        } catch (e: SecurityException) {
            // 权限缺失，无法监听网络状态
            // 可以记录日志或降级处理
            e.printStackTrace()
        }
        
        updateNetworkType()
        updateBatteryStatus()
    }
    
    fun stopMonitoring() {
        connectivityManager.unregisterNetworkCallback(networkCallback)
    }
    
    private fun updateNetworkType() {
        // 电池状态没有回调，顺带在每次网络变化时刷新一次
        updateBatteryStatus()

        val activeNetwork = connectivityManager.activeNetwork
        if (activeNetwork == null) {
            _networkType.value = NetworkType.UNKNOWN
            return
        }
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        if (capabilities == null ||
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        ) {
            _networkType.value = NetworkType.UNKNOWN
            return
        }

        val type = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                val bandwidth = capabilities.getLinkDownstreamBandwidthKbps()
                when {
                    bandwidth > 10000 -> NetworkType.CELLULAR_5G
                    bandwidth > 1000 -> NetworkType.CELLULAR_4G
                    bandwidth > 100 -> NetworkType.CELLULAR_3G
                    else -> NetworkType.CELLULAR_2G
                }
            }
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ->
                resolveUnderlyingType() ?: NetworkType.ETHERNET
            else -> NetworkType.UNKNOWN
        }

        _networkType.value = type
    }

    /**
     * VPN 的 NetworkCapabilities 只暴露 VPN transport，尝试从其他可用网络推断底层类型。
     * 推断不出来时按非计费网络处理，避免 VPN 下被误判为断网。
     */
    @Suppress("DEPRECATION")
    private fun resolveUnderlyingType(): NetworkType? {
        return try {
            connectivityManager.allNetworks.firstNotNullOfOrNull { network ->
                val caps = connectivityManager.getNetworkCapabilities(network) ?: return@firstNotNullOfOrNull null
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@firstNotNullOfOrNull null
                when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR_4G
                    else -> null
                }
            }
        } catch (_: Exception) {
            null
        }
    }
    
    private fun updateBatteryStatus() {
        val batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val isCharging = batteryManager.isCharging
        val isLowBattery = batteryLevel < 20
        
        _batteryLevel.value = batteryLevel
        _isCharging.value = isCharging
        _isLowBattery.value = isLowBattery
    }
    
    fun getCurrentNetworkType(): NetworkType = _networkType.value
    fun isCurrentlyCharging(): Boolean = _isCharging.value
    fun getCurrentBatteryLevel(): Int = _batteryLevel.value
    fun isCurrentlyLowBattery(): Boolean = _isLowBattery.value
}
