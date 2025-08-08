package com.pichs.download.demo

import android.os.Bundle
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

    override fun afterOnCreate() {
        setupRecycler()
        loadTasks()
        bindListeners()
    }

    private fun setupRecycler() {
        downloadingAdapter = SimpleTaskAdapter(onAction = { task ->
            when (task.status) {
                DownloadStatus.DOWNLOADING -> DownloadManager.pause(task.id)
                DownloadStatus.PAUSED -> DownloadManager.resume(task.id)
                else -> {}
            }
        })
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = downloadingAdapter

        completedAdapter = SimpleTaskAdapter(onAction = { /*no-op*/ })
        binding.recyclerViewDownloaded.layoutManager = LinearLayoutManager(this)
        binding.recyclerViewDownloaded.adapter = completedAdapter
    }

    private fun loadTasks() {
        val all = DownloadManager.getAllTasks()
        downloading.clear()
        completed.clear()
        all.forEach { if (it.status == DownloadStatus.COMPLETED) completed.add(it) else downloading.add(it) }
        downloadingAdapter.submit(downloading)
        completedAdapter.submit(completed)
    }

    private fun bindListeners() {
        DownloadManager.addGlobalListener(object : com.pichs.download.listener.DownloadListener {
            override fun onTaskProgress(task: DownloadTask, progress: Int, speed: Long) {
                val idx = downloading.indexOfFirst { it.id == task.id }
                if (idx >= 0) {
                    downloading[idx] = task
                    downloadingAdapter.updateItem(task)
                }
            }

            override fun onTaskComplete(task: DownloadTask, file: java.io.File) {
                val idx = downloading.indexOfFirst { it.id == task.id }
                if (idx >= 0) downloading.removeAt(idx)
                completed.add(0, task)
                downloadingAdapter.submit(downloading)
                completedAdapter.submit(completed)
            }

            override fun onTaskError(task: DownloadTask, error: Throwable) {
                val idx = downloading.indexOfFirst { it.id == task.id }
                if (idx >= 0) {
                    downloading[idx] = task
                    downloadingAdapter.updateItem(task)
                }
            }
        })
    }
}

// 简单任务适配器
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
        progressBar.progress = task.progress
        tvProgress.text = "${task.progress}%"
        tvSpeed.text = com.pichs.download.utils.SpeedUtils.formatDownloadSpeed(task.speed)
        btn.text = when (task.status) {
            DownloadStatus.DOWNLOADING -> "暂停"
            DownloadStatus.PAUSED -> "继续"
            DownloadStatus.FAILED -> "重试"
            DownloadStatus.COMPLETED -> "打开"
            else -> "下载"
        }
        btn.setOnClickListener { onAction(task) }
    }
}
