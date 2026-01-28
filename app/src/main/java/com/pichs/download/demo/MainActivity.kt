package com.pichs.download.demo

import android.content.Intent
import android.content.res.Configuration
import android.provider.Settings
import androidx.activity.viewModels
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.pichs.download.core.DownloadManager
import com.pichs.download.demo.databinding.ActivityMainBinding
import com.pichs.download.utils.DownloadLog
import com.pichs.shanhai.base.base.BaseActivity
import com.pichs.shanhai.base.receiver.InstallBroadcastReceiver
import com.pichs.shanhai.base.receiver.NetworkMonitor
import com.pichs.shanhai.base.utils.toast.ToastUtils
import com.pichs.download.demo.floatwindow.FloatBallView
import com.pichs.download.model.DownloadTask
import com.pichs.xbase.utils.GsonUtils

class MainActivity : BaseActivity<ActivityMainBinding>() {

    private val viewModel: MainViewModel by viewModels()

    // 悬浮球
    private var floatBallView: FloatBallView? = null

    val GRANT_PERMISSIONS = "com.gankao.dpc.request.GRANT_PERMISSIONS"
    val REMOVE_PERMISSIONS = "com.gankao.dpc.request.REMOVE_PERMISSIONS"

    private var homeFragment: HomeFragment? = null
    private var rankFragment: RankFragment? = null
    private var mineFragment: MineFragment? = null
    private var currentFragment: Fragment? = null

    // Flow监听器 (For FloatBall)
    private val flowListener = DownloadManager.flowListener

    override fun afterOnCreate() {
        NetworkMonitor(onNetworkChanged = { isWifi ->
            DownloadLog.d("网络类型变化，isWifi=$isWifi")
            if (isWifi) {
                ToastUtils.show("WIFI已连接")
            } else {
                ToastUtils.show("数据流量已连接")
            }
            DownloadManager.onNetworkRestored()
        }, onNetworkLost = {
            DownloadLog.d("网络断开")
            ToastUtils.show("网络已断开")
        }).register(this)

        if (Settings.canDrawOverlays(this@MainActivity)) {
            showFloatBall()
            sendBroadcast(Intent(REMOVE_PERMISSIONS).apply {
                putExtra("packageName", packageName)
            })
        }

        InstallBroadcastReceiver().register(this)

        initBottomNavigation()
        bindFlowListener()

        DownloadManager.setCheckAfterCallback(MyCheckAfterCallback(this))
        // 回调设置完成后，恢复中断的任务（僵尸任务 + 非用户手动暂停的任务）
        DownloadManager.restoreInterruptedTasks()
    }

    private fun initBottomNavigation() {
        binding.navView.itemIconTintList = null
        binding.navView.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> switchFragment(0)
                R.id.nav_rank -> switchFragment(1)
                R.id.nav_mine -> switchFragment(2)
            }
            true
        }
        // Default select home
        switchFragment(0)
    }

    private fun switchFragment(index: Int) {
        val transaction = supportFragmentManager.beginTransaction()

        // Hide current
        currentFragment?.let { transaction.hide(it) }

        when (index) {
            0 -> {
                if (homeFragment == null) {
                    homeFragment = HomeFragment()
                    transaction.add(R.id.fragment_container, homeFragment!!)
                } else {
                    transaction.show(homeFragment!!)
                }
                currentFragment = homeFragment
            }

            1 -> {
                if (rankFragment == null) {
                    rankFragment = RankFragment()
                    transaction.add(R.id.fragment_container, rankFragment!!)
                } else {
                    transaction.show(rankFragment!!)
                }
                currentFragment = rankFragment
            }

            2 -> {
                if (mineFragment == null) {
                    mineFragment = MineFragment()
                    transaction.add(R.id.fragment_container, mineFragment!!)
                } else {
                    transaction.show(mineFragment!!)
                }
                currentFragment = mineFragment
            }
        }
        transaction.commitAllowingStateLoss()
    }

    private fun showFloatBall() {
        if (floatBallView == null) {
            floatBallView = FloatBallView(this).apply {
                setOnFloatClickListener {
                    startActivity(Intent(this@MainActivity, DownloadManagerActivity::class.java))
                }
                setOnDismissListener {
                    floatBallView = null
                }
            }
            floatBallView?.show()
        }
    }

    private fun bindFlowListener() {
        flowListener.bindToLifecycle(lifecycleOwner = this, onTaskProgress = { task, progress, speed ->
            if (isDestroyed) return@bindToLifecycle
            updateFloatBallProgress(task, progress, speed)
        }, onTaskComplete = { task, file ->
            // ensure float ball knows?
        }, onTaskError = { task, error ->
        }, onTaskPaused = { task ->
        }, onTaskResumed = { task ->
        }, onTaskCancelled = { task ->
        })
    }

    private fun updateFloatBallProgress(task: DownloadTask, progress: Int, speed: Long) {
        val speedText = formatSpeed(speed)
        var appName = task.fileName
        try {
            if (!task.extras.isNullOrEmpty()) {
                val meta = GsonUtils.fromJson(task.extras!!, ExtraMeta::class.java)
                if (!meta.name.isNullOrEmpty()) {
                    appName = meta.name
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        floatBallView?.updateProgress(appName, progress, speedText)
    }

    private fun formatSpeed(bytesPerSecond: Long): String {
        return when {
            bytesPerSecond >= 1024 * 1024 -> String.format("%.1fMB/s", bytesPerSecond / (1024.0 * 1024.0))
            bytesPerSecond >= 1024 -> String.format("%.0fKB/s", bytesPerSecond / 1024.0)
            else -> "${bytesPerSecond}B/s"
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
    }

    override fun onDestroy() {
        floatBallView?.dismiss()
        floatBallView = null
        super.onDestroy()
    }
}