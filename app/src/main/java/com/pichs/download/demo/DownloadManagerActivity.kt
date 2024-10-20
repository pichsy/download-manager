package com.pichs.download.demo

import android.os.Environment
import androidx.lifecycle.lifecycleScope
import com.drake.brv.utils.bindingAdapter
import com.drake.brv.utils.linear
import com.drake.brv.utils.setup
import com.pichs.download.DownloadTask
import com.pichs.download.Downloader
import com.pichs.download.callback.IDownloadListener
import com.pichs.download.demo.databinding.ActivityDownloadManagerBinding
import com.pichs.download.demo.databinding.ItemDownloadTaskBinding
import com.pichs.download.entity.DownloadStatus
import com.pichs.shanhai.base.base.BaseActivity
import com.pichs.shanhai.base.utils.LogUtils
import com.pichs.shanhai.base.utils.toast.ToastUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch


class DownloadManagerActivity : BaseActivity<ActivityDownloadManagerBinding>(), IDownloadListener {

    private val downloadTasks = mutableListOf<DownloadBean>()
    val kugou =
        "https://ucdl.25pp.com/fs08/2024/10/15/7/110_9cebbff19dae12baff6e94b770d8f84e.apk?nrd=0&fname=%E9%85%B7%E7%8B%97%E9%9F%B3%E4%B9%90&productid=2011&packageid=602787910&pkg=com.kugou.android&vcode=12509&yingid=wdj_web&pos=wdj_web%2Fdetail_normal_dl%2F0&appid=34221&apprd=34221&iconUrl=http%3A%2F%2Fandroid-artworks.25pp.com%2Ffs08%2F2024%2F09%2F20%2F1%2F110_14e34e60489d5eed1b8cabf8d397c7a9_con.png&did=313d33151cb5e8a38fa41799bdefce05&md5=f10c1677354da870fc99b0f8ec079892"

    val kuwo =
        "https://ucdl.25pp.com/fs08/2024/10/12/3/110_a77a5510400c57b89497e0fa0c08303c.apk?nrd=0&fname=%E9%85%B7%E6%88%91%E9%9F%B3%E4%B9%90&productid=2011&packageid=801476238&pkg=cn.kuwo.player&vcode=11020&yingid=wdj_web&pos=wdj_web%2Fdetail_normal_dl%2F0&appid=28047&apprd=28047&iconUrl=http%3A%2F%2Fandroid-artworks.25pp.com%2Ffs08%2F2024%2F10%2F14%2F4%2F110_642a5d47e8deb634aa03efa9b13fa3cb_con.png&did=637000d4d8a99f3431b3c196a2318ee5&md5=0112e4544138d4482f14d77d052aacef"

    val douyin =
        "https://ucdl.25pp.com/fs08/2024/10/09/4/120_9b927aae9231b4dd096f38c8093ee550.apk?nrd=0&fname=%E6%8A%96%E9%9F%B3&productid=2011&packageid=501025750&pkg=com.ss.android.ugc.aweme&vcode=310701&yingid=wdj_web&pos=wdj_web%2Fdetail_normal_dl%2F0&appid=7461948&apprd=7461948&iconUrl=http%3A%2F%2Fandroid-artworks.25pp.com%2Ffs08%2F2024%2F08%2F15%2F5%2F110_8d8f02e579639bf1d536c0da2772f916_con.png&did=40128e3579ac4c7da6c36d2ca687c3fb&md5=6327a10d2ceff171759b441bcbb13fb1"

    val kuaishou =
        "https://ucdl.25pp.com/fs08/2024/10/16/10/110_209960fda75afe71f03657a41a6781c4.apk?nrd=0&fname=UC%E6%B5%8F%E8%A7%88%E5%99%A8&productid=2011&packageid=602791738&pkg=com.UCMobile&vcode=11343&yingid=wdj_web&pos=wdj_web%2Fdetail_normal_dl%2F0&appid=36557&apprd=36557&iconUrl=http%3A%2F%2Fandroid-artworks.25pp.com%2Ffs08%2F2024%2F10%2F16%2F2%2F110_b9d6f2434ca762ffe276b04874b9e102_con.png&did=dc879f796ef5216e04dd46ebe8ed1166&md5=1b875eaf0c59bfd2f10fd005b709880a"

    val wangzhe =
        "https://downali.wandoujia.com/s1/7/7/20240925100856_com.tencent.tmgp.sgame_u660371_10.1.1.6_MlI5id.apk?nrd=0&fname=%E7%8E%8B%E8%80%85%E8%8D%A3%E8%80%80&productid=2011&packageid=602706192&pkg=com.tencent.tmgp.sgame&vcode=1001010602&yingid=wdj_web&minSDK=22&size=2072162345&pos=wdj_web%2Fdetail_normal_dl%2F0&shortMd5=67358bc165b0b037574e405d370ba7cf&appid=6648837&apprd=6648837&crc32=2143257901&iconUrl=http%3A%2F%2Fandroid-artworks.25pp.com%2Ffs08%2F2024%2F09%2F25%2F5%2F109_fbdfd14a57a438e4604e1345d66bbfc7_con.png&did=448a4a0c9fc6838ceba720973f3f457e&md5=846fafaf6d36b512c3fa809e9c2845f5"

    val taptap =
        "https://ucdl.25pp.com/fs08/2024/10/14/11/120_a96a86bf5de4a74615686d0f909134d5.apk?nrd=0&fname=TapTap&productid=2011&packageid=401741884&pkg=com.taptap&vcode=271001001&yingid=wdj_web&pos=wdj_web%2Fdetail_normal_dl%2F0&appid=7060083&apprd=7060083&iconUrl=http%3A%2F%2Fandroid-artworks.25pp.com%2Ffs08%2F2021%2F09%2F30%2F10%2F106_6277470a4726c83336916a66e77b068d_con.png&did=2c497f8239b174764e1d5596256b4886&md5=fa7a662f6b56a3ba0fc36c20d53dc80a"

    val kuaishoujisuban =
        "https://ucdl.25pp.com/fs08/2024/10/10/11/120_8504ca04ed8777150e5913d781d3d19b.apk?nrd=0&fname=%E5%BF%AB%E6%89%8B%E6%9E%81%E9%80%9F%E7%89%88&productid=2011&packageid=501026572&pkg=com.kuaishou.nebula&vcode=8788&yingid=wdj_web&pos=wdj_web%2Fdetail_normal_dl%2F0&appid=7880556&apprd=7880556&iconUrl=http%3A%2F%2Fandroid-artworks.25pp.com%2Ffs08%2F2024%2F10%2F11%2F6%2F110_f9357ac63ed7d01b501ab372e6367f29_con.png&did=0c256fa11ea035f6d34b591871e00ebf&md5=2f185514150c614b05529d35705cc9ed"

    override fun afterOnCreate() {
        initRecyclerView()
    }

    private fun initRecyclerView() {

        //OkDownload.with().downloadDispatcher().

        /**
         * 下载监控 + 下载监听
         */
//        OkDownload.with().monitor = object :DownloadMonitor{
//            override fun taskStart(task: DownloadTask?) {
//                LogUtils.d("下载管理：taskStart=====task=${task?.url}")
////                updateTaskStatus(task!!, "Pending")
//            }
//
//            override fun taskDownloadFromBreakpoint(task: DownloadTask, info: BreakpointInfo) {
//                LogUtils.d("下载管理：taskDownloadFromBreakpoint=====info=$info")
//            }
//
//            override fun taskDownloadFromBeginning(task: DownloadTask, info: BreakpointInfo, cause: ResumeFailedCause?) {
//                LogUtils.d("下载管理：taskDownloadFromBeginning=====info=$info")
//            }
//
//            override fun taskEnd(task: DownloadTask?, cause: EndCause?, realCause: java.lang.Exception?) {
//                LogUtils.d("下载管理：taskEnd=====cause=$cause")
//            }
//        }

//        OkDownload.setMaxParallelRunningCount(3)
//        OkDownload.setRemitToDBDelayMillis(1000)

        binding.recyclerView.linear().setup {
            addType<DownloadBean>(R.layout.item_download_task)
            onPayload { payloads ->
                val tag = payloads.firstOrNull()?.toString()
                val item = getModel<DownloadBean>()
                val itemBinding = getBinding<ItemDownloadTaskBinding>()
                LogUtils.d("下载管理：onPayload=====item=${item.name}, tag=$tag")
                if (tag == "status") {
                    itemBinding.tvStatus.text = item.status.toString()
                    when (item.status) {
                        -1 -> {
                            itemBinding.tvStatus.text = "暂未开始"
                        }

                        0 -> {
                            itemBinding.tvStatus.text = "等待下载..."
                        }

                        2 -> {
                            itemBinding.tvStatus.text = "下载中..."
                        }

                        3 -> {
                            itemBinding.tvStatus.text = "下载完成"
                        }

                        4 -> {
                            // 下载出错。
                            itemBinding.tvStatus.text = "下载出错"
                        }

                        5 -> {
                            // 等待wifi下载
                            itemBinding.tvStatus.text = "等待wifi下载"
                        }

                        6, 7 -> {
                            itemBinding.tvStatus.text = "任务已移除!"
                        }
                    }
                } else if (tag == "progress") {
                    itemBinding.progressBar.progress = item.progress
                }
            }

            onBind {
                val item = getModel<DownloadBean>()
                LogUtils.d("下载管理：onBind=====item=${item.name}")
                val itemBinding = getBinding<ItemDownloadTaskBinding>()
                itemBinding.tvName.text = item.name
                itemBinding.progressBar.progress = item.progress
                itemBinding.tvStatus.text = item.status.toString()
                when (item.status) {
                    -1 -> {
                        itemBinding.tvStatus.text = "暂未开始"
                    }

                    0 -> {
                        itemBinding.tvStatus.text = "等待下载..."
                    }

                    2 -> {
                        itemBinding.tvStatus.text = "下载中..."
                    }

                    3 -> {
                        itemBinding.tvStatus.text = "下载完成"
                    }

                    4 -> {
                        // 下载出错。
                        itemBinding.tvStatus.text = "下载出错"
                    }

                    5 -> {
                        // 等待wifi下载
                        itemBinding.tvStatus.text = "等待wifi下载"
                    }

                    6, 7 -> {
                        itemBinding.tvStatus.text = "任务已移除!"
                    }
                }
            }

            onClick(R.id.btnAction) {
                val item = getModel<DownloadBean>()
                when (item.status) {
                    -1 -> {
                        Downloader.Builder()
                            .setListener(this@DownloadManagerActivity)
                            .setDownloadTaskInfo {
                                url = item.url
                                filePath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
                                fileName = item.name + ".apk"
                            }
                            .build()
                            .also { item.task = it }
                            .pushTask()

                    }

                    0 -> {
                        // 可能未进行下载。点击后添加任务中。
                        Downloader.Builder()
                            .setListener(this@DownloadManagerActivity)
                            .setDownloadTaskInfo {
                                url = item.url
                                filePath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
                                fileName = item.name + ".apk"
                            }
                            .build()
                            .also { item.task = it }
                            .pushTask()
                    }

                    2 -> {

                    }

                    3 -> {
                        startInstall(item)
                    }

                    4 -> {

                    }

                    5 -> {

                    }

                    6, 7 -> {
                        ToastUtils.toast("任务已移除!")
                    }

                }
            }
        }.models = downloadTasks

        addDownloadTask(
            url = kugou,
            name = "酷狗音乐",
            packageName = "com.kugou.android",
        )
        addDownloadTask(
            url = kuwo,
            name = "酷我音乐",
            packageName = "com.kuwo.android",
        )
        addDownloadTask(
            url = douyin,
            name = "抖音",
            packageName = "com.ss.android.ugc.aweme",
        )
        addDownloadTask(
            url = kuaishou,
            name = "快手",
            packageName = "com.kuaishou.nebula",
        )
        addDownloadTask(
            url = wangzhe,
            name = "王者荣耀",
            packageName = "com.eg.android.AlipayGphone",
        )
        addDownloadTask(
            url = taptap,
            name = "taptap",
            packageName = "com.taptap.android",
        )
        addDownloadTask(
            url = kuaishoujisuban,
            name = "快手的极速版",
            packageName = "com.kuaishou.android",
        )

        binding.recyclerView.bindingAdapter.models = downloadTasks
    }

    private fun startInstall(item: DownloadBean) {
        // 实现安装逻辑
    }

    private fun updateTaskStatus(task: DownloadTask, status: Int) {
        lifecycleScope.launch(Dispatchers.Main) {
            val index = downloadTasks.indexOfFirst { it.task == task }
            if (index != -1) {
                downloadTasks[index].status = status
                binding.recyclerView.bindingAdapter.notifyItemChanged(index, "status")
            }
        }
    }

    private fun updateTaskProgress(task: DownloadTask, progress: Int, currentLength: Long, totalLength: Long, speed: Long) {
        lifecycleScope.launch(Dispatchers.Main) {
            val index = downloadTasks.indexOfFirst { it.task == task }
            if (index != -1) {
                downloadTasks[index].progress = progress
                binding.recyclerView.bindingAdapter.notifyItemChanged(index, "progress")
            }
        }
    }

    fun addDownloadTask(url: String, name: String, packageName: String) {
        val newTask = DownloadBean(url = url, name = name, status = DownloadStatus.DEFAULT, packageName = packageName)
        downloadTasks.add(newTask)
    }

    override fun onPrepare(task: DownloadTask?) {
        if (task == null) return
        updateTaskStatus(task, DownloadStatus.WAITING)
    }


    // 回调========================================================================
    override fun onStart(task: DownloadTask?, totalLength: Long) {
        if (task == null) return
        updateTaskStatus(task, DownloadStatus.DOWNLOADING)
    }

    override fun onPause(task: DownloadTask?) {
        if (task == null) return
        updateTaskStatus(task, DownloadStatus.PAUSE)
    }

    override fun onProgress(task: DownloadTask?, currentLength: Long, totalLength: Long, progress: Int, speed: Long) {
        if (task == null) return
        updateTaskProgress(task, progress, currentLength, totalLength, speed)
    }

    override fun onComplete(task: DownloadTask?) {
        if (task == null) return
        updateTaskStatus(task, DownloadStatus.COMPLETED)
    }

    override fun onError(task: DownloadTask?, e: Throwable?) {
        if (task == null) return
        updateTaskStatus(task, DownloadStatus.ERROR)
    }

    override fun onCancel(task: DownloadTask?) {
        if (task == null) return
        updateTaskStatus(task, DownloadStatus.CANCEL)
    }
}
