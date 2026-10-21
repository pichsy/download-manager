package com.pichs.download.demo

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.pichs.download.model.DownloadTask
import com.pichs.download.model.DownloadTaskChange
import com.pichs.download.model.diffFrom

const val PAYLOAD_STORE_PROGRESS = "STORE_PROGRESS_UPDATE"

/**
 * 商店条目 + 下载任务快照。
 * 列表本身是静态目录，任务状态变化时只替换行数据，避免改动目录对象。
 */
data class StoreAppRow(
    val item: DownloadItem,
    val task: DownloadTask?,
    val installed: Boolean
)

private val STORE_APP_DIFF = object : DiffUtil.ItemCallback<StoreAppRow>() {
    override fun areItemsTheSame(oldItem: StoreAppRow, newItem: StoreAppRow): Boolean {
        return oldItem.item.url == newItem.item.url
    }

    override fun areContentsTheSame(oldItem: StoreAppRow, newItem: StoreAppRow): Boolean {
        return oldItem == newItem
    }

    override fun getChangePayload(oldItem: StoreAppRow, newItem: StoreAppRow): Any? {
        if (oldItem.item != newItem.item || oldItem.installed != newItem.installed) return null
        val oldTask = oldItem.task ?: return null
        val newTask = newItem.task ?: return null
        return if (newTask.diffFrom(oldTask) == DownloadTaskChange.PROGRESS) {
            PAYLOAD_STORE_PROGRESS
        } else {
            null
        }
    }
}

class StoreAppAdapter(
    private val layoutId: Int,
    private val binder: Binder
) : ListAdapter<StoreAppRow, StoreAppAdapter.ViewHolder>(STORE_APP_DIFF) {

    interface Binder {
        fun bind(view: View, row: StoreAppRow)
        fun bindProgress(view: View, row: StoreAppRow)
    }

    class ViewHolder(val view: View) : RecyclerView.ViewHolder(view)

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        return getItem(position).item.url.hashCode().toLong()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        binder.bind(holder.view, getItem(position))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_STORE_PROGRESS)) {
            binder.bindProgress(holder.view, getItem(position))
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }
}
