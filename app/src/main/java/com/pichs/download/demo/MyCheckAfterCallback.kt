package com.pichs.download.demo

import android.content.Context
import android.widget.Toast
import com.pichs.download.core.CheckAfterCallback
import com.pichs.download.core.NetworkScenario
import com.pichs.download.model.DownloadTask
import com.pichs.shanhai.base.utils.LogUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 下载决策回调实现
 * 使用端实现 UI 展示
 * 使用 StackManager 获取顶部 Activity
 */
class MyCheckAfterCallback(
    private val applicationContext: Context
) : CheckAfterCallback {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // 待执行的回调
    private var pendingOnUseCellular: (() -> Unit)? = null
    private var pendingOnConnectWifi: (() -> Unit)? = null

    init {
        // 监听确认事件
        scope.launch {
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
        LogUtils.d("requestConfirmation called: " + "scenario=$scenario, taskCount=${pendingTasks.size}, " + "tasks=${pendingTasks.map { it.fileName }}")

        when (scenario) {
            NetworkScenario.CELLULAR_CONFIRMATION -> {
                // 流量确认：弹窗
                pendingOnUseCellular = onUseCellular
                pendingOnConnectWifi = onConnectWifi
                LogUtils.d("MyCheckAfterCallback requestConfirmation: showing dialog CELLULAR_CONFIRMATION")
                CellularConfirmDialog.show(
                    totalSize,
                    pendingTasks.size,
                    CellularConfirmDialog.MODE_CELLULAR,
                    onConfirm = {
                        pendingOnUseCellular?.invoke()
                        pendingOnUseCellular = null
                        pendingOnConnectWifi = null
                    },
                    onCancel = {
                        pendingOnConnectWifi?.invoke()
                        pendingOnUseCellular = null
                        pendingOnConnectWifi = null
                    }
                )
            }

            NetworkScenario.WIFI_ONLY_MODE -> {
                // 仅WiFi模式：弹窗
                pendingOnUseCellular = onUseCellular
                pendingOnConnectWifi = onConnectWifi
                LogUtils.d("MyCheckAfterCallback requestConfirmation: showing dialog WIFI_ONLY_MODE")
                CellularConfirmDialog.show(
                    totalSize = totalSize,
                    taskCount = pendingTasks.size,
                    mode = CellularConfirmDialog.MODE_WIFI_ONLY,
                    onConfirm = {
                        pendingOnUseCellular?.invoke()
                        pendingOnUseCellular = null
                        pendingOnConnectWifi = null
                    },
                    onCancel = {
                        pendingOnConnectWifi?.invoke()
                        pendingOnUseCellular = null
                        pendingOnConnectWifi = null
                    }
                )
            }

            NetworkScenario.NO_NETWORK -> {
                // 无网络：Toast 提示
                if (pendingTasks.isNotEmpty()) {
                    Toast.makeText(
                        applicationContext, "等待网络下载", Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    override fun showWifiOnlyHint(task: DownloadTask?) {
        Toast.makeText(
            applicationContext, "当前设置为仅 WiFi 下载，请连接 WiFi 后重试", Toast.LENGTH_LONG
        ).show()
    }

    override fun showWifiDisconnectedHint(pausedCount: Int) {
        if (pausedCount > 0) {
            Toast.makeText(
                applicationContext, "WiFi 已断开，$pausedCount 个下载任务已暂停", Toast.LENGTH_SHORT
            ).show()
        }
    }
}
