package com.pichs.download.demo.floatwindow

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.pichs.download.core.DownloadManager
import com.pichs.download.demo.DownloadManagerActivity
import com.pichs.download.demo.ExtraMeta
import com.pichs.download.model.DownloadStatus
import com.pichs.download.utils.SpeedUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 悬浮球管理帮助类
 * 绑定 Activity 生命周期，自动管理悬浮窗的显示和隐藏
 * 自动监听下载进度并更新悬浮球UI
 */
class FloatBallHelper(private val context: Context) : DefaultLifecycleObserver {

    private var floatBallView: FloatBallView? = null
    private var lifecycleOwner: LifecycleOwner? = null
    private val flowListener = DownloadManager.flowListener

    /**
     * 绑定生命周期
     * 在 Activity onDestroy 时自动隐藏悬浮窗
     */
    fun bind(lifecycleOwner: LifecycleOwner): FloatBallHelper {
        this.lifecycleOwner = lifecycleOwner
        lifecycleOwner.lifecycle.addObserver(this)
        bindDownloadListener()
        return this
    }

    /**
     * 绑定下载监听器
     */
    private fun bindDownloadListener() {
        lifecycleOwner?.let { owner ->
            flowListener.bindToLifecycle(
                lifecycleOwner = owner,
                onTaskProgress = { task, progress, speed ->
                    // 获取应用名
                    val meta = ExtraMeta.fromJson(task.extras)
                    val appName = meta?.name ?: task.fileName.substringBeforeLast(".")
                    val speedText = SpeedUtils.formatDownloadSpeed(speed)
                    floatBallView?.updateProgress(appName, progress, speedText)
                },
                onTaskComplete = { task, _ ->
                    // 下载完成，检查是否还有其他正在下载的任务
                    checkAndUpdateActiveTask()
                },
                onTaskError = { task, _ ->
                    checkAndUpdateActiveTask()
                },
                onTaskPaused = { task ->
                    checkAndUpdateActiveTask()
                },
                onTaskResumed = { task ->
                    val meta = ExtraMeta.fromJson(task.extras)
                    val appName = meta?.name ?: task.fileName.substringBeforeLast(".")
                    floatBallView?.updateProgress(appName, task.progress, "0KB/s")
                },
                onTaskCancelled = { task ->
                    checkAndUpdateActiveTask()
                }
            )
        }
    }

    /**
     * 检查并更新当前活跃的下载任务
     */
    private fun checkAndUpdateActiveTask() {
        CoroutineScope(Dispatchers.Main).launch {
            val allTasks = DownloadManager.getAllTasks()
            val activeTask = allTasks.firstOrNull { 
                it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.WAITING 
            }
            if (activeTask != null) {
                val meta = ExtraMeta.fromJson(activeTask.extras)
                val appName = meta?.name ?: activeTask.fileName.substringBeforeLast(".")
                val speedText = SpeedUtils.formatDownloadSpeed(activeTask.speed)
                floatBallView?.updateProgress(appName, activeTask.progress, speedText)
            } else {
                // 没有活跃任务，显示默认状态
                floatBallView?.updateProgress("无下载", 0, "0KB/s")
            }
        }
    }

    /**
     * 显示悬浮球
     * 需要悬浮窗权限
     */
    fun show() {
        if (!Settings.canDrawOverlays(context)) {
            return
        }
        if (floatBallView == null) {
            floatBallView = FloatBallView(context).apply {
                setOnFloatClickListener {
                    // 点击悬浮球跳转到下载管理页
                    context.startActivity(
                        Intent(context, DownloadManagerActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                setOnDismissListener {
                    // 悬浮球被拖入删除区域隐藏
                    floatBallView = null
                }
            }
        }
        floatBallView?.show()
        // 显示后立即检查当前下载状态
        checkAndUpdateActiveTask()
    }

    /**
     * 隐藏悬浮球
     */
    fun hide() {
        floatBallView?.dismiss()
        floatBallView = null
    }

    /**
     * 更新下载进度
     */
    fun updateProgress(appName: String, progress: Int, speed: String) {
        floatBallView?.updateProgress(appName, progress, speed)
    }

    /**
     * 悬浮球是否正在显示
     */
    fun isShowing(): Boolean {
        return floatBallView?.isShowing() == true
    }

    // ===== 生命周期回调 =====

    override fun onDestroy(owner: LifecycleOwner) {
        hide()
        lifecycleOwner = null
        owner.lifecycle.removeObserver(this)
    }
}
