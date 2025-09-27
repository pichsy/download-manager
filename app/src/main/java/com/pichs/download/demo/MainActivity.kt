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
import com.pichs.download.core.DownloadPriority
import com.pichs.download.core.FlowDownloadListener
import com.pichs.download.model.DownloadStatus
import com.pichs.download.model.DownloadTask
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import com.pichs.download.demo.databinding.ActivityMainBinding
import com.pichs.download.demo.databinding.ItemGridDownloadBeanBinding
import com.pichs.shanhai.base.base.BaseActivity
import com.pichs.shanhai.base.utils.toast.ToastUtils
import com.pichs.xbase.kotlinext.setItemAnimatorDisable
import com.pichs.xbase.utils.GsonUtils
import java.io.File

class MainActivity : BaseActivity<ActivityMainBinding>() {

    private val list = arrayListOf<DownloadItem>()

    // 旧的监听器已移除，现在使用Flow监听器
    private val flowListener = DownloadManager.flowListener

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
        // 将首页的数据注册到进程内注册表，供其他页面优先读取
        AppMetaRegistry.registerAll(list)

        initRecyclerView()
        // 绑定全局监听（新方式）
        bindFlowListener()
    }

    private fun initListener() {
        binding.ivDownloadManager.setOnClickListener {
            startActivity(Intent(this, DownloadManagerActivity::class.java))
        }

        binding.ivSearch.setOnClickListener { ToastUtils.show("搜索") }
    }

    @SuppressLint("SetTextI18n")
    private fun initRecyclerView() {
        binding.recyclerView.setItemAnimatorDisable()
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
                // 冷启动映射历史任务到列表项
                runCatching {
                    val dirBind = externalCacheDir?.absolutePath ?: cacheDir.absolutePath
                    lifecycleScope.launch {
                        val existingTask = DownloadManager.getAllTasks().firstOrNull {
                            it.url == item.url && it.filePath == dirBind && normalizeName(it.fileName) == normalizeName(item.name)
                        }
                        if (existingTask != null) item.task = existingTask
                    }
                }
                bindButtonUIWithInstalledAndFile(vb, item)
                vb.btnDownload.setOnClickListener { handleClickWithInstalled(item, vb) }
                // 新增：点击封面跳转详情页
                vb.ivCover.setOnClickListener { openDetail(item) }
            }
        }.models = list
    }

    private fun bindButtonUIWithInstalledAndFile(vb: ItemGridDownloadBeanBinding, item: DownloadItem) {
        val task = item.task
        // 直接使用 item 自带的包名+版本信息
        val pkg = item.packageName.orEmpty()
        val storeVC = item.versionCode
        val canOpen = pkg.isNotBlank() && AppUtils.isInstalledAndUpToDate(this, pkg, storeVC)
        if (canOpen) {
            vb.btnDownload.setText("打开")
            vb.btnDownload.isEnabled = true
            return
        }
        // 回退到原绑定逻辑，但在 COMPLETED 时考虑文件健康
        when (task?.status) {
            DownloadStatus.COMPLETED -> {
                val health = task.let { AppUtils.checkFileHealth(it) }
                if (health == AppUtils.FileHealth.OK) {
                    vb.btnDownload.setText("安装")
                    vb.btnDownload.setProgress(100)
                    vb.btnDownload.isEnabled = true
                } else {
                    vb.btnDownload.setText("下载")
                    vb.btnDownload.setProgress(0)
                    vb.btnDownload.isEnabled = true
                }
            }

            DownloadStatus.DOWNLOADING -> {
                vb.btnDownload.setText("${task.progress}%"); vb.btnDownload.setProgress(task.progress); vb.btnDownload.isEnabled = true
            }

            DownloadStatus.PAUSED -> {
                // 暂停时显示“继续”，进度条保留进度
                vb.btnDownload.setText("继续"); vb.btnDownload.setProgress(task.progress); vb.btnDownload.isEnabled = true
            }

            DownloadStatus.WAITING, DownloadStatus.PENDING -> {
                vb.btnDownload.setText("等待中"); vb.btnDownload.isEnabled = true
            }

            DownloadStatus.FAILED -> {
                vb.btnDownload.setText("重试"); vb.btnDownload.isEnabled = true
            }

            else -> {
                vb.btnDownload.setText("下载"); vb.btnDownload.setProgress(0); vb.btnDownload.isEnabled = true
            }
        }
    }

    private fun handleClickWithInstalled(item: DownloadItem, vb: ItemGridDownloadBeanBinding) {
        // 直接使用 item 自带的包名+版本信息
        val pkg = item.packageName.orEmpty()
        val storeVC = item.versionCode
        val canOpen = pkg.isNotBlank() && AppUtils.isInstalledAndUpToDate(this, pkg, storeVC)
        if (canOpen) {
            if (!AppUtils.openApp(this, pkg)) {
                ToastUtils.show("无法打开应用")
            }
            return
        }
        handleClick(item, vb)
    }

    private fun handleClick(item: DownloadItem, vb: ItemGridDownloadBeanBinding) {
        val dir = externalCacheDir?.absolutePath ?: cacheDir.absolutePath
        val name = item.name
        lifecycleScope.launch {
            val existing = DownloadManager.getAllTasks().firstOrNull {
                it.url == item.url && it.filePath == dir && normalizeName(it.fileName) == normalizeName(name)
            }
            val task = item.task ?: existing
            when (task?.status) {
                DownloadStatus.DOWNLOADING -> {
                    DownloadManager.pause(task.id)
                    // 暂停后应显示“继续”，进度条保持当前进度
                    vb.btnDownload.setText("继续")
                    vb.btnDownload.setProgress(task.progress)
                    vb.btnDownload.isEnabled = true
                }

                DownloadStatus.PAUSED -> {
                    DownloadManager.resume(task.id)
                    // 不强制设置文案，等待调度后通过监听刷新
                }

                DownloadStatus.WAITING, DownloadStatus.PENDING -> {
                    // 等待中也可暂停：从队列移出，切换为“继续”
                    DownloadManager.pause(task.id)
                    vb.btnDownload.setText("继续")
                    vb.btnDownload.isEnabled = true
                    // 立刻更新本地模型，避免下一次点击仍按 WAITING 分支
                    item.task = task.copy(status = DownloadStatus.PAUSED, speed = 0L, updateTime = System.currentTimeMillis())
                }

                DownloadStatus.COMPLETED -> {
                    val file = File(task.filePath, task.fileName)
                    if (file.exists()) {
                        openApk(task)
                    } else {
                        // 文件缺失：清理任务并直接重启下载
                        DownloadManager.deleteTask(task.id, deleteFile = false)
                        item.task = null
                        startDownload(item, vb)
                    }
                }

                DownloadStatus.FAILED -> startDownload(item, vb)
                else -> if (existing == null) {
                    startDownload(item, vb)
                } else {
                    // 存在旧任务但状态不适配时的处理：
                    when (existing.status) {
                        DownloadStatus.CANCELLED, DownloadStatus.FAILED -> {
                            startDownload(item, vb)
                        }

                        DownloadStatus.COMPLETED -> {
                            val f = File(existing.filePath, existing.fileName)
                            if (!f.exists()) {
                                DownloadManager.deleteTask(existing.id, deleteFile = false)
                                item.task = null
                                startDownload(item, vb)
                            } else {
                                bindButtonUI(vb, existing)
                            }
                        }

                        DownloadStatus.PAUSED -> {
                            DownloadManager.resume(existing.id)
                        }

                        else -> bindButtonUI(vb, existing)
                    }
                }
            }
        }
    }

    private fun startDownload(item: DownloadItem, vb: ItemGridDownloadBeanBinding) {
        val dir = externalCacheDir?.absolutePath ?: cacheDir.absolutePath
        // 将图标/名称/包名/版本写入 extras(JSON)
        val extrasJson = com.pichs.xbase.utils.GsonUtils.toJson(
            mapOf(
                "name" to (item.name ?: ""),
                "packageName" to (item.packageName ?: ""),
                "versionCode" to (item.versionCode ?: 0L),
                "icon" to (item.icon ?: "")
            )
        )
        // 使用新的优先级API，用户主动下载使用HIGH优先级
        val task = DownloadManager.downloadWithPriority(item.url, DownloadPriority.HIGH)
            .path(dir)
            .fileName(item.name)
            .start()
        item.task = task
        bindButtonUIWithInstalledAndFile(vb, item)
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
                vb.btnDownload.setText("继续")
                vb.btnDownload.setProgress(task.progress)
                vb.btnDownload.isEnabled = true
            }

            DownloadStatus.WAITING, DownloadStatus.PENDING -> {
                vb.btnDownload.setText("等待中"); vb.btnDownload.isEnabled = true
            }

            DownloadStatus.COMPLETED -> {
                val health = task.let { AppUtils.checkFileHealth(it) }
                if (health == AppUtils.FileHealth.OK) {
                    vb.btnDownload.setText("安装")
                    vb.btnDownload.setProgress(100)
                    vb.btnDownload.isEnabled = true
                } else {
                    // 文件缺失/损坏：回落为下载态
                    vb.btnDownload.setText("下载")
                    vb.btnDownload.setProgress(0)
                    vb.btnDownload.isEnabled = true
                }
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

    // 新增：绑定Flow监听，保持列表项与任务状态同步
    private fun bindFlowListener() {
        flowListener.bindToLifecycle(
            lifecycleOwner = this,
            onTaskProgress = { task, progress, speed ->
                if (isDestroyed) return@bindToLifecycle
                // 更新任务进度和速度
                updateItemTaskWithProgress(task, progress, speed)
            },
            onTaskComplete = { task, file ->
                if (isDestroyed) return@bindToLifecycle
                updateItemTask(task)
            },
            onTaskError = { task, error ->
                if (isDestroyed) return@bindToLifecycle
                updateItemTask(task)
            },
            onTaskPaused = { task ->
                if (isDestroyed) return@bindToLifecycle
                updateItemTask(task)
            },
            onTaskResumed = { task ->
                if (isDestroyed) return@bindToLifecycle
                updateItemTask(task)
            },
            onTaskCancelled = { task ->
                if (isDestroyed) return@bindToLifecycle
                updateItemTask(task)
            }
        )
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

    // 专门处理进度更新的方法（添加防抖机制）
    private var lastProgressUpdateTime = 0L
    private val progressUpdateInterval = 300L // 300ms防抖间隔
    
    private fun updateItemTaskWithProgress(task: DownloadTask, progress: Int, speed: Long) {
        val now = System.currentTimeMillis()
        if (now - lastProgressUpdateTime < progressUpdateInterval) {
            return // 防抖：跳过过于频繁的更新
        }
        lastProgressUpdateTime = now
        
        val dir = externalCacheDir?.absolutePath ?: cacheDir.absolutePath
        val idx = list.indexOfFirst {
            it.url == task.url && dir == task.filePath && normalizeName(it.name) == normalizeName(task.fileName)
        }
        if (idx >= 0) {
            // 更新任务数据
            list[idx].task = task
            // 使用post延迟更新UI，避免阻塞主线程
            binding.recyclerView.post {
                binding.recyclerView.adapter?.notifyItemChanged(idx)
            }
        }
    }

    // 更新ViewHolder中的进度显示
    private fun updateProgressInViewHolder(
        viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder,
        progress: Int,
        speed: Long,
        status: com.pichs.download.model.DownloadStatus
    ) {
        // 这里需要根据实际的ViewHolder结构来更新
        // 由于使用了BRV库，我们需要通过其他方式来更新进度
        // 暂时使用notifyItemChanged来触发重新绑定
        binding.recyclerView.adapter?.notifyItemChanged(viewHolder.adapterPosition)
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
        // Flow监听器会自动管理生命周期，无需手动移除
        super.onDestroy()
    }
}