package com.pichs.download.demo

import androidx.recyclerview.widget.LinearLayoutManager
import com.pichs.download.core.DownloadManager
import com.pichs.download.demo.databinding.ActivityDownloadManagerBinding
import com.pichs.download.model.DownloadStatus
import com.pichs.download.model.DownloadTask
import com.pichs.shanhai.base.base.BaseActivity

class DownloadManagerActivity : BaseActivity<ActivityDownloadManagerBinding>() {

    private val downloading = mutableListOf<DownloadTask>()
    private val completed = mutableListOf<DownloadTask>()
    private lateinit var downloadingAdapter: SimpleTaskAdapter
    private lateinit var completedAdapter: SimpleTaskAdapter

    private var globalListener: com.pichs.download.listener.DownloadListener? = null

    override fun afterOnCreate() {
        setupRecycler()
        refreshLists()
        bindListeners()
    }

    private fun setupRecycler() {
        downloadingAdapter = SimpleTaskAdapter(onAction = { task ->
            when (task.status) {
                DownloadStatus.DOWNLOADING -> DownloadManager.pause(task.id)
                DownloadStatus.PAUSED, DownloadStatus.PENDING -> DownloadManager.resume(task.id)
                DownloadStatus.FAILED -> {
                    DownloadManager.resume(task.id)
                }

                else -> {}
            }
        })
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = downloadingAdapter

        completedAdapter = SimpleTaskAdapter(onAction = { task -> openApk(task) })
        binding.recyclerViewDownloaded.layoutManager = LinearLayoutManager(this)
        binding.recyclerViewDownloaded.adapter = completedAdapter
    }

    private fun refreshLists() {
        val all = DownloadManager.getAllTasks()
            .sortedByDescending { it.updateTime }
            .distinctBy { it.id }

        downloading.clear()
        completed.clear()

        all.forEach { task ->
            when (task.status) {
                DownloadStatus.COMPLETED -> completed.add(task)
                DownloadStatus.DOWNLOADING, DownloadStatus.PAUSED, DownloadStatus.PENDING -> downloading.add(task)
                else -> {}
            }
        }

        downloadingAdapter.submit(downloading)
        completedAdapter.submit(completed)
    }

    private fun updateSingle(task: DownloadTask) {
        val inDownloading = downloading.any { it.id == task.id }
        val inCompleted = completed.any { it.id == task.id }
        val shouldBeInDownloading = task.status == DownloadStatus.DOWNLOADING || task.status == DownloadStatus.PAUSED || task.status == DownloadStatus.PENDING
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

    private fun bindListeners() {
        val listener = object : com.pichs.download.listener.DownloadListener {
            override fun onTaskProgress(task: DownloadTask, progress: Int, speed: Long) {
                runOnUiThread { if (!isDestroyed) updateSingle(task) }
            }

            override fun onTaskComplete(task: DownloadTask, file: java.io.File) {
                runOnUiThread { if (!isDestroyed) refreshLists() }
            }

            override fun onTaskError(task: DownloadTask, error: Throwable) {
                runOnUiThread { if (!isDestroyed) updateSingle(task) }
            }
        }
        globalListener = listener
        DownloadManager.addGlobalListener(listener)
    }

    override fun onDestroy() {
        globalListener?.let { DownloadManager.removeGlobalListener(it) }
        globalListener = null
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

    private val title: com.pichs.xwidget.view.XTextView = itemView.findViewById(R.id.tv_title)
    private val progressBar: android.widget.ProgressBar = itemView.findViewById(R.id.progressBar)
    private val tvProgress: com.pichs.xwidget.view.XTextView = itemView.findViewById(R.id.tvProgress)
    private val tvSpeed: com.pichs.xwidget.view.XTextView = itemView.findViewById(R.id.tvSpeed)
    private val btn: com.pichs.xwidget.cardview.XCardButton = itemView.findViewById(R.id.btn_download)

    fun bind(task: DownloadTask) {
        title.text = task.fileName
        val indeterminate = (task.status == DownloadStatus.DOWNLOADING || task.status == DownloadStatus.PENDING) && task.totalSize <= 0
        progressBar.isIndeterminate = indeterminate
        if (!indeterminate) {
            progressBar.progress = task.progress
            tvProgress.text = "${task.progress}%"
        } else {
            tvProgress.text = "处理中…"
        }
        tvSpeed.text = com.pichs.download.utils.SpeedUtils.formatDownloadSpeed(task.speed)
        when (task.status) {
            DownloadStatus.DOWNLOADING -> {
                btn.text = "暂停"; btn.isEnabled = true
            }

            DownloadStatus.PAUSED -> {
                btn.text = "继续"; btn.isEnabled = true
            }

            DownloadStatus.PENDING -> {
                btn.text = "准备中"; btn.isEnabled = false
            }

            DownloadStatus.FAILED -> {
                btn.text = "重试"; btn.isEnabled = true
            }

            DownloadStatus.COMPLETED -> {
                btn.text = "安装"; btn.isEnabled = true; progressBar.isIndeterminate = false; progressBar.progress = 100; tvProgress.text = "100%"
            }

            else -> {
                btn.text = "下载"; btn.isEnabled = true
            }
        }
        btn.setOnClickListener { if (btn.isEnabled) onAction(task) }
    }
}
