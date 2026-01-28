package com.pichs.download.demo

import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.bumptech.glide.Glide
import com.pichs.download.core.DownloadManager
import com.pichs.download.model.DownloadStatus
import com.pichs.download.model.DownloadTask
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import com.pichs.download.demo.databinding.ActivityAppDetailBinding
import com.pichs.shanhai.base.base.BaseActivity
import com.pichs.shanhai.base.utils.LogUtils
import com.pichs.xbase.kotlinext.dp
import java.io.File

class AppDetailActivity : BaseActivity<ActivityAppDetailBinding>() {

    private var appInfo: DownloadItem? = null

    override fun afterOnCreate() {
        appInfo = intent.getParcelableExtra("app_info")
        if (appInfo == null) {
            finish()
            return
        }

        initUI(appInfo!!)
        initDownloadState()
        bindListeners()
    }

    private fun initUI(item: DownloadItem) {
        binding.tvTitle.text = item.name
        binding.tvPackage.text = item.packageName
        if (!item.icon.isNullOrEmpty()) Glide.with(binding.ivIcon).load(item.icon).into(binding.ivIcon)

        // Info Grid
        binding.tvInfo1.text = item.version
        binding.tvInfo2.text = formatSize(item.sizeBytes)
        binding.tvInfo3.text = item.update_time

        // Tags
        val tagList = mutableListOf<String>()
        item.categories?.let { tagList.addAll(it) }
        item.tags?.let { tagList.addAll(it) }
        binding.tvTags.text = tagList.joinToString("  ")

        // Desc
        binding.tvDesc.text = item.description.ifBlank { "暂无介绍" }

        // Developer
        val sb = StringBuilder()
        if (item.developer.isNotBlank()) sb.append("开发者：").append(item.developer).append("\n")
        if (item.registration_no.isNotBlank()) sb.append("备案号：").append(item.registration_no)
        binding.tvDeveloperInfo.text = sb.toString()

        // Screenshots
        if (item.screenshots.isNullOrEmpty()) {
            binding.rvScreenshots.visibility = android.view.View.GONE
        } else {
            binding.rvScreenshots.visibility = android.view.View.VISIBLE
            binding.rvScreenshots.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this, androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false)
            binding.rvScreenshots.adapter = ScreenshotAdapter(item.screenshots!!)
            binding.rvScreenshots.addItemDecoration(object : androidx.recyclerview.widget.RecyclerView.ItemDecoration() {
                override fun getItemOffsets(outRect: android.graphics.Rect, view: android.view.View, parent: androidx.recyclerview.widget.RecyclerView, state: androidx.recyclerview.widget.RecyclerView.State) {
                    outRect.right = 8.dp
                }
            })
        }

        binding.ivBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.btnDownload.setOnClickListener { onClickDownload() }
    }

    class ScreenshotAdapter(private val list: List<String>) : androidx.recyclerview.widget.RecyclerView.Adapter<ScreenshotAdapter.VH>() {
        class VH(v: android.view.View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(v) {
            val iv: android.widget.ImageView = v.findViewById(R.id.iv_screenshot)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
            val v = android.view.LayoutInflater.from(parent.context).inflate(R.layout.item_screenshot, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            Glide.with(holder.iv).load(list[position]).into(holder.iv)
        }

        override fun getItemCount(): Int = list.size
    }

    private fun canOpenInstalled(): Boolean {
        val pkg = appInfo?.packageName ?: return false
        val storeVC = CatalogRepository.getStoreVersionCode(this, pkg) ?: 0L
        return AppUtils.isInstalledAndUpToDate(this, pkg, storeVC)
    }

    private var task: DownloadTask? = null
    private val flowListener = DownloadManager.flowListener

    private fun initDownloadState() {
        val url = appInfo?.url ?: return
        lifecycleScope.launch {
            task = DownloadManager.getTaskByUrl(url)
            bindButtonUI(task)
        }
    }

    private fun onClickDownload() {
        val info = appInfo ?: return
        val t = task
        val dir = getExternalFilesDir(null)?.absolutePath ?: filesDir.absolutePath
        if (t == null) {
            // 新建下载 - 使用预检查
            requestDownloadWithPreCheck(dir)
            return
        }
        when (t.status) {
            DownloadStatus.DOWNLOADING -> {
                DownloadManager.pause(t.id)
                this.task = t.copy(status = DownloadStatus.PAUSED, speed = 0L, updateTime = System.currentTimeMillis())
                bindButtonUI(this.task)
            }
            DownloadStatus.PAUSED -> DownloadManager.resume(t.id)
            DownloadStatus.PENDING, DownloadStatus.WAITING -> {
                DownloadManager.pause(t.id)
                this.task = t.copy(status = DownloadStatus.PAUSED, speed = 0L, updateTime = System.currentTimeMillis())
                bindButtonUI(this.task)
            }
            DownloadStatus.COMPLETED -> {
                val pkg = info.packageName
                val storeVC = CatalogRepository.getStoreVersionCode(this, pkg) ?: 0L
                if (pkg.isNotBlank() && AppUtils.isInstalledAndUpToDate(this, pkg, storeVC)) {
                    if (!AppUtils.openApp(this, pkg)) {
                        android.widget.Toast.makeText(this, "无法打开应用", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    return
                }
                val health = AppUtils.checkFileHealth(t)
                if (health == AppUtils.FileHealth.OK) {
                    openApk(t)
                } else {
                    startOrRestartDownload(dir)
                }
            }
            DownloadStatus.FAILED -> DownloadManager.resume(t.id)
            else -> {}
        }
    }

    private fun requestDownloadWithPreCheck(dir: String) {
        val info = appInfo ?: return
        lifecycleScope.launch {
            val result = DownloadManager.checkBeforeCreate(info.sizeBytes)
            when (result) {
                is com.pichs.download.model.CheckBeforeResult.Allow -> doStartDownload(dir)
                is com.pichs.download.model.CheckBeforeResult.NoNetwork -> showNoNetworkDialog(dir)
                is com.pichs.download.model.CheckBeforeResult.WifiOnly -> showWifiOnlyDialog(dir)
                is com.pichs.download.model.CheckBeforeResult.NeedConfirmation -> showCellularConfirmDialog(dir, result.estimatedSize)
                else -> {}
            }
        }
    }

    private fun showNoNetworkDialog(dir: String) {
        val info = appInfo ?: return
        CellularConfirmDialog.show(
            totalSize = info.sizeBytes,
            taskCount = 1,
            mode = CellularConfirmDialog.MODE_NO_NETWORK,
            onConfirm = { doStartDownloadAndPause(dir, com.pichs.download.model.PauseReason.NETWORK_ERROR) },
            onCancel = {}
        )
    }

    private fun showWifiOnlyDialog(dir: String) {
        val info = appInfo ?: return
        CellularConfirmDialog.show(
            totalSize = info.sizeBytes,
            taskCount = 1,
            mode = CellularConfirmDialog.MODE_WIFI_ONLY,
            onConfirm = { doStartDownloadAndPause(dir, com.pichs.download.model.PauseReason.WIFI_UNAVAILABLE) },
            onCancel = {}
        )
    }

    private fun showCellularConfirmDialog(dir: String, totalSize: Long) {
        CellularConfirmDialog.show(
            totalSize = totalSize,
            taskCount = 1,
            mode = CellularConfirmDialog.MODE_CELLULAR,
            onConfirm = { doStartDownload(dir, cellularConfirmed = true) },
            onCancel = {}
        )
    }

    private fun doStartDownload(dir: String, cellularConfirmed: Boolean = false) {
        val info = appInfo ?: return
        lifecycleScope.launch {
            val storeVC = CatalogRepository.getStoreVersionCode(this@AppDetailActivity, info.packageName) ?: 0L
            val extrasJson = ExtraMeta(
                name = info.name,
                packageName = info.packageName,
                versionCode = storeVC,
                icon = info.icon ?: "",
                size = info.sizeBytes,
                install_count = info.install_count,
                description = info.description,
                update_time = info.update_time,
                version_name = info.version,
                developer = info.developer,
                registration_no = info.registration_no,
                categories = info.categories,
                tags = info.tags,
                screenshots = info.screenshots
            ).toJson()

            // 优先使用传递过来的 priority，如果没有可以默认
            val priority = when (info.priority) {
                com.pichs.download.core.DownloadPriority.URGENT.value -> com.pichs.download.core.DownloadPriority.URGENT.value
                com.pichs.download.core.DownloadPriority.HIGH.value -> com.pichs.download.core.DownloadPriority.HIGH.value
                com.pichs.download.core.DownloadPriority.LOW.value -> com.pichs.download.core.DownloadPriority.LOW.value
                else -> com.pichs.download.core.DownloadPriority.NORMAL.value
            }

            task = DownloadManager.download(info.url)
                .path(dir)
                .fileName(info.name)
                .estimatedSize(info.sizeBytes)
                .extras(extrasJson)
                .priority(priority)
                .cellularConfirmed(cellularConfirmed)
                .start()

            task = DownloadManager.getTask(task?.id ?: "") ?: task
            bindButtonUI(task)
        }
    }

    private fun doStartDownloadAndPause(dir: String, pauseReason: com.pichs.download.model.PauseReason) {
        val info = appInfo ?: return
        val storeVC = CatalogRepository.getStoreVersionCode(this, info.packageName) ?: 0L
        val extrasJson = ExtraMeta(
            name = info.name,
            packageName = info.packageName,
            versionCode = storeVC,
            icon = info.icon ?: "",
            size = info.sizeBytes,
            install_count = info.install_count,
            description = info.description,
            update_time = info.update_time,
            version_name = info.version,
            developer = info.developer,
            registration_no = info.registration_no,
            categories = info.categories,
            tags = info.tags,
            screenshots = info.screenshots
        ).toJson()

        // 同样处理 Priority
         val priority = when (info.priority) {
            com.pichs.download.core.DownloadPriority.URGENT.value -> com.pichs.download.core.DownloadPriority.URGENT.value
            com.pichs.download.core.DownloadPriority.HIGH.value -> com.pichs.download.core.DownloadPriority.HIGH.value
            com.pichs.download.core.DownloadPriority.LOW.value -> com.pichs.download.core.DownloadPriority.LOW.value
            else -> com.pichs.download.core.DownloadPriority.NORMAL.value
        }

        val newTask = DownloadManager.download(info.url)
            .path(dir)
            .fileName(info.name)
            .estimatedSize(info.sizeBytes)
            .extras(extrasJson)
            .priority(priority)
            .start()

        DownloadManager.pauseTask(newTask.id, pauseReason)
        task = newTask.copy(status = DownloadStatus.PAUSED, pauseReason = pauseReason)
        bindButtonUI(task)

        val msg = when (pauseReason) {
            com.pichs.download.model.PauseReason.NETWORK_ERROR -> "已加入下载队列，等待网络连接"
            com.pichs.download.model.PauseReason.WIFI_UNAVAILABLE -> "已加入下载队列，等待WiFi连接"
            else -> "已加入下载队列"
        }
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun startOrRestartDownload(dir: String) {
        requestDownloadWithPreCheck(dir)
    }

    private fun bindButtonUI(task: DownloadTask?) {
        // 对齐首页逻辑：先判断是否已安装可打开
        if (canOpenInstalled()) {
            binding.btnDownload.setText("打开")
            binding.btnDownload.isEnabled = true
            return
        }

        when (task?.status) {
            DownloadStatus.DOWNLOADING -> {
                binding.btnDownload.setText("${task.progress}%")
                binding.btnDownload.setProgress(task.progress)
                binding.btnDownload.isEnabled = true
            }

            DownloadStatus.PAUSED -> {
                binding.btnDownload.setText("继续")
                binding.btnDownload.setProgress(task.progress)
                binding.btnDownload.isEnabled = true
            }

            DownloadStatus.WAITING, DownloadStatus.PENDING -> {
                binding.btnDownload.setText("等待中")
                binding.btnDownload.isEnabled = true
            }

            DownloadStatus.COMPLETED -> {
                val health = task.let { AppUtils.checkFileHealth(it) }
                if (health == AppUtils.FileHealth.OK) {
                    binding.btnDownload.setText("安装")
                    binding.btnDownload.setProgress(100)
                    binding.btnDownload.isEnabled = true
                } else {
                    binding.btnDownload.setText("下载")
                    binding.btnDownload.setProgress(0)
                    binding.btnDownload.isEnabled = true
                }
            }

            DownloadStatus.FAILED -> {
                binding.btnDownload.setText("重试")
                binding.btnDownload.isEnabled = true
            }

            else -> {
                binding.btnDownload.setText("下载")
                binding.btnDownload.setProgress(0)
                binding.btnDownload.isEnabled = true
            }
        }
    }

    private fun bindListeners() {
        flowListener.bindToLifecycle(
            lifecycleOwner = this,
            onTaskProgress = { task, progress, speed ->
                if (task.id == this@AppDetailActivity.task?.id) {
                    // 同步本地引用，确保后续点击使用最新状态
                    this@AppDetailActivity.task = task
                    bindButtonUI(task)
                }
            },
            onTaskComplete = { task, file ->
                if (task.id == this@AppDetailActivity.task?.id) {
                    this@AppDetailActivity.task = task
                    bindButtonUI(task)
                }
            },
            onTaskError = { task, error ->
                if (task.id == this@AppDetailActivity.task?.id) {
                    this@AppDetailActivity.task = task
                    bindButtonUI(task)
                }
            },
            onTaskPaused = { task ->
                if (task.id == this@AppDetailActivity.task?.id) {
                    this@AppDetailActivity.task = task
                    bindButtonUI(task)
                }
            },
            onTaskResumed = { task ->
                if (task.id == this@AppDetailActivity.task?.id) {
                    this@AppDetailActivity.task = task
                    bindButtonUI(task)
                }
            }
        )
    }

    private fun openApk(task: DownloadTask) {
        val file = File(task.filePath, task.fileName)
        openApkFile(file)
    }

    private fun openApkFile(file: File) {
        if (!file.exists()) return
        val pkg = appInfo?.packageName ?: packageName
        val uri: Uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setDataAndType(uri, "application/vnd.android.package-archive")
        }
        startActivity(intent)
    }

    private fun normalizeName(n: String): String = n.substringBeforeLast('.').lowercase()

    private fun formatSize(size: Long): String {
        if (size <= 0) return "--"
        val kb = 1024.0
        val mb = kb * 1024
        val gb = mb * 1024
        return when {
            size >= gb -> String.format("%.2f GB", size / gb)
            size >= mb -> String.format("%.2f MB", size / mb)
            size >= kb -> String.format("%.2f KB", size / kb)
            else -> "${size} B"
        }
    }

    override fun onDestroy() {
        // Flow监听器会自动管理生命周期，无需手动移除
        super.onDestroy()
    }
}
