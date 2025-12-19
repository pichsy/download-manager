package com.pichs.download.demo

import androidx.core.view.isVisible
import com.pichs.download.core.DownloadManager
import com.pichs.download.demo.databinding.ActivityAppUseDataSettingsBinding
import com.pichs.download.model.CellularPromptMode
import com.pichs.shanhai.base.base.BaseActivity
import com.pichs.xbase.clickhelper.fastClick

class AppUseDataSettingsActivity : BaseActivity<ActivityAppUseDataSettingsBinding>() {

    override fun afterOnCreate() {
        // 初始化阈值管理器
        CellularThresholdManager.init(this)
        
        binding.ivBack.fastClick {
            finish()
        }

        // 加载当前配置
        loadConfig()
        
        // 监听前置检查开关（整行点击切换）
        binding.llPreCheckSwitch.fastClick {
            val newChecked = !binding.switchPreCheck.isChecked
            binding.switchPreCheck.setChecked(newChecked)
            val config = DownloadManager.getNetworkConfig()
            com.pichs.download.utils.DownloadLog.d("Settings", "切换前置检查: $newChecked, 当前config: $config")
            DownloadManager.setNetworkConfig(config.copy(checkBeforeCreate = newChecked))
            com.pichs.download.utils.DownloadLog.d("Settings", "保存后config: ${DownloadManager.getNetworkConfig()}")
        }

        // 监听网络模式切换（仅 WiFi / 允许流量）
        binding.llDownloadGroup.setOnRadioCheckedListener { _, _, isChecked, position ->
            if (isChecked) {
                val config = DownloadManager.getNetworkConfig()
                val wifiOnly = position == 0
                DownloadManager.setNetworkConfig(config.copy(wifiOnly = wifiOnly))
                updateUIVisibility(wifiOnly, config.cellularPromptMode)
            }
        }

        // 监听流量提醒模式切换
        // 0: 每次提醒, 1: 不再提醒, 2: 智能提醒（交给用户）
        binding.llDownloadDataUseGroup.setOnRadioCheckedListener { _, _, isChecked, position ->
            if (isChecked) {
                val config = DownloadManager.getNetworkConfig()
                val newMode = when (position) {
                    0 -> CellularPromptMode.ALWAYS     // 每次提醒
                    1 -> CellularPromptMode.NEVER      // 不再提醒
                    else -> CellularPromptMode.USER_CONTROLLED // 智能提醒（交给用户）
                }
                DownloadManager.setNetworkConfig(config.copy(cellularPromptMode = newMode))
                CellularThresholdManager.smartModeEnabled = (newMode == CellularPromptMode.USER_CONTROLLED)
                // 更新阈值选择器可见性
                binding.llDownloadDataLimitGroup.isVisible = (newMode == CellularPromptMode.USER_CONTROLLED)
            }
        }
        
        // 监听阈值选择
        binding.llDownloadDataLimitGroup.setOnRadioCheckedListener { _, _, isChecked, position ->
            if (isChecked && position in CellularThresholdManager.thresholds.indices) {
                CellularThresholdManager.thresholdMB = CellularThresholdManager.thresholds[position]
            }
        }
    }

    private fun loadConfig() {
        val config = DownloadManager.getNetworkConfig()
        com.pichs.download.utils.DownloadLog.d("Settings", "加载配置: $config")
        
        // 设置前置检查开关
        binding.switchPreCheck.setChecked(config.checkBeforeCreate)

        // 设置网络模式选择
        if (config.wifiOnly) {
            binding.llDownloadGroup.select(0)
        } else {
            binding.llDownloadGroup.select(1)
        }

        // 设置流量提醒模式选择
        when (config.cellularPromptMode) {
            CellularPromptMode.ALWAYS -> binding.llDownloadDataUseGroup.select(0)
            CellularPromptMode.NEVER -> binding.llDownloadDataUseGroup.select(1)
            CellularPromptMode.USER_CONTROLLED -> binding.llDownloadDataUseGroup.select(2)
        }
        
        // 设置阈值选择
        val thresholdIndex = CellularThresholdManager.thresholds.indexOf(CellularThresholdManager.thresholdMB)
        if (thresholdIndex >= 0) {
            binding.llDownloadDataLimitGroup.select(thresholdIndex)
        }

        // 更新 UI 可见性
        updateUIVisibility(config.wifiOnly, config.cellularPromptMode)
    }

    private fun updateUIVisibility(wifiOnly: Boolean, promptMode: CellularPromptMode) {
        // 仅 WiFi 模式下隐藏流量提醒设置
        binding.llDownloadDataUseGroup.isVisible = !wifiOnly
        // 智能提醒（交给用户）模式下显示阈值选择
        binding.llDownloadDataLimitGroup.isVisible = !wifiOnly && promptMode == CellularPromptMode.USER_CONTROLLED
    }
}