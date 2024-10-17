package com.pichs.shanhai.base.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pichs.xbase.xlog.XLog

/**
 * 开机广播
 */
class BootCompleteReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            XLog.d("开机广播,${intent?.action}")
        } else if (intent?.action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            XLog.d("锁屏开机广播,${intent?.action}")
        }
    }

}