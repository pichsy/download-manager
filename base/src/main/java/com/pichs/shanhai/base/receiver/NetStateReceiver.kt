package com.pichs.shanhai.base.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.pichs.xbase.utils.NetWorkUtils
import com.pichs.xbase.utils.UiKit
import com.pichs.xbase.xlog.XLog

/**
 * 网络状态监听
 */
class NetStateReceiver(var onNetConnected: ((Boolean) -> Unit)? = null, var onNetDisConnected: (() -> Unit)? = null) : BroadcastReceiver() {

    private var callbackTimeStamp = 0L
    fun register(lifecycleOwner: LifecycleOwner) {
        try {
            val intentFilter = IntentFilter()
            intentFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION)
            ContextCompat.registerReceiver(UiKit.getApplication(), this, intentFilter, ContextCompat.RECEIVER_EXPORTED)
            lifecycleOwner.lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    try {
                        onNetConnected = null
                        onNetDisConnected = null
                        UiKit.getApplication().unregisterReceiver(this@NetStateReceiver)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            })
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        XLog.d("网络状态发生变化：isOnline: ${NetWorkUtils.isOnline()},action:${intent?.action}  Receiver: ${this}")
        XLog.d("网络状态发生变化：防重拦截 222222: ${NetWorkUtils.isOnline()},action:${intent?.action}  Receiver: ${this}")
        if (System.currentTimeMillis() - callbackTimeStamp < 500) {
            return
        }

        callbackTimeStamp = System.currentTimeMillis()

        // 如果相等的话就说明网络状态发生了变化
        if (NetWorkUtils.isOnline()) {
            onNetConnected?.invoke(true)
        } else {
            onNetDisConnected?.invoke()
        }
    }

}
