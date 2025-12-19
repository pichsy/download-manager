package com.pichs.download.demo

import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.pichs.download.core.DownloadDecisionCallback
import com.pichs.download.model.DownloadTask
import kotlinx.coroutines.launch

/**
 * 下载决策回调实现
 * 使用端实现 UI 展示
 */
class MyDownloadDecisionCallback(
    private val activity: FragmentActivity
) : DownloadDecisionCallback {

    // 待执行的回调
    private var pendingOnUseCellular: (() -> Unit)? = null
    private var pendingOnConnectWifi: (() -> Unit)? = null
    
    init {
        // 使用 Activity 的 lifecycleScope，自动随生命周期取消
        activity.lifecycleScope.launch {
            CellularConfirmViewModel.confirmEvent.collect { event ->
                when (event) {
                    is CellularConfirmEvent.Confirmed -> {
                        pendingOnUseCellular?.invoke()
                        pendingOnUseCellular = null
                        pendingOnConnectWifi = null
                    }
                    is CellularConfirmEvent.Denied -> {
                        pendingOnConnectWifi?.invoke()
                        pendingOnUseCellular = null
                        pendingOnConnectWifi = null
                    }
                }
            }
        }
    }

    override fun requestCellularConfirmation(
        pendingTasks: List<DownloadTask>,
        totalSize: Long,
        onConnectWifi: () -> Unit,
        onUseCellular: () -> Unit
    ) {
        // 保存回调
        pendingOnUseCellular = onUseCellular
        pendingOnConnectWifi = onConnectWifi
        // 启动弹窗
        CellularConfirmDialogActivity.start(activity, totalSize, pendingTasks.size)
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
}
