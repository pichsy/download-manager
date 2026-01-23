package com.pichs.download.demo

import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.View
import android.view.animation.Animation
import com.pichs.download.core.DownloadManager
import com.pichs.download.demo.databinding.DialogCellularConfirmBinding
import com.pichs.download.model.CellularThreshold
import com.pichs.xbase.clickhelper.fastClick
import com.pichs.xbase.stack.StackManager
import com.pichs.xbase.utils.UiKit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import razerdp.basepopup.BasePopupWindow
import razerdp.util.animation.AnimationHelper
import razerdp.util.animation.ScaleConfig

/**
 * 流量下载确认弹窗
 * 使用 BasePopupWindow 实现
 */
class CellularConfirmDialog(
    context: Context, private val totalSize: Long, private val taskCount: Int, private val mode: Int = MODE_CELLULAR
) : BasePopupWindow(context) {

    companion object {
        private const val TAG = "CellularConfirmDialog"

        /** 流量确认模式 */
        const val MODE_CELLULAR = 0

        /** 仅WiFi模式 */
        const val MODE_WIFI_ONLY = 1

        /** 无网络模式 */
        const val MODE_NO_NETWORK = 2

        /**
         * 显示确认弹窗（默认流量确认模式）
         */
        fun show(totalSize: Long, taskCount: Int) {
            return show(totalSize, taskCount, MODE_CELLULAR)
        }

        /**
         * 显示确认弹窗
         * @param mode 弹窗模式
         * @return CellularConfirmDialog? 如果没有顶部 Activity 或 Activity 无效则返回 null
         */
        fun show(totalSize: Long, taskCount: Int, mode: Int) {
            if (totalSize <= 0L || taskCount == 0) {
                Log.w(TAG, "show: totalSize==${totalSize}, taskCount==${taskCount}, cannot show dialog ")
                return
            }
            val topActivity = StackManager.get().getTopActivity()
            if (topActivity == null) {
                Log.w(TAG, "show: topActivity is null, cannot show dialog")
                return
            }
            if (topActivity.isFinishing || topActivity.isDestroyed) {
                Log.w(TAG, "show: topActivity is finishing or is destroyed, class=${topActivity.javaClass.simpleName}")
                return
            }
            try {
                topActivity.runOnUiThread {
                    Log.d(TAG, "show: showing dialog on ${topActivity.javaClass.simpleName}")
                    CellularConfirmDialog(topActivity, totalSize, taskCount, mode).apply {
                        showPopupWindow()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "show: failed to show dialog", e)
            }
        }
    }

    private lateinit var binding: DialogCellularConfirmBinding
    private val dialogScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        setContentView(R.layout.dialog_cellular_confirm)
    }

    override fun onViewCreated(contentView: View) {
        super.onViewCreated(contentView)
        binding = DialogCellularConfirmBinding.bind(contentView)

        // 弹窗配置
        setOutSideDismiss(true)
        isOutSideTouchable = true
        setBackPressEnable(true)

        // UI 初始化
        setupContent()
        setupClickListeners()
        loadThresholdText()
    }

    private fun setupContent() {
        val sizeText = FormatUtils.formatFileSize(totalSize)

        when (mode) {
            MODE_NO_NETWORK -> {
                binding.tvTitle.text = "网络连接提醒"
                binding.tvMessage.text = "该应用下载将消耗 $sizeText 流量，暂无网络连接，是否等待网络？"
                binding.btnUseCellular.text = "等待网络"
            }

            MODE_WIFI_ONLY -> {
                binding.tvTitle.text = "流量安装提醒"
                binding.tvMessage.text = "该应用下载将消耗 $sizeText 流量，已预约 WLAN 下自动安装，是否直接安装？"
                binding.btnUseCellular.text = "直接安装"
            }

            else -> {
                binding.tvTitle.text = "流量安装提醒"
                binding.tvMessage.text = "该应用下载将消耗 $sizeText 流量，已预约 WLAN 下自动安装，是否直接安装？"
                binding.btnUseCellular.text = "直接安装"
            }
        }
    }

    private fun setupClickListeners() {
        // 取消按钮
        binding.btnCancel.fastClick {
            handleDeny()
        }

        // 确认按钮
        binding.btnUseCellular.fastClick {
            handleConfirm()
        }

        // "去设置" 按钮
        binding.tvGoSettings.fastClick {
            runCatching {
                context.startActivity(Intent().apply {
                    setClassName(UiKit.getPackageName(), CellularSettingsActivity::class.java.name)
                })
            }
            dismiss()
        }
    }

    private fun handleConfirm() {
        dialogScope.launch {
            CellularConfirmViewModel.confirm()
        }
        dismiss()
    }

    private fun handleDeny() {
        dialogScope.launch {
            CellularConfirmViewModel.deny()
        }
        dismiss()
    }

    /**
     * 加载并显示当前阈值设置文本
     */
    private fun loadThresholdText() {
        val threshold = DownloadManager.getNetworkConfig().cellularThreshold
        binding.tvDoNotRemind.text = when (threshold) {
            CellularThreshold.ALWAYS_PROMPT -> "使用流量下载每次都提醒"
            CellularThreshold.NEVER_PROMPT -> "使用流量下载不提醒"
            else -> {
                val mb = (threshold / (1024 * 1024)).toInt()
                val displaySize = if (mb >= 1024) "${mb / 1024} GB" else "$mb MB"
                "下载流量超过${displaySize}时提醒"
            }
        }
    }

    override fun onCreateShowAnimation(): Animation {
        return AnimationHelper.asAnimation().withScale(ScaleConfig.CENTER).toShow()
    }

    override fun onCreateDismissAnimation(): Animation {
        return AnimationHelper.asAnimation().withScale(ScaleConfig.CENTER).toDismiss()
    }

    override fun onDismiss() {
        super.onDismiss()
        dialogScope.cancel()
    }
}
