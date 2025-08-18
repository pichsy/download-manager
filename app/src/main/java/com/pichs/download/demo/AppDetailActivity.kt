package com.pichs.download.demo

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.bumptech.glide.Glide
import com.pichs.download.core.DownloadManager
import com.pichs.download.model.DownloadStatus
import com.pichs.download.model.DownloadTask
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
    private var globalListener: com.pichs.download.listener.DownloadListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        url = intent.getStringExtra("url") ?: ""
        name = intent.getStringExtra("name") ?: ""
        packageNameStr = intent.getStringExtra("packageName") ?: ""
        size = intent.getLongExtra("size", 0L)
        icon = intent.getStringExtra("icon")

        // 若首页已注册完整数据，则以注册表为准，避免不一致
        AppMetaRegistry.getByName(name)?.let { it ->
            if (url.isBlank()) url = it.url
            if (packageNameStr.isBlank()) packageNameStr = it.packageName.orEmpty()
            if (size <= 0) size = it.size ?: 0L
            if (icon.isNullOrBlank()) icon = it.icon
        }

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
        // 尝试找到现有任务
        val dir = externalCacheDir?.absolutePath ?: cacheDir.absolutePath
        task = DownloadManager.getAllTasks().firstOrNull {
            it.url == url && it.filePath == dir && normalizeName(it.fileName) == normalizeName(name)
        }
        bindButtonUI(task)
    }

    private fun onClickDownload() {
        val t = task
        val dir = externalCacheDir?.absolutePath ?: cacheDir.absolutePath
        if (t == null) {
            // 新建下载
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
                .to(dir, name)
                .meta(packageNameStr, storeVC)
                .extras(extrasJson)
                .onProgress { progress, _ ->
                    // 对齐首页：下载中按钮显示百分比
                    binding.btnDownload.setProgress(progress)
                    binding.btnDownload.setText("${progress}%")
                }
                .onComplete { file ->
                    binding.btnDownload.setProgress(100)
                    binding.btnDownload.setText("安装")
                    openApkFile(file)
                }
                .onError {
                    binding.btnDownload.setText("重试")
                }
                .start()
            bindButtonUI(task)
            return
        }
        when (t.status) {
            DownloadStatus.DOWNLOADING -> DownloadManager.pause(t.id)
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

    private fun startOrRestartDownload(dir: String) {
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
            .to(dir, name)
            .meta(packageNameStr, storeVC)
            .extras(extrasJson)
            .onProgress { progress, _ ->
                binding.btnDownload.setProgress(progress)
                binding.btnDownload.setText("${progress}%")
            }
            .onComplete { file ->
                binding.btnDownload.setProgress(100)
                binding.btnDownload.setText("安装")
                openApkFile(file)
            }
            .onError {
                binding.btnDownload.setText("重试")
            }
            .start()
        bindButtonUI(task)
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
        val listener = object : com.pichs.download.listener.DownloadListener {
            override fun onTaskProgress(task: DownloadTask, progress: Int, speed: Long) {
                if (task.id == this@AppDetailActivity.task?.id) {
                    runOnUiThread {
                        // 同步本地引用，确保后续点击使用最新状态
                        this@AppDetailActivity.task = task
                        bindButtonUI(task)
                    }
                }
            }
            override fun onTaskComplete(task: DownloadTask, file: File) {
                if (task.id == this@AppDetailActivity.task?.id) {
                    runOnUiThread {
                        this@AppDetailActivity.task = task
                        bindButtonUI(task)
                    }
                }
            }
            override fun onTaskError(task: DownloadTask, error: Throwable) {
                if (task.id == this@AppDetailActivity.task?.id) {
                    runOnUiThread {
                        this@AppDetailActivity.task = task
                        bindButtonUI(task)
                    }
                }
            }
        }
        globalListener = listener
        DownloadManager.addGlobalListener(listener)
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
        globalListener?.let { DownloadManager.removeGlobalListener(it) }
        globalListener = null
        super.onDestroy()
    }
}
