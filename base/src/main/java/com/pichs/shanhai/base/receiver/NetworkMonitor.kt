package com.pichs.shanhai.base.receiver

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.pichs.xbase.utils.UiKit
import com.pichs.xbase.xlog.XLog

/**
 * 网络监听器 - 使用 NetworkCallback 实现
 * 可以准确监听网络类型切换（WiFi ↔ 流量）
 */
class NetworkMonitor(
    private val onNetworkChanged: (isWifi: Boolean) -> Unit,
    private val onNetworkLost: () -> Unit
) {

    private val context: Context = UiKit.getApplication()
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var currentIsWifi: Boolean? = null
    private var isRegistered = false

    init {
        XLog.d("NetworkMonitor", "======> NetworkMonitor 实例被创建")
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            XLog.d("NetworkMonitor", "======> onAvailable: $network")
            checkNetworkType()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            XLog.d("NetworkMonitor", "======> onCapabilitiesChanged: $network, capabilities=$networkCapabilities")
            checkNetworkType()
        }

        override fun onLost(network: Network) {
            XLog.d("NetworkMonitor", "======> onLost: $network")
            checkNetworkType()
        }
    }

    private fun checkNetworkType() {
        val activeNetwork = connectivityManager.activeNetwork
        val capabilities = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }

        val isWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val isCellular = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val hasNetwork = isWifi || isCellular

        XLog.d("NetworkMonitor", "======> checkNetworkType: isWifi=$isWifi, isCellular=$isCellular, lastIsWifi=$currentIsWifi, hasNetwork=$hasNetwork")

        if (hasNetwork) {
            // 有网络连接，检查类型是否变化
            if (currentIsWifi != isWifi) {
                XLog.d("NetworkMonitor", "======> 网络类型变化: $currentIsWifi -> $isWifi")
                currentIsWifi = isWifi
                XLog.d("NetworkMonitor", "======> 触发 onNetworkChanged 回调: isWifi=$isWifi")
                onNetworkChanged(isWifi)
            } else {
                XLog.d("NetworkMonitor", "======> 网络类型未变化，不触发回调")
            }
        } else {
            // 没有可用网络
            if (currentIsWifi != null) {
                XLog.d("NetworkMonitor", "======> 所有网络都断开了")
                currentIsWifi = null
                XLog.d("NetworkMonitor", "======> 触发 onNetworkLost 回调")
                onNetworkLost()
            } else {
                XLog.d("NetworkMonitor", "======> 网络已经是断开状态，不触发回调")
            }
        }
    }

    /**
     * 注册网络监听，绑定到 LifecycleOwner
     */
    fun register(lifecycleOwner: LifecycleOwner) {
        XLog.d("NetworkMonitor", "======> register() 被调用")

        if (isRegistered) {
            XLog.w("NetworkMonitor", "======> 已经注册过，跳过")
            return
        }

        try {
            XLog.d("NetworkMonitor", "======> 开始注册 NetworkCallback")

            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .build()

            XLog.d("NetworkMonitor", "======> NetworkRequest 创建完成: $request")

            connectivityManager.registerNetworkCallback(request, networkCallback)
            isRegistered = true

            XLog.d("NetworkMonitor", "======> registerNetworkCallback 调用成功")

            // 立即检查当前网络状态
            XLog.d("NetworkMonitor", "======> 立即检查当前网络状态")
            checkNetworkType()

            XLog.d("NetworkMonitor", "======> 网络监听已注册，绑定生命周期")

            // 绑定生命周期，自动注销
            lifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    XLog.d("NetworkMonitor", "======> Lifecycle onDestroy, 准备注销")
                    unregister()
                }
            })

            XLog.d("NetworkMonitor", "======> register() 完成")
        } catch (e: Exception) {
            XLog.e("NetworkMonitor", "======> 注册网络监听失败: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * 注销网络监听
     */
    fun unregister() {
        if (!isRegistered) {
            return
        }

        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            isRegistered = false
            currentIsWifi = null
            XLog.d("NetworkMonitor", "网络监听已注销")
        } catch (e: Exception) {
            XLog.e("NetworkMonitor", "注销网络监听失败")
        }
    }
}
