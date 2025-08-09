package com.pichs.download.demo

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import androidx.core.content.FileProvider
import com.bumptech.glide.Glide
import com.drake.brv.utils.grid
import com.drake.brv.utils.setup
import com.hjq.permissions.Permission
import com.hjq.permissions.XXPermissions
import com.pichs.download.core.DownloadManager
import com.pichs.download.model.DownloadStatus
import com.pichs.download.model.DownloadTask
import com.pichs.download.demo.databinding.ActivityMainBinding
import com.pichs.download.demo.databinding.ItemGridDownloadBeanBinding
import com.pichs.shanhai.base.base.BaseActivity
import com.pichs.shanhai.base.utils.toast.ToastUtils
import com.pichs.xbase.utils.GsonUtils
import java.io.File

class MainActivity : BaseActivity<ActivityMainBinding>() {

    private val list = arrayListOf<DownloadItem>()
    private var globalListener: com.pichs.download.listener.DownloadListener? = null

    // 统一的名称归一化工具
    private fun normalizeName(n: String): String = n.substringBeforeLast('.').lowercase()

    override fun afterOnCreate() {
        XXPermissions.with(this)
            .unchecked()
            .permission(Permission.MANAGE_EXTERNAL_STORAGE)
            .permission(Permission.REQUEST_INSTALL_PACKAGES)
            .request { _, _ -> }

        initListener()

        val appJsonStr = assets.open("app_list.json").bufferedReader().use { it.readText() }
        val appListBean = GsonUtils.fromJson<AppListBean>(appJsonStr, AppListBean::class.java)
        appListBean.appList?.let { list.addAll(it) }
        
        initRecyclerView()
        // 绑定全局监听
        bindGlobalListener()
    }

    private fun initListener() {
        binding.ivDownloadManager.setOnClickListener {
            startActivity(Intent(this, DownloadManagerActivity::class.java))
        }

        binding.ivSearch.setOnClickListener { ToastUtils.show("搜索") }
    }

    @SuppressLint("SetTextI18n")
    private fun initRecyclerView() {
        binding.recyclerView.grid(4).setup {
            addType<DownloadItem>(R.layout.item_grid_download_bean)

            onBind {
                val item = getModel<DownloadItem>()
                val vb = getBinding<ItemGridDownloadBeanBinding>()
                if (!item.icon.isNullOrEmpty()) {
                    Glide.with(vb.ivCover).load(item.icon).into(vb.ivCover)
                } else {
                    vb.ivCover.setImageResource(R.color.purple_200)
                }
                vb.tvAppName.text = item.name
                bindButtonUI(vb, item.task)
                vb.btnDownload.setOnClickListener { handleClick(item, vb) }
                // 新增：点击封面跳转详情页
                vb.ivCover.setOnClickListener { openDetail(item) }
            }
        }.models = list
    }

    private fun handleClick(item: DownloadItem, vb: ItemGridDownloadBeanBinding) {
        val dir = externalCacheDir?.absolutePath ?: cacheDir.absolutePath
        val name = item.name
        val existing = DownloadManager.getAllTasks().firstOrNull {
            it.url == item.url && it.filePath == dir && normalizeName(it.fileName) == normalizeName(name)
        }
        val task = item.task ?: existing
        when (task?.status) {
            com.pichs.download.model.DownloadStatus.DOWNLOADING -> {
                DownloadManager.pause(task.id)
                // 文案显示当前进度
                vb.btnDownload.setText("${task.progress}%")
                vb.btnDownload.isEnabled = true
            }
            com.pichs.download.model.DownloadStatus.PAUSED -> {
                DownloadManager.resume(task.id)
                // 文案显示当前进度
                vb.btnDownload.setText("${task.progress}%")
                vb.btnDownload.isEnabled = true
            }
            com.pichs.download.model.DownloadStatus.PENDING -> {
                vb.btnDownload.setText("准备中")
                vb.btnDownload.isEnabled = false
            }
            com.pichs.download.model.DownloadStatus.COMPLETED -> openApk(task)
            com.pichs.download.model.DownloadStatus.FAILED -> startDownload(item, vb)
            else -> if (existing == null) startDownload(item, vb) else {
                bindButtonUI(vb, existing)
            }
        }
    }

    private fun startDownload(item: DownloadItem, vb: ItemGridDownloadBeanBinding) {
        val dir = externalCacheDir?.absolutePath ?: cacheDir.absolutePath
        val task = DownloadManager.download(item.url)
            .to(dir, item.name)
            .onProgress { progress, _ ->
                vb.btnDownload.setProgress(progress)
                vb.btnDownload.setText("${progress}%")
            }
            .onComplete { file ->
                vb.btnDownload.setProgress(100)
                vb.btnDownload.setText("安装")
                openApkFile(file)
            }
            .onError {
                vb.btnDownload.setText("重试")
            }
            .start()
        item.task = task
        bindButtonUI(vb, task)
    }

    private fun openApk(task: DownloadTask) {
        val file = File(task.filePath, task.fileName)
        openApkFile(file)
    }

    private fun openApkFile(file: File) {
        if (!file.exists()) return
        val uri: Uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setDataAndType(uri, "application/vnd.android.package-archive")
        }
        startActivity(intent)
    }

    private fun bindButtonUI(vb: ItemGridDownloadBeanBinding, task: DownloadTask?) {
        when (task?.status) {
            DownloadStatus.DOWNLOADING -> {
                vb.btnDownload.setText("${task.progress}%")
                vb.btnDownload.setProgress(task.progress)
                vb.btnDownload.isEnabled = true
            }
            DownloadStatus.PAUSED -> {
                vb.btnDownload.setText("${task.progress}%")
                vb.btnDownload.setProgress(task.progress)
                vb.btnDownload.isEnabled = true
            }
            DownloadStatus.PENDING -> {
                vb.btnDownload.setText("准备中")
                vb.btnDownload.isEnabled = false
            }
            DownloadStatus.COMPLETED -> {
                vb.btnDownload.setText("安装")
                vb.btnDownload.setProgress(100)
                vb.btnDownload.isEnabled = true
            }
            DownloadStatus.FAILED -> {
                vb.btnDownload.setText("重试")
                vb.btnDownload.isEnabled = true
            }
            else -> {
                vb.btnDownload.setText("下载")
                vb.btnDownload.setProgress(0)
                vb.btnDownload.isEnabled = true
            }
        }
    }

    // 新增：绑定全局监听，保持列表项与任务状态同步
    private fun bindGlobalListener() {
        val listener = object : com.pichs.download.listener.DownloadListener {
            override fun onTaskProgress(task: DownloadTask, progress: Int, speed: Long) {
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    updateItemTask(task)
                }
            }

            override fun onTaskComplete(task: DownloadTask, file: File) {
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    updateItemTask(task)
                }
            }

            override fun onTaskError(task: DownloadTask, error: Throwable) {
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    updateItemTask(task)
                }
            }
        }
        globalListener = listener
        DownloadManager.addGlobalListener(listener)
    }

    // 根据任务匹配列表项并刷新
    private fun updateItemTask(task: DownloadTask) {
        val dir = externalCacheDir?.absolutePath ?: cacheDir.absolutePath
        val idx = list.indexOfFirst {
            it.url == task.url && dir == task.filePath && normalizeName(it.name) == normalizeName(task.fileName)
        }
        if (idx >= 0) {
            list[idx].task = task
            binding.recyclerView.adapter?.notifyItemChanged(idx)
        }
    }

    private fun openDetail(item: DownloadItem) {
        val i = Intent(this, AppDetailActivity::class.java).apply {
            putExtra("url", item.url)
            putExtra("name", item.name)
            putExtra("packageName", item.packageName)
            putExtra("size", item.size)
            putExtra("icon", item.icon)
        }
        startActivity(i)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            binding.recyclerView.post { binding.recyclerView.grid(7).adapter?.notifyDataSetChanged() }
        } else {
            binding.recyclerView.grid(4).adapter?.notifyDataSetChanged()
        }
    }

    override fun onDestroy() {
        globalListener?.let { DownloadManager.removeGlobalListener(it) }
        globalListener = null
        super.onDestroy()
    }
}