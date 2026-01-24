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
    context: Context,
    private val totalSize: Long,
    private val taskCount: Int,
    private val mode: Int = MODE_CELLULAR,
    private val onConfirm: ((Boolean) -> Unit)? = null,
    private val onCancel: (() -> Unit)? = null
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
         * 显示确认弹窗
         * @param mode 弹窗模式
         * @return CellularConfirmDialog? 如果没有顶部 Activity 或 Activity 无效则返回 null
         */
        fun show(totalSize: Long, taskCount: Int, mode: Int, onConfirm: ((Boolean) -> Unit)? = null, onCancel: (() -> Unit)? = null) {
            if (totalSize <= 0L && taskCount == 0) {
                Log.w(TAG, "CellularConfirmDialog show: totalSize==${totalSize}, taskCount==${taskCount}, cannot show dialog ")
                return
            }
            val topActivity = StackManager.get().getTopActivity()
            if (topActivity == null) {
                Log.w(TAG, "CellularConfirmDialog show: topActivity is null, cannot show dialog")
                return
            }
            if (topActivity.isFinishing || topActivity.isDestroyed) {
                Log.w(TAG, "CellularConfirmDialog show: topActivity is finishing or is destroyed, class=${topActivity.javaClass.simpleName}")
                return
            }
            // 传递 null，触发 ViewModel fallback
            show(topActivity, totalSize, taskCount, mode, onConfirm, onCancel)
        }

        /**
         * 显示确认弹窗（带回调，支持直接传入 Context）
         * @param context 上下文（通常是 Activity）
         * @param onConfirm 回调 (true=确认/使用流量, false=取消/等待WiFi)。如果是 null，则使用 CellularConfirmViewModel。
         */
        @Deprecated("别再调用这个方法了，废弃了，也是私有方法，再调用腿给你打断")
        private fun show(
            context: Context,
            totalSize: Long,
            taskCount: Int,
            mode: Int,
            onConfirm: ((Boolean) -> Unit)? = null,
            onCancel: (() -> Unit)? = null
        ) {
            if (totalSize <= 0L && taskCount == 0 || context !is android.app.Activity || context.isFinishing || context.isDestroyed) {
                return
            }
            try {
                context.runOnUiThread {
                    CellularConfirmDialog(context, totalSize, taskCount, mode, onConfirm, onCancel).showPopupWindow()
                }
            } catch (e: Exception) {
                Log.e(TAG, "CellularConfirmDialog show: failed to show dialog", e)
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
        val countText = if (taskCount > 1) {
            "下载${taskCount}个应用，预计消耗${sizeText}流量，已预约WLAN下自动安装，是否直接安装?"
        } else {
            "该应用下载将消耗${sizeText}流量，已预约WLAN下自动安装，是否直接安装?"
        }

        when (mode) {
            MODE_NO_NETWORK -> {
                binding.tvTitle.text = "网络连接提醒"
                binding.tvMessage.text = "暂无网络连接，将下载${taskCount}个应用，共$sizeText"
                binding.btnUseCellular.text = "等待网络"
                binding.btnCancel.text = "连接网络"
            }

            MODE_WIFI_ONLY -> {
                binding.tvTitle.text = "流量安装提醒"
                binding.tvMessage.text = countText
                binding.btnUseCellular.text = "直接安装"
                binding.btnCancel.text = "等待WLAN"
            }

            else -> {
                binding.tvTitle.text = "流量安装提醒"
                binding.tvMessage.text = countText
                binding.btnUseCellular.text = "直接安装"
                binding.btnCancel.text = "等待WLAN"
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
        if (onConfirm != null) {
            onConfirm.invoke(true)
        } else {
            dialogScope.launch {
                CellularConfirmViewModel.confirm()
            }
        }
        dismiss()
    }

    private fun handleDeny() {
        if (onCancel != null) {
            onCancel.invoke()
        } else {
            dialogScope.launch {
                CellularConfirmViewModel.deny()
            }
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
