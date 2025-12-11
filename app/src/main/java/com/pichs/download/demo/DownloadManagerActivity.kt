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
                DownloadStatus.DOWNLOADING -> {
                    DownloadManager.pause(task.id)
                    // 借鉴MainActivity：立即更新本地数据，然后通过updateSingle触发UI刷新
                    val paused = task.copy(status = DownloadStatus.PAUSED, speed = 0L, updateTime = System.currentTimeMillis())
                    // 先更新本地列表数据
                    val idx = downloading.indexOfFirst { it.id == task.id }
                    if (idx >= 0) {
                        downloading[idx] = paused
                    }
                    // 再触发UI更新
                    updateSingle(paused)
                }
                DownloadStatus.PAUSED -> {
                    DownloadManager.resume(task.id)
                    // 状态更新由 onTaskResumed 回调处理，task.status 会变为 WAITING
                }
                DownloadStatus.PENDING, DownloadStatus.WAITING -> {
                    DownloadManager.pause(task.id)
                    // 同步本地一份，立刻显示"继续"
                    val paused = task.copy(status = DownloadStatus.PAUSED, speed = 0L, updateTime = System.currentTimeMillis())
                    // 先更新本地列表数据
                    val idx = downloading.indexOfFirst { it.id == task.id }
                    if (idx >= 0) {
                        downloading[idx] = paused
                    }
                    // 再触发UI更新
                    updateSingle(paused)
                }
                DownloadStatus.FAILED -> DownloadManager.resume(task.id)
                else -> {}
            }
        })
        binding.recyclerView.setItemAnimatorDisable()

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        downloadingAdapter.setHasStableIds(true)
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
                // 文件缺失或损坏，删除旧任务并重新下载
                lifecycleScope.launch {
                    DownloadManager.deleteTask(task.id, deleteFile = true)
                    // 从 completed 列表移除
                    val idx = completed.indexOfFirst { it.id == task.id }
                    if (idx >= 0) {
                        completed.removeAt(idx)
                        completedAdapter.notifyItemRemoved(idx)
                    }
                    // 重新创建下载任务
                    val dir = externalCacheDir?.absolutePath ?: cacheDir.absolutePath
                    val newTask = DownloadManager.download(task.url)
                        .path(dir)
                        .fileName(task.fileName)
                        .start()
                    // 添加到下载中列表
                    downloading.add(0, newTask)
                    downloadingAdapter.notifyItemInserted(0)
                    android.widget.Toast.makeText(this@DownloadManagerActivity, "开始重新下载", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        })
        binding.recyclerViewDownloaded.setItemAnimatorDisable()
        binding.recyclerViewDownloaded.layoutManager = LinearLayoutManager(this)
        completedAdapter.setHasStableIds(true)
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
        // 仅维护下载中列表；完成列表统一通过DB刷新
        val idx = downloading.indexOfFirst { it.id == task.id }
        if (idx >= 0) {
            downloading[idx] = task
            downloadingAdapter.updateItem(task)
        }
    }

    // 专门处理进度更新的方法
    private fun updateSingleWithProgress(task: DownloadTask, progress: Int, speed: Long) {
        val idx = downloading.indexOfFirst { it.id == task.id }
        if (idx >= 0) {
            downloading[idx] = task
            // 立即更新进度显示
            downloadingAdapter.updateItemWithProgress(task, progress, speed)
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
                removeFromDownloading(task.id)
                refreshCompletedFromDB()
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
                removeFromDownloading(task.id)
            }
        )
    }

    private fun removeFromDownloading(taskId: String) {
        val idx = downloading.indexOfFirst { it.id == taskId }
        if (idx >= 0) {
            downloading.removeAt(idx)
            downloadingAdapter.submit(downloading)
        }
    }

    private fun refreshCompletedFromDB() {
        lifecycleScope.launch {
            val all = DownloadManager.getAllTasks()
                .filter { it.status == DownloadStatus.COMPLETED }
                .sortedByDescending { it.updateTime }
            completed.clear()
            completed.addAll(all)
            completedAdapter.submit(completed)
        }
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
            // 使用Payload进行局部刷新，避免由notifyItemChanged触发的full bind导致的图片加载和PM调用
            notifyItemChanged(idx, "PROGRESS_UPDATE")
        }
    }

    override fun getItemId(position: Int): Long {
        return data.getOrNull(position)?.id?.hashCode()?.toLong() ?: androidx.recyclerview.widget.RecyclerView.NO_ID
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): SimpleTaskVH {
        val v = android.view.LayoutInflater.from(parent.context).inflate(R.layout.item_download_task, parent, false)
        return SimpleTaskVH(v, onAction)
    }

    override fun getItemCount(): Int = data.size

    override fun onBindViewHolder(holder: SimpleTaskVH, position: Int) {
        holder.bind(data[position])
    }

    // 处理局部刷新
    override fun onBindViewHolder(holder: SimpleTaskVH, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty()) {
            val task = data[position]
            // 仅更新进度和速度
            holder.updateProgress(task)
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }
}

private class SimpleTaskVH(
    itemView: android.view.View,
    val onAction: (DownloadTask) -> Unit
) : androidx.recyclerview.widget.RecyclerView.ViewHolder(itemView) {

    private val ivCover: com.pichs.xwidget.cardview.XCardImageView = itemView.findViewById(R.id.iv_cover)
    private val title: com.pichs.xwidget.view.XTextView = itemView.findViewById(R.id.tv_title)
    private val tvSpeed: com.pichs.xwidget.view.XTextView = itemView.findViewById(R.id.tvSpeed)
    private val tvHint: com.pichs.xwidget.view.XTextView = itemView.findViewById(R.id.tvHint)
    private val btn: com.pichs.download.demo.widget.ProgressButton = itemView.findViewById(R.id.btn_download)
    
    // 保存当前任务引用
    private var currentTask: DownloadTask? = null
    
    init {
        // 在构造时设置点击监听器，使用 currentTask 引用
        btn.setOnClickListener { 
            android.util.Log.d("SimpleTaskVH", "Button clicked, currentTask: ${currentTask?.id}, status: ${currentTask?.status}")
            currentTask?.let { task ->
                onAction(task)
            }
        }
    }

    fun bind(task: DownloadTask) {
        currentTask = task
        
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
        
        // 设置进度条
        btn.setProgress(task.progress)
        
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
        
        // 优先判断是否"已安装且版本>=商店" → 显示打开
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
                btn.setText("${task.progress}%")
                btn.setProgress(task.progress)
                btn.isEnabled = true
                tvSpeed.visibility = android.view.View.VISIBLE
            }

            DownloadStatus.PAUSED -> {
                btn.setText("继续")
                btn.isEnabled = true
                tvSpeed.visibility = android.view.View.GONE
                tvSpeed.text = ""
            }

            DownloadStatus.WAITING, DownloadStatus.PENDING -> {
                btn.setText("等待中")
                btn.isEnabled = true
                tvSpeed.visibility = android.view.View.GONE
                tvSpeed.text = ""
            }

            DownloadStatus.FAILED -> {
                btn.setText("重试")
                btn.isEnabled = true
                tvSpeed.visibility = android.view.View.GONE
                tvSpeed.text = ""
            }

            DownloadStatus.COMPLETED -> {
                btn.setProgress(100)
                // 显示文件大小或文件状态
                if (health == AppUtils.FileHealth.OK) {
                    tvSpeed.text = formatFileSize(task.totalSize)
                    tvSpeed.visibility = android.view.View.VISIBLE
                } else {
                    tvSpeed.text = if (health == AppUtils.FileHealth.MISSING) "文件缺失" else "文件损坏"
                    tvSpeed.visibility = android.view.View.VISIBLE
                }
                if (showOpen) {
                    btn.setText("打开")
                    btn.isEnabled = true
                } else {
                    if (health == AppUtils.FileHealth.OK) {
                        btn.setText("安装")
                        btn.isEnabled = true
                    } else {
                        btn.setText("重新下载")
                        btn.isEnabled = true
                    }
                }
            }

            else -> {
                btn.setText("下载")
                btn.setProgress(0)
                btn.isEnabled = true
                tvSpeed.visibility = android.view.View.GONE
            }
        }
    }

    // 专门用于更新进度的方法，避免重新绑定整个ViewHolder
    fun updateProgress(task: DownloadTask) {
        currentTask = task
        btn.setProgress(task.progress)
        btn.setText("${task.progress}%")
        
        tvSpeed.text = com.pichs.download.utils.SpeedUtils.formatDownloadSpeed(task.speed)
        tvSpeed.visibility = android.view.View.VISIBLE
        
        btn.isEnabled = true
    }
    
    private fun formatFileSize(size: Long): String {
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
}
