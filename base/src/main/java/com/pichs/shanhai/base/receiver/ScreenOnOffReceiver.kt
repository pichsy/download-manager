package com.pichs.shanhai.base.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.pichs.xbase.utils.UiKit
import com.pichs.xbase.xlog.XLog

/**
 * 应用 解锁屏监听
 */
class ScreenOnOffReceiver constructor(private var onScreenChanged: OnScreenStateChangeListener? = null) : BroadcastReceiver() {

    fun register(lifecycleOwner: LifecycleOwner) {
        try {
            val filter = IntentFilter(Intent.ACTION_SCREEN_ON)
            filter.addAction(Intent.ACTION_SCREEN_OFF)
            ContextCompat.registerReceiver(UiKit.getApplication(), this, filter, ContextCompat.RECEIVER_EXPORTED)
            lifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    try {
                        onScreenChanged = null
                        UiKit.getApplication().unregisterReceiver(this@ScreenOnOffReceiver)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            })
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }


    override fun onReceive(context: Context, intent: Intent) {
        XLog.d("DeviceReceiver, onReceive: ${intent.action},${intent.dataString}")

        when (intent.action) {
            Intent.ACTION_SCREEN_OFF -> {
                onScreenChanged?.onScreenStateChanged(false)
            }

            Intent.ACTION_SCREEN_ON -> {
                onScreenChanged?.onScreenStateChanged(true)
            }
        }
    }
}

fun interface OnScreenStateChangeListener {
    fun onScreenStateChanged(isOn: Boolean)
}