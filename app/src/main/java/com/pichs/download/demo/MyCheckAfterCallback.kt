package com.pichs.download.demo

import android.content.Context
import android.widget.Toast
import com.pichs.download.core.CheckAfterCallback
import com.pichs.download.core.NetworkScenario
import com.pichs.download.model.DownloadTask
import com.pichs.shanhai.base.utils.LogUtils

/**
 * 下载决策回调实现
 * 使用端实现 UI 展示
 * 使用 StackManager 获取顶部 Activity
 *
 * 每次请求的回调都只保存在本次弹窗的闭包里，避免多次确认请求互相覆盖。
 */
class MyCheckAfterCallback(
    private val applicationContext: Context
) : CheckAfterCallback {

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
                LogUtils.d("MyCheckAfterCallback requestConfirmation: showing dialog CELLULAR_CONFIRMATION")
                val shown = CellularConfirmDialog.show(
                    totalSize,
                    pendingTasks.size,
                    CellularConfirmDialog.MODE_CELLULAR,
                    onConfirm = { onUseCellular() },
                    onCancel = { onConnectWifi() }
                )
                if (!shown) {
                    // 没有可用 Activity 时保持暂停，等下次网络事件/手动恢复再确认
                    onConnectWifi()
                }
            }

            NetworkScenario.WIFI_ONLY_MODE -> {
                LogUtils.d("MyCheckAfterCallback requestConfirmation: showing dialog WIFI_ONLY_MODE")
                val shown = CellularConfirmDialog.show(
                    totalSize = totalSize,
                    taskCount = pendingTasks.size,
                    mode = CellularConfirmDialog.MODE_WIFI_ONLY,
                    onConfirm = { onUseCellular() },
                    onCancel = { onConnectWifi() }
                )
                if (!shown) {
                    onConnectWifi()
                }
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
