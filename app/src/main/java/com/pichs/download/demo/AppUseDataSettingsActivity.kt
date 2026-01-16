package com.pichs.download.demo

import com.pichs.download.core.DownloadManager
import com.pichs.download.demo.databinding.ActivityAppUseDataSettingsBinding
import com.pichs.download.model.CellularThreshold
import com.pichs.shanhai.base.base.BaseActivity
import com.pichs.shanhai.base.utils.toast.ToastUtils
import com.pichs.xbase.clickhelper.fastClick

class AppUseDataSettingsActivity : BaseActivity<ActivityAppUseDataSettingsBinding>() {

    companion object {
        private const val PREFS_NAME = "app_settings_cache"
    }

    // 阈值选项（MB）：与参考代码保持一致
    private val DOWNLOAD_THRESHOLD_OPTIONS = listOf(1024, 500, 200, 100, 50, 20)

    override fun afterOnCreate() {
        binding.ivBack.fastClick {
            finish()
        }

        // 加载当前配置
        loadConfig()

        // 前置检查开关
        binding.switchPreCheck.setOnCheckedChangeListener { v, isChecked ->
            ToastUtils.show("开关：newChecked=${isChecked}")
            val config = DownloadManager.getNetworkConfig()
            com.pichs.download.utils.DownloadLog.d("Settings", "切换前置检查: $isChecked, 当前config: $config")
            DownloadManager.setNetworkConfig(config.copy(checkBeforeCreate = isChecked))
            com.pichs.download.utils.DownloadLog.d("Settings", "保存后config: ${DownloadManager.getNetworkConfig()}")
        }

        // 网络模式选择 - 点击弹出选择器
        binding.clNetworkModeChoose.fastClick {
            showNetworkModePicker()
        }

        // 流量下载提醒 - 点击弹出选择器
        binding.clDownloadThresholdChoose.fastClick {
            showDownloadThresholdPicker()
        }
    }

    private fun loadConfig() {
        val config = DownloadManager.getNetworkConfig()
        com.pichs.download.utils.DownloadLog.d("Settings", "加载配置: $config")

        // 设置前置检查开关
        binding.switchPreCheck.setChecked(config.checkBeforeCreate)

        // 更新网络模式显示文本
        binding.tvNetworkModeValue.text = if (config.wifiOnly) {
            "仅wifi"
        } else {
            "wifi和流量"
        }

        // 更新阈值显示文本
        binding.tvDownloadThresholdValue.text = thresholdToDisplayString(config.cellularThreshold)
    }

    /**
     * 显示网络模式选择弹窗
     */
    private fun showNetworkModePicker() {
        val options = listOf("仅wifi", "wifi和流量")
        
        // 当前选中项
        val config = DownloadManager.getNetworkConfig()
        val selectedIndex = if (config.wifiOnly) 0 else 1

        val popup = WheelPickerPopup(
            context = this,
            title = "自动更新应用",
            items = options,
            selectedIndex = selectedIndex,
            onConfirm = { index, _ ->
                val wifiOnly = index == 0
                // 保存到配置
                val newConfig = DownloadManager.getNetworkConfig()
                DownloadManager.setNetworkConfig(newConfig.copy(wifiOnly = wifiOnly))

                // 更新 UI 显示
                binding.tvNetworkModeValue.text = options[index]
            }
        )
        popup.showPopupWindow()
    }

    /**
     * 显示下载阈值选择弹窗
     */
    private fun showDownloadThresholdPicker() {
        // 构建选项列表: 不提醒 + 阈值 + 每次都提醒
        val options = mutableListOf<Pair<String, Long>>()

        // 添加特殊选项：不提醒
        options.add("不提醒" to CellularThreshold.NEVER_PROMPT)

        // 添加阈值选项
        DOWNLOAD_THRESHOLD_OPTIONS.forEach { mb ->
            val label = if (mb >= 1024) "${mb / 1024} GB" else "$mb MB"
            options.add(label to (mb * 1024L * 1024L))
        }

        // 添加特殊选项：每次都提醒
        options.add("每次都提醒" to CellularThreshold.ALWAYS_PROMPT)

        // 找到当前选中项
        val currentThreshold = DownloadManager.getNetworkConfig().cellularThreshold
        val selectedIndex = options.indexOfFirst { it.second == currentThreshold }
            .takeIf { it >= 0 } ?: (options.size - 1)

        // 使用 WheelPickerPopup 显示选择器
        val popup = WheelPickerPopup(
            context = this,
            title = "流量下载提醒",
            items = options.map { it.first },
            selectedIndex = selectedIndex,
            onConfirm = { index, _ ->
                val threshold = options[index].second
                // 保存到配置
                val config = DownloadManager.getNetworkConfig()
                DownloadManager.setNetworkConfig(config.copy(cellularThreshold = threshold))

                // 更新 UI 显示
                binding.tvDownloadThresholdValue.text = options[index].first
            }
        )
        popup.showPopupWindow()
    }

    /**
     * 将字节阈值转换为显示文本
     */
    private fun thresholdToDisplayString(threshold: Long): String {
        return when (threshold) {
            CellularThreshold.ALWAYS_PROMPT -> "每次都提醒"
            CellularThreshold.NEVER_PROMPT -> "不提醒"
            else -> {
                val mb = (threshold / (1024 * 1024)).toInt()
                if (mb >= 1024) "${mb / 1024} GB" else "$mb MB"
            }
        }
    }
}