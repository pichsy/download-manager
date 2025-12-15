package com.pichs.download.demo

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import com.pichs.download.core.DownloadDecisionCallback
import com.pichs.download.model.DownloadTask

/**
 * 下载决策回调实现
 * 使用端实现 UI 展示
 */
class MyDownloadDecisionCallback(
    private val activity: Activity
) : DownloadDecisionCallback {

    override fun requestCellularConfirmation(
        pendingTasks: List<DownloadTask>,
        totalSize: Long,
        onConnectWifi: () -> Unit,
        onUseCellular: () -> Unit
    ) {
        val sizeText = formatFileSize(totalSize)
        val taskCount = pendingTasks.size
        
        AlertDialog.Builder(activity)
            .setTitle("流量下载提醒")
            .setMessage("当前使用移动网络，${taskCount}个任务共 $sizeText\n确定使用流量下载？")
            .setNeutralButton("取消") { dialog, _ ->
                dialog.dismiss()
            }
            .setNegativeButton("连接 WiFi") { _, _ -> 
                onConnectWifi()
                // 可选：打开 WiFi 设置
                runCatching {
                    activity.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                }
            }
            .setPositiveButton("使用流量下载") { _, _ -> 
                onUseCellular() 
            }
            .setCancelable(false)
            .show()
    }

    override fun showWifiOnlyHint(task: DownloadTask?) {
        Toast.makeText(
            activity, 
            "当前设置为仅 WiFi 下载，请连接 WiFi 后重试", 
            Toast.LENGTH_LONG
        ).show()
    }
    
    override fun showWifiDisconnectedHint(pausedCount: Int) {
        if (pausedCount > 0) {
            Toast.makeText(
                activity, 
                "WiFi 已断开，$pausedCount 个下载任务已暂停", 
                Toast.LENGTH_SHORT
            ).show()
        }
    }
    
    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
            bytes >= 1024 * 1024 -> "%.2f MB".format(bytes / 1024.0 / 1024.0)
            bytes >= 1024 -> "%.2f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
