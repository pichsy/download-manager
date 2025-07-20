package com.pichs.download.demo

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.os.Environment
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.drake.brv.utils.grid
import com.drake.brv.utils.linear
import com.drake.brv.utils.setup
import com.hjq.permissions.Permission
import com.hjq.permissions.XXPermissions
import com.pichs.download.DownloadTask
import com.pichs.download.Downloader
import com.pichs.download.call.DownloadMultiCall
import com.pichs.download.callback.DownloadListener
import com.pichs.download.callback.IDownloadListener
import com.pichs.download.demo.DownloadManagerActivity
import com.pichs.download.demo.databinding.ActivityMainBinding
import com.pichs.download.demo.databinding.ItemDonwloadListBinding
import com.pichs.download.demo.databinding.ItemDownloadTaskBinding
import com.pichs.download.demo.databinding.ItemGridDownloadBeanBinding
import com.pichs.download.dispatcher.DispatcherListener
import com.pichs.download.entity.DownloadStatus
import com.pichs.download.utils.SpeedUtils
import com.pichs.download.utils.TaskIdUtils
import com.pichs.shanhai.base.base.BaseActivity
import com.pichs.shanhai.base.ext.click
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
                val item = getModel<DownloadItem>()
                val itemBinding = getBinding<ItemGridDownloadBeanBinding>()
                itemBinding.tvAppName.text = item.name ?: "未知应用"
                Glide.with(itemBinding.ivCover)
                    .load(item.icon ?: "https://android-artworks.25pp.com/fs08/2025/07/10/4/110_f93562a47e4623a0037084abae9f4cc3_con_130x130.png")
                    .into(itemBinding.ivCover)

                when (item.task?.downloadInfo?.status) {
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

                itemBinding.btnDownload.fastClick {
                    // 可能未进行下载。点击后添加任务中。
                    item.task = Downloader.Builder()
                        .setListener(OnDownloadListener())
                        .setDownloadTaskInfo {
                            url = item.url
                            filePath = externalCacheDir?.absolutePath ?: Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
                            fileName = item.name + ".apk"
                            extra = item.name
                        }
                        .build()
                        .also { item.task = it }
                        .pushTask()
                }
            }
        }.models = list
    }


    /**
     * 下载监听器
     */
    inner class OnDownloadListener : DownloadListener() {
        override fun onStart(task: DownloadTask?, totalLength: Long) {
            LogUtils.d(
                "download888",
                "下载进度 app:${task?.downloadInfo?.fileName}: 开始下载：${task?.downloadInfo?.filePath},${task?.downloadInfo?.url}, name:${task?.downloadInfo?.fileName},"
            )
        }

        override fun onPrepare(task: DownloadTask?) {
            LogUtils.d(
                "download888",
                "下载进度 app:${task?.downloadInfo?.fileName}: 准备下载：${task?.downloadInfo?.filePath}, ${task?.downloadInfo?.url}, name:${task?.downloadInfo?.fileName}"
            )
        }

        override fun onPause(task: DownloadTask?) {
            LogUtils.d(
                "download888",
                "下载进度 app:${task?.downloadInfo?.fileName}: 下载暂停：${task?.downloadInfo?.filePath}, ${task?.downloadInfo?.url}, name:${task?.downloadInfo?.fileName}"
            )
        }

        override fun onComplete(task: DownloadTask?) {
            LogUtils.d("download888", "下载进度 app:${task?.downloadInfo?.fileName}: 下载完毕：${task?.downloadInfo?.filePath}")
        }

        override fun onProgress(task: DownloadTask?, currentLength: Long, totalLength: Long, progress: Int, speed: Long) {
            LogUtils.d(
                "download888",
                "下载进度 app:${task?.downloadInfo?.fileName}: $progress%, speed: $speed, currentLength: $currentLength, totalLength: $totalLength"
            )
        }

        override fun onCancel(task: DownloadTask?) {
            LogUtils.d("download888", "下载进度 app:${task?.downloadInfo?.fileName}: 下载取消")
        }

        override fun onError(task: DownloadTask?, e: Throwable?) {
            LogUtils.e("download888", "下载进度 app:${task?.downloadInfo?.fileName}: 下载失败: ${e?.message}", e)
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