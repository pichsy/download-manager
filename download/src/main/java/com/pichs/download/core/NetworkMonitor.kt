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
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)
        
        updateNetworkType()
        updateBatteryStatus()
    }
    
    fun stopMonitoring() {
        connectivityManager.unregisterNetworkCallback(networkCallback)
    }
    
    private fun updateNetworkType() {
        val activeNetwork = connectivityManager.activeNetwork ?: return
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return
        
        val networkType = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                // 根据带宽判断网络类型
                val bandwidth = capabilities.getLinkDownstreamBandwidthKbps()
                when {
                    bandwidth > 10000 -> NetworkType.CELLULAR_5G
                    bandwidth > 1000 -> NetworkType.CELLULAR_4G
                    bandwidth > 100 -> NetworkType.CELLULAR_3G
                    else -> NetworkType.CELLULAR_2G
                }
            }
            else -> NetworkType.UNKNOWN
        }
        
        _networkType.value = networkType
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
