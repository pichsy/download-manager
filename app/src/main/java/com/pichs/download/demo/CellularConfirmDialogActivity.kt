package com.pichs.download.demo

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.pichs.download.core.DownloadManager
import com.pichs.download.demo.databinding.ActivityCellularConfirmDialogBinding
import com.pichs.download.model.CellularPromptMode
import com.pichs.xbase.utils.UiKit
import kotlinx.coroutines.launch

/**
 * 流量下载确认弹窗 Activity
 * 使用透明主题，可以在任何界面弹起
 */
class CellularConfirmDialogActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_TOTAL_SIZE = "total_size"
        private const val EXTRA_TASK_COUNT = "task_count"
        private const val EXTRA_MODE = "mode"

        /** 流量确认模式 */
        const val MODE_CELLULAR = 0

        /** 仅WiFi模式 */
        const val MODE_WIFI_ONLY = 1

        /** 无网络模式 */
        const val MODE_NO_NETWORK = 2

        /**
         * 启动确认弹窗（默认流量确认模式）
         */
        fun start(context: Context, totalSize: Long, taskCount: Int) {
            start(context, totalSize, taskCount, MODE_CELLULAR)
        }

        /**
         * 启动确认弹窗
         * @param mode 弹窗模式：MODE_CELLULAR 或 MODE_WIFI_ONLY
         */
        fun start(context: Context, totalSize: Long, taskCount: Int, mode: Int) {
            val intent = Intent(context, CellularConfirmDialogActivity::class.java).apply {
                putExtra(EXTRA_TOTAL_SIZE, totalSize)
                putExtra(EXTRA_TASK_COUNT, taskCount)
                putExtra(EXTRA_MODE, mode)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            // 添加进入动画（放大出现）
            if (context is android.app.Activity) {
                context.overridePendingTransition(R.anim.dialog_scale_in, R.anim.no_anim)
            }
        }
    }

    private lateinit var binding: ActivityCellularConfirmDialogBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 设置无标题
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE)

        // 沉浸式状态栏和导航栏
        setupImmersiveMode()

        binding = ActivityCellularConfirmDialogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 获取参数
        val totalSize = intent.getLongExtra(EXTRA_TOTAL_SIZE, 0L)
        val taskCount = intent.getIntExtra(EXTRA_TASK_COUNT, 0)
        val mode = intent.getIntExtra(EXTRA_MODE, MODE_CELLULAR)

        // 点击外部区域关闭
        binding.root.setOnClickListener {
            handleDeny()
        }

        // 阻止内容区域的点击事件传递到根布局
        binding.cardContent.setOnClickListener { }

        // 流量提示
        val sizeText = formatFileSize(totalSize)
        val countText = "${taskCount}个应用"

        // 根据模式设置内容
        when (mode) {
            MODE_NO_NETWORK -> {
                // 无网络模式
                binding.tvTitle.text = "网络连接提醒"
                binding.tvMessage.text = "该应用下载将消耗 $sizeText 流量，暂无网络连接，是否等待网络？"
                binding.btnUseCellular.text = "等待网络"
            }

            MODE_WIFI_ONLY -> {
                // 仅WiFi模式
                binding.tvTitle.text = "流量安装提醒"
                binding.tvMessage.text = "该应用下载将消耗 $sizeText 流量，已预约 WLAN 下自动安装，是否直接安装？"
                binding.btnUseCellular.text = "直接安装"
            }

            else -> {
                // 流量确认模式
                binding.tvTitle.text = "流量安装提醒"
                binding.tvMessage.text = "该应用下载将消耗 $sizeText 流量，已预约 WLAN 下自动安装，是否直接安装？"
                binding.btnUseCellular.text = "直接安装"
            }
        }


        // 取消按钮
        binding.btnCancel.setOnClickListener {
            handleDeny()
        }

        // 确认按钮（直接安装/等待网络）
        binding.btnUseCellular.setOnClickListener {
            handleConfirm()
        }

        // "去设置" 按钮点击
        binding.tvGoSettings.setOnClickListener {
            runCatching {
                startActivity(Intent().apply {
                    setClassName(UiKit.getPackageName(), AppUseDataSettingsActivity::class.java.name)
                })
            }
            finishWithAnimation()
        }
    }

    private fun setupImmersiveMode() {
        // 设置沉浸式状态栏和导航栏
        WindowCompat.setDecorFitsSystemWindows(window, false)

        window.apply {
            statusBarColor = Color.TRANSPARENT
            navigationBarColor = Color.TRANSPARENT

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                attributes.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        // 设置状态栏和导航栏图标颜色为深色（浅色背景）
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
    }

    private fun handleConfirm() {
        // 检查是否勾选了"不再提醒"
//        if (binding.civDoNotRemind.isChecked) {
//            // 更新网络配置为不再提醒
//            val currentConfig = DownloadManager.getNetworkConfig()
//            DownloadManager.setNetworkConfig(
//                currentConfig.copy(cellularPromptMode = CellularPromptMode.NEVER)
//            )
//        }

        // pendingAction 已在设置时包含 cellularConfirmed=true
        lifecycleScope.launch {
            CellularConfirmViewModel.confirm()
        }
        finishNoAnimation()
    }

    private fun handleDeny() {
        lifecycleScope.launch {
            CellularConfirmViewModel.deny()
        }
        finishNoAnimation()
    }

    @SuppressLint("MissingSuperCall")
    override fun onBackPressed() {
        handleDeny()
    }

    private fun finishWithAnimation() {
        finish()
        overridePendingTransition(R.anim.no_anim, R.anim.dialog_scale_out)
    }

    private fun finishNoAnimation() {
        finish()
        overridePendingTransition(R.anim.no_anim, R.anim.no_anim)
    }

    private fun formatFileSize(bytes: Long): String = FormatUtils.formatFileSize(bytes)
}
