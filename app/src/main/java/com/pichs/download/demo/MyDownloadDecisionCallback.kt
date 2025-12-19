package com.pichs.download.demo

import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.pichs.download.core.DownloadDecisionCallback
import com.pichs.download.core.NetworkScenario
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

    /**
     * 根据场景决定如何展示 UI
     */
    override fun requestConfirmation(
        scenario: NetworkScenario,
        pendingTasks: List<DownloadTask>,
        totalSize: Long,
        onConnectWifi: () -> Unit,
        onUseCellular: () -> Unit
    ) {
        when (scenario) {
            NetworkScenario.CELLULAR_CONFIRMATION -> {
                // 流量确认：弹窗
                pendingOnUseCellular = onUseCellular
                pendingOnConnectWifi = onConnectWifi
                CellularConfirmDialogActivity.start(activity, totalSize, pendingTasks.size)
            }
            NetworkScenario.WIFI_ONLY_MODE -> {
                // 仅WiFi模式：使用端可以选择弹窗或Toast
                pendingOnUseCellular = onUseCellular
                pendingOnConnectWifi = onConnectWifi
                CellularConfirmDialogActivity.start(
                    activity, totalSize, pendingTasks.size, 
                    CellularConfirmDialogActivity.MODE_WIFI_ONLY
                )
            }
            NetworkScenario.NO_NETWORK -> {
                // 无网络：Toast 提示
                if (pendingTasks.isNotEmpty()) {
                    Toast.makeText(
                        activity,
                        "等待网络下载",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
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

