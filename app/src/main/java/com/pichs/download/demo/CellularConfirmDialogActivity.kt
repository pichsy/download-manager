package com.pichs.download.demo

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Window
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.pichs.download.core.DownloadManager
import com.pichs.download.demo.databinding.ActivityCellularConfirmDialogBinding
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
        }
    }
    
    private lateinit var binding: ActivityCellularConfirmDialogBinding
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 设置无标题
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        
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
        binding.llContent.setOnClickListener { }
        
        // 根据模式设置内容
        val sizeText = formatFileSize(totalSize)
        val countText = if (taskCount == 1) "1 个应用" else "${taskCount} 个应用"
        
        if (mode == MODE_WIFI_ONLY) {
            // 仅WiFi模式
            binding.tvMessage.text = "当前未连接WiFi，将下载 $countText 共 $sizeText"
            binding.btnUseCellular.text = "等待WiFi下载"
        } else {
            // 流量确认模式
            binding.tvMessage.text = "当前使用移动网络，将下载 $countText 共 $sizeText\n确定使用流量下载？"
            binding.btnUseCellular.text = "使用流量下载"
        }
        
        // 取消按钮
        binding.btnCancel.setOnClickListener {
            handleDeny()
        }
        
        // 连接 WiFi 按钮
        binding.btnConnectWifi.setOnClickListener {
            runCatching {
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            }
        }
        
        // 确认按钮（使用流量/等待WiFi）
        binding.btnUseCellular.setOnClickListener {
            handleConfirm()
        }
    }
    
    private fun handleConfirm() {
        DownloadManager.markCellularDownloadAllowed()
        lifecycleScope.launch {
            CellularConfirmViewModel.confirm()
        }
        finish()
    }
    
    private fun handleDeny() {
        lifecycleScope.launch {
            CellularConfirmViewModel.deny()
        }
        finish()
    }
    
    override fun onBackPressed() {
        handleDeny()
    }
    
    private fun formatFileSize(bytes: Long): String = FormatUtils.formatFileSize(bytes)
}
