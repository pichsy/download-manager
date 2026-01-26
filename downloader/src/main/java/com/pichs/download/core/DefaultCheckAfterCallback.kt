package com.pichs.download.core

import com.pichs.download.model.DownloadTask
import com.pichs.download.utils.DownloadLog

/**
 * 默认的决策回调实现
 * 策略：全部“通行”
 * - 遇到流量询问：自动同意（onUseCellular）
 * - 遇到提示：仅打印日志
 *
 * 目的：防止用户忘记设置回调导致任务静默暂停
 */
class DefaultCheckAfterCallback : CheckAfterCallback {

    companion object {
        private const val TAG = "DefaultCheckAfterCallback"
    }

    override fun requestConfirmation(
        scenario: NetworkScenario,
        pendingTasks: List<DownloadTask>,
        totalSize: Long,
        onConnectWifi: () -> Unit,
        onUseCellular: () -> Unit
    ) {
        DownloadLog.d(TAG, "默认回调触发: scenario=$scenario, 自动放行 (onUseCellular)，系统默认，放行。 请你设置自己的CheckAfterCallback，来控制拦截放行逻辑")
        // 默认策略：全部允许。即使用户未配置，也让下载继续，避免业务阻断。
        onUseCellular()
    }

    override fun showWifiOnlyHint(task: DownloadTask?) {
        DownloadLog.d(TAG, "默认回调触发: showWifiOnlyHint (仅日志提示，无UI)，系统默认，放行。请你设置自己的CheckAfterCallback，来控制拦截放行逻辑")
    }

    override fun showWifiDisconnectedHint(pausedCount: Int) {
        DownloadLog.d(
            TAG,
            "默认回调触发: showWifiDisconnectedHint (仅日志提示，无UI), 系统默认，放行。pausedCount=$pausedCount，请你设置自己的CheckAfterCallback，来控制拦截放行逻辑"
        )
    }
}
