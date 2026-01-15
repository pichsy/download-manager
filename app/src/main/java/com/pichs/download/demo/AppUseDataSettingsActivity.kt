package com.pichs.download.demo

import android.content.Context
import androidx.core.view.isVisible
import com.pichs.download.core.DownloadManager
import com.pichs.download.demo.databinding.ActivityAppUseDataSettingsBinding
import com.pichs.download.model.CellularThreshold
import com.pichs.shanhai.base.base.BaseActivity
import com.pichs.shanhai.base.utils.toast.ToastUtils
import com.pichs.xbase.clickhelper.fastClick

class AppUseDataSettingsActivity : BaseActivity<ActivityAppUseDataSettingsBinding>() {

    companion object {
        private const val PREFS_NAME = "app_settings_cache"
        private const val KEY_LAST_SMART_THRESHOLD_INDEX = "last_smart_threshold_index"
    }

    // 预设阈值（MB）
    private val thresholds = listOf(20, 50, 100, 200, 500, 1000)
    
    // 本地缓存（用于记住智能提醒的阈值选择，即使切换到其他模式也不清理）
    private val prefs by lazy { 
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) 
    }

    override fun afterOnCreate() {
        binding.ivBack.fastClick {
            finish()
        }

        // 加载当前配置
        loadConfig()

        binding.switchPreCheck.setOnCheckedChangeListener { v, isChecked ->
            ToastUtils.show("开关：newChecked=${isChecked}")
            val config = DownloadManager.getNetworkConfig()
            com.pichs.download.utils.DownloadLog.d("Settings", "切换前置检查: $isChecked, 当前config: $config")
            DownloadManager.setNetworkConfig(config.copy(checkBeforeCreate = isChecked))
            com.pichs.download.utils.DownloadLog.d("Settings", "保存后config: ${DownloadManager.getNetworkConfig()}")
        }

        // 监听网络模式切换（仅 WiFi / 允许流量）
        binding.llDownloadGroup.setOnRadioCheckedListener { _, _, isChecked, position ->
            if (isChecked) {
                val config = DownloadManager.getNetworkConfig()
                val wifiOnly = position == 0
                DownloadManager.setNetworkConfig(config.copy(wifiOnly = wifiOnly))
                updateUIVisibility(wifiOnly, config.cellularThreshold)
            }
        }

        // 监听流量提醒模式切换
        // 0: 每次提醒, 1: 不再提醒, 2: 智能提醒
        binding.llDownloadDataUseGroup.setOnRadioCheckedListener { _, _, isChecked, position ->
            if (isChecked) {
                val config = DownloadManager.getNetworkConfig()
                val newThreshold = when (position) {
                    0 -> CellularThreshold.ALWAYS_PROMPT     // 每次提醒 (0)
                    1 -> CellularThreshold.NEVER_PROMPT      // 不再提醒 (Long.MAX_VALUE)
                    else -> {
                        // 智能提醒：使用上次缓存的阈值索引
                        val cachedIndex = getLastSmartThresholdIndex()
                        binding.llDownloadDataLimitGroup.select(cachedIndex)
                        getThresholdFromIndex(cachedIndex)
                    }
                }
                DownloadManager.setNetworkConfig(config.copy(cellularThreshold = newThreshold))
                // 更新阈值选择器可见性
                binding.llDownloadDataLimitGroup.isVisible = (position == 2)
            }
        }
        
        // 监听阈值选择
        binding.llDownloadDataLimitGroup.setOnRadioCheckedListener { _, _, isChecked, position ->
            if (isChecked && position in thresholds.indices) {
                val thresholdBytes = thresholds[position] * 1024L * 1024L
                val config = DownloadManager.getNetworkConfig()
                DownloadManager.setNetworkConfig(config.copy(cellularThreshold = thresholdBytes))
                // 缓存选择的阈值索引（即使切换到其他模式也不清理）
                saveLastSmartThresholdIndex(position)
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
        val threshold = config.cellularThreshold
        when {
            threshold == CellularThreshold.ALWAYS_PROMPT -> binding.llDownloadDataUseGroup.select(0)
            threshold == CellularThreshold.NEVER_PROMPT -> binding.llDownloadDataUseGroup.select(1)
            else -> binding.llDownloadDataUseGroup.select(2) // 智能提醒（有阈值）
        }
        
        // 设置阈值选择
        val thresholdIndex = if (threshold != CellularThreshold.ALWAYS_PROMPT && threshold != CellularThreshold.NEVER_PROMPT) {
            // 智能提醒模式：从当前配置计算索引
            val thresholdMB = (threshold / (1024 * 1024)).toInt()
            thresholds.indexOf(thresholdMB).takeIf { it >= 0 } ?: getLastSmartThresholdIndex()
        } else {
            // 非智能提醒模式：使用缓存的索引
            getLastSmartThresholdIndex()
        }
        binding.llDownloadDataLimitGroup.select(thresholdIndex)

        // 更新 UI 可见性
        updateUIVisibility(config.wifiOnly, config.cellularThreshold)
    }

    private fun updateUIVisibility(wifiOnly: Boolean, threshold: Long) {
        // 仅 WiFi 模式下隐藏流量提醒设置
        binding.llDownloadDataUseGroup.isVisible = !wifiOnly
        // 智能提醒模式下显示阈值选择
        val isSmartMode = threshold != CellularThreshold.ALWAYS_PROMPT && threshold != CellularThreshold.NEVER_PROMPT
        binding.llDownloadDataLimitGroup.isVisible = !wifiOnly && isSmartMode
    }
    
    private fun getThresholdFromIndex(index: Int): Long {
        return if (index in thresholds.indices) {
            thresholds[index] * 1024L * 1024L
        } else {
            100 * 1024L * 1024L // 默认 100MB
        }
    }
    
    // 获取上次选择的智能阈值索引（默认索引 2 = 100MB）
    private fun getLastSmartThresholdIndex(): Int {
        return prefs.getInt(KEY_LAST_SMART_THRESHOLD_INDEX, 2)
    }
    
    // 保存智能阈值索引（不会因为切换模式而清理）
    private fun saveLastSmartThresholdIndex(index: Int) {
        prefs.edit().putInt(KEY_LAST_SMART_THRESHOLD_INDEX, index).apply()
    }
}