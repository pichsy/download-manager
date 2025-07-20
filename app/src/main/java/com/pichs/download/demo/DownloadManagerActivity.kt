package com.pichs.download.demo

import android.os.Environment
import androidx.lifecycle.lifecycleScope
import com.drake.brv.utils.bindingAdapter
import com.drake.brv.utils.linear
import com.drake.brv.utils.setup
import com.pichs.download.DownloadTask
import com.pichs.download.Downloader
import com.pichs.download.callback.IDownloadListener
import com.pichs.download.demo.databinding.ActivityDownloadManagerBinding
import com.pichs.download.demo.databinding.ItemDownloadTaskBinding
import com.pichs.download.entity.DownloadStatus
import com.pichs.download.utils.FileUtils
import com.pichs.download.utils.SpeedUtils
import com.pichs.download.utils.addApkExtensionIfMissing
import com.pichs.shanhai.base.base.BaseActivity
import com.pichs.shanhai.base.utils.LogUtils
import com.pichs.shanhai.base.utils.toast.ToastUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.internal.format
import java.math.RoundingMode


class DownloadManagerActivity : BaseActivity<ActivityDownloadManagerBinding>(), IDownloadListener {

    private val downloadTasks = mutableListOf<DownloadTask>()


    override fun afterOnCreate() {
        lifecycleScope.launch {
            initRecyclerView()

            // 初始化下载器l
            loadTaskList()

            Downloader.with().addGlobalListener(this@DownloadManagerActivity)
        }
    }

    private suspend fun loadTaskList() {
        val list = Downloader.with().queryAllTasksFromCache()
        downloadTasks.clear()
        downloadTasks.addAll(list)
        binding.recyclerView.bindingAdapter.models = downloadTasks
    }

    private fun initRecyclerView() {
        binding.recyclerView.linear().setup {
            addType<DownloadTask>(R.layout.item_download_task)
            onPayload { payloads ->
                val tag = payloads.firstOrNull()?.toString()
                val item = getModel<DownloadTask>()
                val itemBinding = getBinding<ItemDownloadTaskBinding>()
                LogUtils.d("下载管理：onPayload=====item=${item.downloadInfo.status}, tag=$tag")
                if (tag == "status") {
                    when (item.downloadInfo.status) {
                        DownloadStatus.DEFAULT -> {
                            itemBinding.btnDownload.text = "下载"
                        }

                        DownloadStatus.DOWNLOADING -> {
                            itemBinding.btnDownload.text = "下载中"
                        }

                        DownloadStatus.WAITING -> {
                            itemBinding.btnDownload.text = "等待中"
                        }

                        DownloadStatus.PAUSE -> {
                            itemBinding.btnDownload.text = "暂停"
                        }

                        DownloadStatus.COMPLETED -> {
                            itemBinding.btnDownload.text = "安装"
                        }

                        DownloadStatus.ERROR, DownloadStatus.CANCEL -> {
                            // 下载出错。
                            itemBinding.btnDownload.text = "重新下载"
                        }

                        DownloadStatus.WAITING_WIFI -> {
                            // 等待wifi下载
                            itemBinding.btnDownload.text = "等待wifi"
                        }
                    }
                } else if (tag == "progress") {
                    itemBinding.progressBar.progress = item.downloadInfo.progress ?: 0
                    itemBinding.tvProgress.text = "${item.downloadInfo.progress ?: 0}%"
                    itemBinding.tvSpeed.text = SpeedUtils.formatDownloadSpeed(item.downloadInfo.speed)
                }
            }

            onBind {
                val item = getModel<DownloadTask>()
                LogUtils.d("下载管理：onBind=====item=${item.downloadInfo.fileName}")
                val itemBinding = getBinding<ItemDownloadTaskBinding>()
                itemBinding.tvTitle.text = item.downloadInfo.fileName
                itemBinding.progressBar.progress = item.downloadInfo.progress ?: 0
                itemBinding.tvProgress.text = "${item.downloadInfo.progress ?: 0}%"
                itemBinding.tvSpeed.text = SpeedUtils.formatDownloadSpeed(item.downloadInfo.speed)
                when (item.downloadInfo.status) {
                    DownloadStatus.DEFAULT -> {
                        itemBinding.btnDownload.text = "下载"
                    }
                    DownloadStatus.DOWNLOADING -> {
                        itemBinding.btnDownload.text = "下载中"
                    }

                    DownloadStatus.WAITING -> {
                        itemBinding.btnDownload.text = "等待中"
                    }

                    DownloadStatus.PAUSE -> {
                        itemBinding.btnDownload.text = "暂停"
                    }

                    DownloadStatus.COMPLETED -> {
                        itemBinding.btnDownload.text = "安装"
                    }

                    DownloadStatus.ERROR, DownloadStatus.CANCEL -> {
                        // 下载出错。
                        itemBinding.btnDownload.text = "重新下载"
                    }

                    DownloadStatus.WAITING_WIFI -> {
                        // 等待wifi下载
                        itemBinding.btnDownload.text = "等待wifi"
                    }
                }
            }

            onClick(R.id.btn_download) {
                val item = getModel<DownloadTask>()
                when (item.downloadInfo.status) {
                    DownloadStatus.DEFAULT -> {
                        Downloader.Builder()
                            .setDownloadTaskInfo {
                                url = item.downloadInfo.url
                                filePath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
                                fileName = item.downloadInfo.fileName.addApkExtensionIfMissing()
                            }
                            .build()
                            .pushTask()
                    }

                    DownloadStatus.WAITING -> {
                        // 可能未进行下载。点击后添加任务中。
                        Downloader.Builder()
                            .setDownloadTaskInfo {
                                url = item.downloadInfo.url
                                filePath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
                                fileName = item.downloadInfo.fileName.addApkExtensionIfMissing()
                            }
                            .build()
                            .pushTask()
                    }

                    DownloadStatus.DOWNLOADING -> {
                        // 暂停下载
                        Downloader.with().pauseTask(item.getTaskId())
                    }

                    DownloadStatus.PAUSE -> {

                    }

                    DownloadStatus.COMPLETED -> {
//                        startInstall(item)
                    }

                    DownloadStatus.ERROR -> {

                    }

                    DownloadStatus.CANCEL -> {
                        ToastUtils.toast("任务已移除!")
                    }

                    DownloadStatus.WAITING_WIFI -> {
                        ToastUtils.toast("等待wifi下载")
                    }
                }
            }
        }
    }


    private fun updateTaskStatus(task: DownloadTask, status: Int) {
        lifecycleScope.launch(Dispatchers.Main) {
            val index = downloadTasks.indexOfFirst { it == task }
            if (index != -1) {
                downloadTasks[index].downloadInfo.status = status
                binding.recyclerView.bindingAdapter.notifyItemChanged(index, "status")
            }
        }
    }

    private fun updateTaskProgress(task: DownloadTask, progress: Int, currentLength: Long, totalLength: Long, speed: Long) {
        lifecycleScope.launch(Dispatchers.Main) {
            val index = downloadTasks.indexOfFirst { it.getTaskId() == task.getTaskId() }
            if (index != -1) {
                downloadTasks[index].downloadInfo.progress = progress
                downloadTasks[index].downloadInfo.currentLength = currentLength
                downloadTasks[index].downloadInfo.totalLength = totalLength
                downloadTasks[index].downloadInfo.speed = speed
                binding.recyclerView.bindingAdapter.notifyItemChanged(index, "progress")
            }
        }
    }


    override fun onDestroy() {
        Downloader.with().removeGlobalListener(this@DownloadManagerActivity)
        super.onDestroy()
    }

    // 回调========================================================================

    override fun onPrepare(task: DownloadTask?) {
        if (task == null) return
        LogUtils.d("Manager:下载进度888 app:${task.downloadInfo.fileName}: 准备下载：${task.downloadInfo.filePath}, ${task.downloadInfo.url}, name:${task.downloadInfo.fileName}")
        updateTaskStatus(task, DownloadStatus.WAITING)
    }


    override fun onStart(task: DownloadTask?, totalLength: Long) {
        if (task == null) return
        LogUtils.d("Manager:下载进度888 app:${task.downloadInfo.fileName}: 开始下载：${task.downloadInfo.filePath}, ${task.downloadInfo.url}, name:${task.downloadInfo.fileName}")
        updateTaskStatus(task, DownloadStatus.DOWNLOADING)
    }

    override fun onPause(task: DownloadTask?) {
        if (task == null) return
        LogUtils.d("Manager:下载进度888 app:${task.downloadInfo.fileName}: 下载暂停：${task.downloadInfo.filePath}, ${task.downloadInfo.url}, name:${task.downloadInfo.fileName}")
        updateTaskStatus(task, DownloadStatus.PAUSE)
    }

    override fun onProgress(task: DownloadTask?, currentLength: Long, totalLength: Long, progress: Int, speed: Long) {
        LogUtils.d("Manager:下载进度888 app:${task?.downloadInfo?.fileName}: $progress%, speed: $speed, currentLength: $currentLength, totalLength: $totalLength")
        if (task == null) return
        updateTaskProgress(task, progress, currentLength, totalLength, speed)
    }

    override fun onComplete(task: DownloadTask?) {
        if (task == null) return
        LogUtils.d("Manager:下载进度888 app:${task.downloadInfo.fileName}: 下载完毕：${task.downloadInfo.filePath}")
        updateTaskStatus(task, DownloadStatus.COMPLETED)
    }

    override fun onError(task: DownloadTask?, e: Throwable?) {
        if (task == null) return
        LogUtils.e("", "Manager:下载进度888 app:${task.downloadInfo.fileName}: 下载失败: ${e?.message}", e)
        updateTaskStatus(task, DownloadStatus.ERROR)
    }

    override fun onCancel(task: DownloadTask?) {
        if (task == null) return
        LogUtils.d("Manager:下载进度888 app:${task.downloadInfo.fileName}: 下载取消")
        updateTaskStatus(task, DownloadStatus.CANCEL)
    }
}
