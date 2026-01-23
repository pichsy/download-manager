package com.pichs.download.demo

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.bumptech.glide.Glide
import com.pichs.download.core.DownloadManager
import com.pichs.download.core.FlowDownloadListener
import com.pichs.download.model.DownloadStatus
import com.pichs.download.model.DownloadTask
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import com.pichs.download.demo.databinding.ActivityAppDetailBinding
import java.io.File

class AppDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppDetailBinding

    private var url: String = ""
    private var name: String = ""
    private var packageNameStr: String = ""
    private var size: Long = 0L
    private var icon: String? = null
    private fun canOpenInstalled(): Boolean {
        val pkg = packageNameStr
        if (pkg.isBlank()) return false
        val storeVC = CatalogRepository.getStoreVersionCode(this, pkg) ?: 0L
        return AppUtils.isInstalledAndUpToDate(this, pkg, storeVC)
    }

    private var task: DownloadTask? = null
    private val flowListener = DownloadManager.flowListener

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        url = intent.getStringExtra("url") ?: ""
        name = intent.getStringExtra("name") ?: ""
        packageNameStr = intent.getStringExtra("packageName") ?: ""
        size = intent.getLongExtra("size", 0L)
        icon = intent.getStringExtra("icon")

        initUI()
        initDownloadState()
        bindListeners()
    }

    private fun initUI() {
        binding.tvTitle.text = name
        binding.tvPackage.text = packageNameStr
        binding.tvSize.text = formatSize(size)
        if (!icon.isNullOrEmpty()) Glide.with(binding.ivIcon).load(icon).into(binding.ivIcon)
        binding.btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.btnDownload.setOnClickListener { onClickDownload() }
    }

    private fun initDownloadState() {
        // 尝试找到现有任务（通过 URL 匹配）
        lifecycleScope.launch {
            task = DownloadManager.getTaskByUrl(url)
            bindButtonUI(task)
        }
    }

    private fun onClickDownload() {
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
                // 立即把本地任务置为 PAUSED，保证下次点击能 resume
                this.task = t.copy(status = DownloadStatus.PAUSED, speed = 0L, updateTime = System.currentTimeMillis())
                bindButtonUI(this.task)
            }
            DownloadStatus.PAUSED -> {
                DownloadManager.resume(t.id)
            }
            DownloadStatus.PENDING, DownloadStatus.WAITING -> {
                // 等待中可暂停：从队列移出
                DownloadManager.pause(t.id)
                // 立即把本地任务置为 PAUSED，保证下次点击能 resume
                this.task = t.copy(status = DownloadStatus.PAUSED, speed = 0L, updateTime = System.currentTimeMillis())
                bindButtonUI(this.task)
            }
            DownloadStatus.COMPLETED -> {
                // 对齐首页：优先打开已安装；否则检查文件健康，健康则安装，否则触发重新下载
                val pkg = packageNameStr
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
                    // 重新下载
                    startOrRestartDownload(dir)
                }
            }
            DownloadStatus.FAILED -> {
                // 失败重试
                DownloadManager.resume(t.id)
            }
            else -> {}
        }
    }

    /**
     * 使用预检查流程请求下载
     */
    private fun requestDownloadWithPreCheck(dir: String) {
        lifecycleScope.launch {
            val result = DownloadManager.checkBeforeCreate(size)
            
            when (result) {
                is com.pichs.download.model.CheckBeforeResult.Allow -> {
                    doStartDownload(dir)
                }
                is com.pichs.download.model.CheckBeforeResult.NoNetwork -> {
                    showNoNetworkDialog(dir)
                }
                is com.pichs.download.model.CheckBeforeResult.WifiOnly -> {
                    showWifiOnlyDialog(dir)
                }
                is com.pichs.download.model.CheckBeforeResult.NeedConfirmation -> {
                    showCellularConfirmDialog(dir, result.estimatedSize)
                }
                is com.pichs.download.model.CheckBeforeResult.UserControlled -> {
                    if (CellularThresholdManager.shouldPrompt(result.estimatedSize)) {
                        showCellularConfirmDialog(dir, result.estimatedSize)
                    } else {
                        doStartDownload(dir, cellularConfirmed = true)
                    }
                }
            }
        }
    }
    
    private fun showNoNetworkDialog(dir: String) {
        CellularConfirmViewModel.pendingAction = {
            doStartDownloadAndPause(dir, com.pichs.download.model.PauseReason.NETWORK_ERROR)
        }
        CellularConfirmDialog.show( size, 1, CellularConfirmDialog.MODE_NO_NETWORK)

    }
    
    private fun showWifiOnlyDialog(dir: String) {
        CellularConfirmViewModel.pendingAction = {
            doStartDownloadAndPause(dir, com.pichs.download.model.PauseReason.WIFI_UNAVAILABLE)
        }
        CellularConfirmDialog.show(size, 1, CellularConfirmDialog.MODE_WIFI_ONLY)
    }
    
    private fun showCellularConfirmDialog(dir: String, totalSize: Long) {
        // 创建临时 DownloadItem 用于弹窗
        val item = DownloadItem(
            name = name,
            url = url,
            packageName = packageNameStr,
            versionCode = CatalogRepository.getStoreVersionCode(this, packageNameStr),
            icon = icon ?: "",
            size = size
        )
        CellularConfirmViewModel.pendingAction = {
            doStartDownload(dir, cellularConfirmed = true)
        }
        CellularConfirmDialog.show( totalSize, 1)
    }

    private fun doStartDownload(dir: String, cellularConfirmed: Boolean = false) {
        val storeVC = CatalogRepository.getStoreVersionCode(this, packageNameStr) ?: 0L
        val extrasJson = com.pichs.xbase.utils.GsonUtils.toJson(
            mapOf(
                "name" to (name),
                "packageName" to (packageNameStr),
                "versionCode" to (storeVC),
                "icon" to (icon ?: ""),
                "size" to (size)
            )
        )
        task = DownloadManager.download(url)
            .path(dir)
            .fileName(name)
            .estimatedSize(size)
            .extras(extrasJson)
            .cellularConfirmed(cellularConfirmed)
            .start()
        bindButtonUI(task)
    }
    
    private fun doStartDownloadAndPause(dir: String, pauseReason: com.pichs.download.model.PauseReason) {
        val storeVC = CatalogRepository.getStoreVersionCode(this, packageNameStr) ?: 0L
        val extrasJson = com.pichs.xbase.utils.GsonUtils.toJson(
            mapOf(
                "name" to (name),
                "packageName" to (packageNameStr),
                "versionCode" to (storeVC),
                "icon" to (icon ?: ""),
                "size" to (size)
            )
        )
        val newTask = DownloadManager.download(url)
            .path(dir)
            .fileName(name)
            .estimatedSize(size)
            .extras(extrasJson)
            .start()
        
        // 立即暂停，设置暂停原因
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
