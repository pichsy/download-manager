package com.pichs.download.demo

import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.pichs.download.core.DownloadManager
import com.pichs.download.core.DownloadPriority
import com.pichs.download.core.FlowDownloadListener
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import com.pichs.download.demo.databinding.ActivityDownloadManagerBinding
import com.pichs.download.model.DownloadStatus
import com.pichs.download.model.DownloadTask
import com.pichs.shanhai.base.base.BaseActivity
import com.pichs.xbase.kotlinext.setItemAnimatorDisable

class DownloadManagerActivity : BaseActivity<ActivityDownloadManagerBinding>() {

    private val downloading = mutableListOf<DownloadTask>()
    private val completed = mutableListOf<DownloadTask>()
    private lateinit var downloadingAdapter: SimpleTaskAdapter
    private lateinit var completedAdapter: SimpleTaskAdapter

    // 旧的监听器已移除，现在使用Flow监听器
    private val flowListener = DownloadManager.flowListener

    override fun afterOnCreate() {
        setupRecycler()
        refreshLists()
        bindListeners()
    }

    private fun setupRecycler() {
        downloadingAdapter = SimpleTaskAdapter(onAction = { task ->
            when (task.status) {
                DownloadStatus.DOWNLOADING -> DownloadManager.pause(task.id)
                DownloadStatus.PAUSED -> DownloadManager.resume(task.id)
                DownloadStatus.PENDING, DownloadStatus.WAITING -> {
                    DownloadManager.pause(task.id)
                    // 同步本地一份，立刻显示“继续”
                    val paused = task.copy(status = DownloadStatus.PAUSED, speed = 0L, updateTime = System.currentTimeMillis())
                    updateSingle(paused)
                }
                DownloadStatus.FAILED -> DownloadManager.resume(task.id)
                else -> {}
            }
        })
        binding.recyclerView.setItemAnimatorDisable()

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = downloadingAdapter

        completedAdapter = SimpleTaskAdapter(onAction = { task ->
            // 优先用任务上的显式元数据；退化到 catalog
            val pkg = task.packageName
                ?: AppUtils.getPackageNameForTask(this, task)
                ?: ""
            val storeVC = task.storeVersionCode ?: AppUtils.getStoreVersionCode(this, pkg)
            if (pkg.isNotBlank() && AppUtils.isInstalledAndUpToDate(this, pkg, storeVC)) {
                if (!AppUtils.openApp(this, pkg)) {
                    android.widget.Toast.makeText(this, "无法打开应用", android.widget.Toast.LENGTH_SHORT).show()
                }
                return@SimpleTaskAdapter
            }
            val health = AppUtils.checkFileHealth(task)
            if (health == AppUtils.FileHealth.OK) {
                openApk(task)
            } else {
                // 不显示安装按钮，仅展示红字提示并保留 X（由 ViewHolder 负责）
            }
        })
        binding.recyclerViewDownloaded.setItemAnimatorDisable()
        binding.recyclerViewDownloaded.layoutManager = LinearLayoutManager(this)
        binding.recyclerViewDownloaded.adapter = completedAdapter
    }

    private fun refreshLists() {
        lifecycleScope.launch {
            val all = DownloadManager.getAllTasks()
            .sortedByDescending { it.updateTime }
            .distinctBy { it.id }

            downloading.clear()
            completed.clear()

            all.forEach { task ->
                when (task.status) {
                    DownloadStatus.COMPLETED -> completed.add(task)
                    DownloadStatus.DOWNLOADING, DownloadStatus.PAUSED, DownloadStatus.PENDING, DownloadStatus.WAITING -> downloading.add(task)
                    else -> {}
                }
            }

            // 按优先级排序：紧急任务在前
            downloading.sortWith(compareByDescending<DownloadTask> { it.priority }.thenByDescending { it.createTime })
            completed.sortByDescending { it.updateTime }

            downloadingAdapter.submit(downloading)
            completedAdapter.submit(completed)
        }
    }

    private fun updateSingle(task: DownloadTask) {
        val inDownloading = downloading.any { it.id == task.id }
        val inCompleted = completed.any { it.id == task.id }
    val shouldBeInDownloading = task.status == DownloadStatus.DOWNLOADING || task.status == DownloadStatus.PAUSED || task.status == DownloadStatus.PENDING || task.status == DownloadStatus.WAITING
        val shouldBeInCompleted = task.status == DownloadStatus.COMPLETED
        val crossGroup = (inDownloading && shouldBeInCompleted) || (inCompleted && shouldBeInDownloading)
        if (crossGroup || (!inDownloading && !inCompleted)) {
            refreshLists()
            return
        }

        if (inDownloading) {
            val idx = downloading.indexOfFirst { it.id == task.id }
            if (idx >= 0) {
                downloading[idx] = task
                downloadingAdapter.updateItem(task)
            }
        } else if (inCompleted) {
            val idx = completed.indexOfFirst { it.id == task.id }
            if (idx >= 0) {
                completed[idx] = task
                completedAdapter.updateItem(task)
            }
        }
    }

    // 专门处理进度更新的方法
    private fun updateSingleWithProgress(task: DownloadTask, progress: Int, speed: Long) {
        val inDownloading = downloading.any { it.id == task.id }
        val inCompleted = completed.any { it.id == task.id }
        val shouldBeInDownloading = task.status == DownloadStatus.DOWNLOADING || task.status == DownloadStatus.PAUSED || task.status == DownloadStatus.PENDING || task.status == DownloadStatus.WAITING
        val shouldBeInCompleted = task.status == DownloadStatus.COMPLETED
        val crossGroup = (inDownloading && shouldBeInCompleted) || (inCompleted && shouldBeInDownloading)
        
        if (crossGroup || (!inDownloading && !inCompleted)) {
            refreshLists()
            return
        }

        if (inDownloading) {
            val idx = downloading.indexOfFirst { it.id == task.id }
            if (idx >= 0) {
                downloading[idx] = task
                // 立即更新进度显示
                downloadingAdapter.updateItemWithProgress(task, progress, speed)
            }
        } else if (inCompleted) {
            val idx = completed.indexOfFirst { it.id == task.id }
            if (idx >= 0) {
                completed[idx] = task
                completedAdapter.updateItem(task)
            }
        }
    }

    private fun bindListeners() {
        flowListener.bindToLifecycle(
            lifecycleOwner = this,
            onTaskProgress = { task, progress, speed ->
                if (isDestroyed) return@bindToLifecycle
                // 更新任务进度和速度
                updateSingleWithProgress(task, progress, speed)
            },
            onTaskComplete = { task, file ->
                if (isDestroyed) return@bindToLifecycle
                refreshLists()
            },
            onTaskError = { task, error ->
                if (isDestroyed) return@bindToLifecycle
                updateSingle(task)
            },
            onTaskPaused = { task ->
                if (isDestroyed) return@bindToLifecycle
                updateSingle(task)
            },
            onTaskResumed = { task ->
                if (isDestroyed) return@bindToLifecycle
                updateSingle(task)
            },
            onTaskCancelled = { task ->
                if (isDestroyed) return@bindToLifecycle
                updateSingle(task)
            }
        )
    }

    override fun onDestroy() {
        // Flow监听器会自动管理生命周期，无需手动移除
        super.onDestroy()
    }

    private fun openApk(task: DownloadTask) {
        val file = java.io.File(task.filePath, task.fileName)
        if (!file.exists()) return
        val uri: android.net.Uri = androidx.core.content.FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setDataAndType(uri, "application/vnd.android.package-archive")
        }
        startActivity(intent)
    }
}

private class SimpleTaskAdapter(
    val onAction: (DownloadTask) -> Unit
) : androidx.recyclerview.widget.RecyclerView.Adapter<SimpleTaskVH>() {

    private val data = mutableListOf<DownloadTask>()

    fun submit(list: List<DownloadTask>) {
        data.clear()
        data.addAll(list)
        notifyDataSetChanged()
    }

    fun updateItem(task: DownloadTask) {
        val idx = data.indexOfFirst { it.id == task.id }
        if (idx >= 0) {
            data[idx] = task
            notifyItemChanged(idx)
        }
    }

    fun updateItemWithProgress(task: DownloadTask, progress: Int, speed: Long) {
        val idx = data.indexOfFirst { it.id == task.id }
        if (idx >= 0) {
            data[idx] = task
            // 立即更新进度显示，避免频繁的notifyItemChanged
            // 由于无法直接访问RecyclerView，暂时使用notifyItemChanged
            notifyItemChanged(idx)
        }
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): SimpleTaskVH {
        val v = android.view.LayoutInflater.from(parent.context).inflate(R.layout.item_download_task, parent, false)
        return SimpleTaskVH(v, onAction)
    }

    override fun getItemCount(): Int = data.size

    override fun onBindViewHolder(holder: SimpleTaskVH, position: Int) {
        holder.bind(data[position])
    }
}

private class SimpleTaskVH(
    itemView: android.view.View,
    val onAction: (DownloadTask) -> Unit
) : androidx.recyclerview.widget.RecyclerView.ViewHolder(itemView) {

    private val ivCover: com.pichs.xwidget.cardview.XCardImageView = itemView.findViewById(R.id.iv_cover)
    private val title: com.pichs.xwidget.view.XTextView = itemView.findViewById(R.id.tv_title)
    private val progressBar: android.widget.ProgressBar = itemView.findViewById(R.id.progressBar)
    private val tvProgress: com.pichs.xwidget.view.XTextView = itemView.findViewById(R.id.tvProgress)
    private val tvSpeed: com.pichs.xwidget.view.XTextView = itemView.findViewById(R.id.tvSpeed)
    private val tvHint: com.pichs.xwidget.view.XTextView = itemView.findViewById(R.id.tvHint)
    private val btn: com.pichs.xwidget.cardview.XCardButton = itemView.findViewById(R.id.btn_download)

    fun bind(task: DownloadTask) {
        // 绑定图标：优先从 extras(JSON) 读取缓存；其次从首页注册表；再尝试本地已安装应用图标；最后占位色
        data class ExtraMeta(
            val name: String? = null,
            val packageName: String? = null,
            val versionCode: Long? = null,
            val icon: String? = null
        )
        val extraMeta: ExtraMeta? = runCatching {
            val raw = task.extras
            if (!raw.isNullOrBlank()) com.pichs.xbase.utils.GsonUtils.fromJson(raw, ExtraMeta::class.java) else null
        }.getOrNull()
        val meta = extraMeta ?: AppMetaRegistry.getByName(task.fileName)?.let {
            ExtraMeta(name = it.name, packageName = it.packageName, versionCode = it.versionCode, icon = it.icon)
        }
        val ctx = itemView.context
        val iconUrl = meta?.icon
        if (!iconUrl.isNullOrBlank()) {
            Glide.with(ivCover).load(iconUrl).into(ivCover)
        } else {
            val pkg = task.packageName
                ?: AppUtils.getPackageNameForTask(ctx, task)
                ?: ""
            if (pkg.isNotBlank()) {
                runCatching { ctx.packageManager.getApplicationIcon(pkg) }
                    .onSuccess { ivCover.setImageDrawable(it) }
                    .onFailure { ivCover.setImageResource(R.color.purple_200) }
            } else {
                ivCover.setImageResource(R.color.purple_200)
            }
        }

        title.text = meta?.name ?: task.fileName
    val indeterminate = (task.status == DownloadStatus.DOWNLOADING || task.status == DownloadStatus.PENDING || task.status == DownloadStatus.WAITING) && task.totalSize <= 0
        progressBar.isIndeterminate = indeterminate
        if (!indeterminate) {
            progressBar.progress = task.progress
            tvProgress.text = "${task.progress}%"
        } else {
            tvProgress.text = "处理中…"
        }
        tvSpeed.text = com.pichs.download.utils.SpeedUtils.formatDownloadSpeed(task.speed)
        
        // 显示优先级标识
        val priorityText = when (task.priority) {
            3 -> " [紧急]"
            2 -> " [高]"
            1 -> " [普通]"
            0 -> " [后台]"
            else -> ""
        }
        title.text = "${title.text}$priorityText"
    // 优先判断是否“已安装且版本>=商店” → 显示打开
        val pkg = task.packageName
            ?: AppUtils.getPackageNameForTask(itemView.context, task)
            ?: ""
        val storeVC = task.storeVersionCode ?: AppUtils.getStoreVersionCode(itemView.context, pkg)
    val showOpen = pkg.isNotBlank() && AppUtils.isInstalledAndUpToDate(itemView.context, pkg, storeVC)

        // 文件健康检查
        val health = AppUtils.checkFileHealth(task)
        tvHint.visibility = android.view.View.GONE
        if (task.status == DownloadStatus.COMPLETED && health != AppUtils.FileHealth.OK) {
            tvHint.text = if (health == AppUtils.FileHealth.MISSING) "文件缺失" else "文件损坏"
            tvHint.visibility = android.view.View.VISIBLE
        }

        when (task.status) {
            DownloadStatus.DOWNLOADING -> {
                btn.text = "暂停"; btn.isEnabled = true
            }

            DownloadStatus.PAUSED -> {
                btn.text = "继续"; btn.isEnabled = true
            }

            DownloadStatus.WAITING, DownloadStatus.PENDING -> {
                btn.text = "等待中"; btn.isEnabled = true
            }

            DownloadStatus.FAILED -> {
                btn.text = "重试"; btn.isEnabled = true
            }

            DownloadStatus.COMPLETED -> {
                progressBar.isIndeterminate = false; progressBar.progress = 100; tvProgress.text = "100%"
                if (showOpen) {
                    btn.text = "打开"; btn.isEnabled = true
                } else {
                    if (health == AppUtils.FileHealth.OK) {
                        btn.text = "安装"; btn.isEnabled = true
                    } else {
                        // 不显示安装按钮：禁用并清空文案，交互交给右侧 X
                        btn.text = ""; btn.isEnabled = false
                    }
                }
            }

            else -> {
                btn.text = "下载"; btn.isEnabled = true
            }
        }
        btn.setOnClickListener { if (btn.isEnabled) onAction(task) }
    }

    // 专门用于更新进度的方法，避免重新绑定整个ViewHolder
    fun updateProgress(progress: Int, speed: Long) {
        // 更新进度条
        progressBar.progress = progress
        tvProgress.text = "${progress}%"
        
        // 更新速度显示
        tvSpeed.text = com.pichs.download.utils.SpeedUtils.formatDownloadSpeed(speed)
        
        // 更新按钮状态
        when {
            progress >= 100 -> {
                btn.text = "安装"
                btn.isEnabled = true
            }
            progress > 0 -> {
                btn.text = "暂停"
                btn.isEnabled = true
            }
            else -> {
                btn.text = "等待中"
                btn.isEnabled = true
            }
        }
    }
}
