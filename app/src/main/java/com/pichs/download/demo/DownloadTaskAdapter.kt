package com.pichs.download.demo

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.pichs.download.model.DownloadStatus
import com.pichs.download.model.DownloadTask
import com.pichs.download.demo.widget.ProgressButton
import com.pichs.xwidget.cardview.XCardImageView
import com.pichs.xwidget.view.XTextView

/**
 * 下载任务通用 Adapter
 */
class DownloadTaskAdapter(
    private val onAction: (DownloadTask) -> Unit,
    private val onLoadIcon: (ImageView, DownloadTask) -> Unit
) : RecyclerView.Adapter<DownloadTaskAdapter.TaskViewHolder>() {

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
            notifyItemChanged(idx, "PROGRESS_UPDATE")
        }
    }

    override fun getItemId(position: Int): Long {
        return data.getOrNull(position)?.id?.hashCode()?.toLong() ?: RecyclerView.NO_ID
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TaskViewHolder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_download_task, parent, false)
        return TaskViewHolder(v, onAction, onLoadIcon)
    }

    override fun getItemCount(): Int = data.size

    override fun onBindViewHolder(holder: TaskViewHolder, position: Int) {
        holder.bind(data[position])
    }

    override fun onBindViewHolder(holder: TaskViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty()) {
            holder.updateProgress(data[position])
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    class TaskViewHolder(
        itemView: View,
        private val onAction: (DownloadTask) -> Unit,
        private val onLoadIcon: (ImageView, DownloadTask) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val ivCover: XCardImageView = itemView.findViewById(R.id.iv_cover)
        private val title: XTextView = itemView.findViewById(R.id.tv_title)
        private val tvSpeed: XTextView = itemView.findViewById(R.id.tvSpeed)
        private val tvHint: XTextView = itemView.findViewById(R.id.tvHint)
        private val btn: ProgressButton = itemView.findViewById(R.id.btn_download)

        private var currentTask: DownloadTask? = null

        init {
            btn.setOnClickListener {
                currentTask?.let { task -> onAction(task) }
            }
        }

        fun bind(task: DownloadTask) {
            currentTask = task

            // 加载图标
            onLoadIcon(ivCover, task)

            // 获取应用名称
            val meta = ExtraMeta.fromJson(task.extras)
            title.text = meta?.name ?: task.fileName

            // 显示优先级标识
            val priorityText = when (task.priority) {
                3 -> " [紧急]"
                2 -> " [高]"
                1 -> " [普通]"
                0 -> " [后台]"
                else -> ""
            }
            title.text = "${title.text}$priorityText"

            // 设置进度条
            btn.setProgress(task.progress)
            tvSpeed.text = com.pichs.download.utils.SpeedUtils.formatDownloadSpeed(task.speed)

            // 文件健康检查
            val health = AppUtils.checkFileHealth(task)
            tvHint.visibility = View.GONE
            if (task.status == DownloadStatus.COMPLETED && health != AppUtils.FileHealth.OK) {
                tvHint.text = if (health == AppUtils.FileHealth.MISSING) "文件缺失" else "文件损坏"
                tvHint.visibility = View.VISIBLE
            }

            // 检查是否已安装且是最新版本
            val pkg = meta?.packageName
                ?: AppUtils.getPackageNameForTask(itemView.context, task)
                ?: ""
            val storeVC = meta?.versionCode ?: AppUtils.getStoreVersionCode(itemView.context, pkg)
            val showOpen = pkg.isNotBlank() && AppUtils.isInstalledAndUpToDate(itemView.context, pkg, storeVC)

            when (task.status) {
                DownloadStatus.DOWNLOADING -> {
                    btn.setText("${task.progress}%")
                    btn.setProgress(task.progress)
                    btn.isEnabled = true
                    tvSpeed.visibility = View.VISIBLE
                }

                DownloadStatus.PAUSED -> {
                    btn.setText("继续")
                    btn.isEnabled = true
                    tvSpeed.visibility = View.GONE
                    tvSpeed.text = ""
                }

                DownloadStatus.WAITING, DownloadStatus.PENDING -> {
                    btn.setText("等待中")
                    btn.isEnabled = true
                    tvSpeed.visibility = View.GONE
                    tvSpeed.text = ""
                }

                DownloadStatus.FAILED -> {
                    btn.setText("重试")
                    btn.isEnabled = true
                    tvSpeed.visibility = View.GONE
                    tvSpeed.text = ""
                }

                DownloadStatus.COMPLETED -> {
                    btn.setProgress(100)
                    if (health == AppUtils.FileHealth.OK) {
                        tvSpeed.text = FormatUtils.formatFileSize(task.totalSize)
                        tvSpeed.visibility = View.VISIBLE
                    } else {
                        tvSpeed.text = if (health == AppUtils.FileHealth.MISSING) "文件缺失" else "文件损坏"
                        tvSpeed.visibility = View.VISIBLE
                    }
                    when {
                        showOpen -> {
                            btn.setText("打开")
                            btn.isEnabled = true
                        }
                        health == AppUtils.FileHealth.OK -> {
                            btn.setText("安装")
                            btn.isEnabled = true
                        }
                        else -> {
                            btn.setText("重新下载")
                            btn.isEnabled = true
                        }
                    }
                }

                else -> {
                    btn.setText("下载")
                    btn.setProgress(0)
                    btn.isEnabled = true
                    tvSpeed.visibility = View.GONE
                }
            }
        }

        fun updateProgress(task: DownloadTask) {
            currentTask = task
            btn.setProgress(task.progress)
            btn.setText("${task.progress}%")
            tvSpeed.text = com.pichs.download.utils.SpeedUtils.formatDownloadSpeed(task.speed)
            tvSpeed.visibility = View.VISIBLE
            btn.isEnabled = true
        }
    }
}
