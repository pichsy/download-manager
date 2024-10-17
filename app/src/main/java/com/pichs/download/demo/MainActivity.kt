package com.pichs.download.demo

import android.annotation.SuppressLint
import android.content.Intent
import androidx.databinding.DataBindingUtil.getBinding
import androidx.lifecycle.lifecycleScope
import com.drake.brv.utils.linear
import com.drake.brv.utils.setup
import com.liulishuo.okdownload.DownloadTask
import com.liulishuo.okdownload.core.cause.EndCause
import com.liulishuo.okdownload.core.cause.ResumeFailedCause
import com.liulishuo.okdownload.core.listener.DownloadListener1
import com.liulishuo.okdownload.core.listener.assist.Listener1Assist
import com.pichs.download.demo.databinding.ActivityMainBinding
import com.pichs.download.demo.databinding.ItemDonwloadListBinding
import com.pichs.shanhai.base.base.BaseActivity
import com.pichs.shanhai.base.ext.click
import com.pichs.shanhai.base.utils.toast.ToastUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.lang.Exception

class MainActivity : BaseActivity<ActivityMainBinding>() {


    val pipixia =
        "https://ucdl.25pp.com/fs08/2024/10/10/5/106_7efa85efaf878e6e860968bc8d5ddcf3.apk?nrd=0&fname=%E7%9A%AE%E7%9A%AE%E8%99%BE&productid=2011&packageid=203582695&pkg=com.sup.android.superb&vcode=516&yingid=wdj_web&pos=wdj_web%2Fdetail_normal_dl%2F0&appid=7826209&apprd=7826209&iconUrl=http%3A%2F%2Fandroid-artworks.25pp.com%2Ffs08%2F2024%2F10%2F10%2F8%2F106_15b4453aa930accac749585eef10c014_con.png&did=fc315c93e2ee9287634f6833b2a12b78&md5=036de1b66bdc730dbabbf2421c21c6d3"

    val bizhiduoduo =
        "https://ucdl.25pp.com/fs08/2024/10/15/5/110_5da8fb346cc954b889dafff2cd239b00.apk?nrd=0&fname=%E5%A3%81%E7%BA%B8%E5%A4%9A%E5%A4%9A&productid=2011&packageid=203615850&pkg=com.shoujiduoduo.wallpaper&vcode=6006690&yingid=wdj_web&pos=wdj_web%2Fdetail_normal_dl%2F0&appid=5784049&apprd=5784049&iconUrl=http%3A%2F%2Fandroid-artworks.25pp.com%2Ffs08%2F2024%2F09%2F29%2F2%2F110_8b07169544819a5baec06cab5c58b542_con.png&did=b8c9935abb5c629d72b44bb7815dbf11&md5=9b3ffc95c0083943533b95dc4ed9592b"

    private val list = arrayListOf<DownloadItem>()

    override fun afterOnCreate() {

        initListener()

        list.add(
            DownloadItem(url = pipixia, name = "皮皮虾"),
        )
        list.add(
            DownloadItem(url = bizhiduoduo, name = "壁纸多多"),
        )

        initRecyclerView()


    }

    private fun initListener() {

        binding.ivDownloadManager.setOnClickListener {
            startActivity(Intent(this, DownloadManagerActivity::class.java))
        }

        binding.ivSearch.setOnClickListener {

            ToastUtils.show("搜索")

        }
    }


    private fun initRecyclerView() {

        binding.recyclerView.linear().setup {
            addType<DownloadItem>(R.layout.item_donwload_list)

            onBind {
                val item = getModel<DownloadItem>()
                val itemBinding = getBinding<ItemDonwloadListBinding>()
                itemBinding.tvAppName.text = item.name

                itemBinding.btnDownload.click {

                    val task = DownloadTask.Builder(item.url, cacheDir)
                        .setFilename(item.name + ".apk")
                        .setMinIntervalMillisCallbackProcess(30)
                        .setPassIfAlreadyCompleted(false)
                        .build()

                    task.enqueue(object : DownloadListener1() {
                        override fun taskStart(task: DownloadTask, model: Listener1Assist.Listener1Model) {
                        }

                        override fun taskEnd(task: DownloadTask, cause: EndCause, realCause: Exception?, model: Listener1Assist.Listener1Model) {
                        }

                        override fun retry(task: DownloadTask, cause: ResumeFailedCause) {
                        }

                        override fun connected(task: DownloadTask, blockCount: Int, currentOffset: Long, totalLength: Long) {
                        }

                        @SuppressLint("SetTextI18n")
                        override fun progress(task: DownloadTask, currentOffset: Long, totalLength: Long) {
                            lifecycleScope.launch(Dispatchers.Main) {
                                itemBinding.tvProgress.text = (currentOffset * 100 / totalLength).toInt().toString() + "%"
                                itemBinding.progressBar.progress = (currentOffset * 100 / totalLength).toInt()
                            }
                        }

                    })
                }
            }

        }.models = list
    }


}