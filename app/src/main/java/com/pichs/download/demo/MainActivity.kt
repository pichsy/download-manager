package com.pichs.download.demo

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.os.Environment
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.drake.brv.utils.bindingAdapter
import com.drake.brv.utils.grid
import com.drake.brv.utils.setup
import com.hjq.permissions.Permission
import com.hjq.permissions.XXPermissions
import com.pichs.download.DownloadTask
import com.pichs.download.Downloader
import com.pichs.download.callback.DownloadListener
import com.pichs.download.demo.databinding.ActivityMainBinding
import com.pichs.download.demo.databinding.ItemDownloadTaskBinding
import com.pichs.download.demo.databinding.ItemGridDownloadBeanBinding
import com.pichs.download.entity.DownloadStatus
import com.pichs.download.utils.SpeedUtils
import com.pichs.shanhai.base.base.BaseActivity
import com.pichs.shanhai.base.utils.LogUtils
import com.pichs.shanhai.base.utils.toast.ToastUtils
import com.pichs.xbase.kotlinext.fastClick
import com.pichs.xbase.utils.GsonUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : BaseActivity<ActivityMainBinding>() {

    private val list = arrayListOf<DownloadItem>()

    override fun afterOnCreate() {

        XXPermissions.with(this)
            .unchecked()
            .permission(Permission.MANAGE_EXTERNAL_STORAGE)
            .request { permissions, allGranted ->
            }

        initListener()

//        binding.bezerView.setRect(100f, 100f, 300f, 300f)
//
//        binding.bezerView.setBezierByCubic(
//            leftCubic = floatArrayOf(0.25f, 0.75f, 0.25f, 0.75f),
//            rightCubic =floatArrayOf(0.75f, 0.25f, 0.75f, 0.25f),
//            offsetX = 120f
//        )


        val appJsonStr = assets.open("app_list.json").bufferedReader().use { it.readText() }
        val appListBean = GsonUtils.fromJson<AppListBean>(appJsonStr, AppListBean::class.java)
        appListBean.appList?.let { list.addAll(it) }


        lifecycleScope.launch {
            Downloader.with().queryAllTasksFromCache().forEach {
                LogUtils.d("download666", "查询到的下载任务:$it")
            }
        }

        initRecyclerView()
    }

    private fun initListener() {
        binding.ivDownloadManager.setOnClickListener {
            startActivity(Intent(this, DownloadManagerActivity::class.java))
        }

        binding.ivSearch.setOnClickListener {
            ToastUtils.show("搜索")
        }
    }


    @SuppressLint("SetTextI18n")
    private fun initRecyclerView() {
        binding.recyclerView.grid(4).setup {
            addType<DownloadItem>(R.layout.item_grid_download_bean)
            onPayload { payloads ->
                val tag = payloads.firstOrNull()?.toString()
                val item = getModel<DownloadItem>()
                val itemBinding = getBinding<ItemGridDownloadBeanBinding>()
                LogUtils.d("下载管理：onPayload=====item=${item.task?.downloadInfo?.status}, tag=$tag")
                if (tag == "status") {
                    when (item.task?.downloadInfo?.status) {
                        DownloadStatus.DEFAULT -> {
                            itemBinding.btnDownload.setText("下载")
                        }

                        DownloadStatus.DOWNLOADING -> {
                            itemBinding.btnDownload.setText("0%")

                        }

                        DownloadStatus.WAITING -> {
                            itemBinding.btnDownload.setText("等待中")
                        }

                        DownloadStatus.PAUSE -> {
                            itemBinding.btnDownload.setText("暂停")
                        }

                        DownloadStatus.COMPLETED -> {
                            itemBinding.btnDownload.setText("安装")
                        }

                        DownloadStatus.ERROR, DownloadStatus.CANCEL -> {
                            // 下载出错。
                            itemBinding.btnDownload.setText("重新下载")
                        }

                        DownloadStatus.WAITING_WIFI -> {
                            // 等待wifi下载
                            itemBinding.btnDownload.setText("等待wifi")
                        }

                        else -> {
                            itemBinding.btnDownload.setText("下载")
                        }
                    }
                } else if (tag == "progress") {
                    itemBinding.btnDownload.setProgress(item.task?.downloadInfo?.progress ?: 0)
                    itemBinding.btnDownload.setText("${item.task?.downloadInfo?.progress ?: 0}%")
                }
            }

            onBind {
                val item = getModel<DownloadItem>()
                val itemBinding = getBinding<ItemGridDownloadBeanBinding>()
                itemBinding.tvAppName.text = item.name ?: "未知应用"
                Glide.with(itemBinding.ivCover)
                    .load(item.icon ?: "https://android-artworks.25pp.com/fs08/2025/07/10/4/110_f93562a47e4623a0037084abae9f4cc3_con_130x130.png")
                    .into(itemBinding.ivCover)

                when (item.task?.downloadInfo?.status) {
                    DownloadStatus.DEFAULT -> {
                        itemBinding.btnDownload.setText("下载")
                    }

                    DownloadStatus.DOWNLOADING -> {
                        itemBinding.btnDownload.setText("0%")
                    }

                    DownloadStatus.WAITING -> {
                        itemBinding.btnDownload.setText("等待中")
                    }

                    DownloadStatus.PAUSE -> {
                        itemBinding.btnDownload.setText("暂停")
                    }

                    DownloadStatus.COMPLETED -> {
                        itemBinding.btnDownload.setText("安装")
                    }

                    DownloadStatus.ERROR, DownloadStatus.CANCEL -> {
                        // 下载出错。
                        itemBinding.btnDownload.setText("重新下载")
                    }

                    DownloadStatus.WAITING_WIFI -> {
                        // 等待wifi下载
                        itemBinding.btnDownload.setText("等待wifi")
                    }

                    else -> {
                        itemBinding.btnDownload.setText("下载")
                    }
                }

                itemBinding.btnDownload.fastClick {
                    // 可能未进行下载。点击后添加任务中。
                    if (item.task == null) {
                        item.task = Downloader.Builder()
                            .setListener(OnDownloadListener())
                            .setDownloadTaskInfo {
                                url = item.url
                                filePath =
                                    externalCacheDir?.absolutePath
                                        ?: Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
                                fileName = item.name + ".apk"
                                extra = item.name
                            }
                            .build()
                            .pushTask()
                        return@fastClick
                    }
                    LogUtils.d("下载管理：点击下载按钮，item=${item.task?.downloadInfo?.status}, name=${item.name}")
                    if (item.task?.downloadInfo?.status == DownloadStatus.COMPLETED) {
                        // 下载完成，点击安装
                        ToastUtils.show("开始安装 ${item.name}")
                    } else if (item.task?.downloadInfo?.status == DownloadStatus.DEFAULT) {
                        item.task = Downloader.Builder()
                            .setListener(OnDownloadListener())
                            .setDownloadTaskInfo {
                                url = item.url
                                filePath =
                                    externalCacheDir?.absolutePath
                                        ?: Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
                                fileName = item.name + ".apk"
                                extra = item.name
                            }
                            .build()
                            .pushTask()
                    } else if (item.task?.downloadInfo?.status == DownloadStatus.PAUSE) {
                        Downloader.with().resumeTask(item.task?.getTaskId() ?: "")
                    }
                }
            }
        }.models = list
    }


    /**
     * 下载监听器
     */
    inner class OnDownloadListener : DownloadListener() {
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


    private fun updateTaskStatus(task: DownloadTask, status: Int) {
        lifecycleScope.launch(Dispatchers.Main) {
            val index = list.indexOfFirst { it.task?.getTaskId() == task.getTaskId() }
            if (index != -1) {
                list.getOrNull(index)?.task?.downloadInfo?.status = status
                binding.recyclerView.bindingAdapter.notifyItemChanged(index, "status")
            }
        }
    }

    private fun updateTaskProgress(task: DownloadTask, progress: Int, currentLength: Long, totalLength: Long, speed: Long) {
        lifecycleScope.launch(Dispatchers.Main) {
            val index = list.indexOfFirst { it.task?.getTaskId() == task.getTaskId() }
            if (index != -1) {
                list.getOrNull(index)?.task?.downloadInfo?.apply {
                    this.progress = progress
                    this.currentLength = currentLength
                    this.totalLength = totalLength
                    this.speed = speed
                }
                binding.recyclerView.bindingAdapter.notifyItemChanged(index, "progress")
            }
        }
    }


    @SuppressLint("NotifyDataSetChanged")
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 如果是横屏，就grid(6)
        // 如果是是竖屏，就grid(4)
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            binding.recyclerView.post {
                binding.recyclerView.grid(7).adapter?.notifyDataSetChanged()
            }
        } else {
            binding.recyclerView.grid(4).adapter?.notifyDataSetChanged()
        }
    }
}