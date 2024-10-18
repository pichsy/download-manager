package com.pichs.download.demo

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Environment
import androidx.lifecycle.lifecycleScope
import com.drake.brv.utils.linear
import com.drake.brv.utils.setup
import com.hjq.permissions.Permission
import com.hjq.permissions.XXPermissions
import com.pichs.download.breakpoint.DownloadBreakPointManger
import com.pichs.download.breakpoint.DownloadChunkManager
import com.pichs.download.call.DownloadCall
import com.pichs.download.call.DownloadMultiCall
import com.pichs.download.demo.databinding.ActivityMainBinding
import com.pichs.download.demo.databinding.ItemDonwloadListBinding
import com.pichs.download.utils.DownloadTaskUtils
import com.pichs.shanhai.base.base.BaseActivity
import com.pichs.shanhai.base.ext.click
import com.pichs.shanhai.base.utils.LogUtils
import com.pichs.shanhai.base.utils.toast.ToastUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : BaseActivity<ActivityMainBinding>() {


    val zhishangtanbing =
        "https://downali.wandoujia.com/s/3/3/20230112163122_3724e0cf162b42b8a9312e1a37b96436_().apk?nrd=0&fname=%E7%BA%B8%E4%B8%8A%E5%BC%B9%E5%85%B5&productid=2011&packageid=701117189&pkg=com.inkflame.zstb.android&vcode=2225&yingid=wdj_web&minSDK=19&size=99182933&pos=wdj_web%2Fdetail_normal_dl%2F0&shortMd5=9026d75b788574f2e02aa6c82a0d5bb6&appid=8338262&apprd=8338262&crc32=3292204550&iconUrl=http%3A%2F%2Fandroid-artworks.25pp.com%2Ffs08%2F2024%2F08%2F16%2F11%2F109_a4e79a0e10096d5f27d843e0e78f4550_con.png&did=e27d73c02ca499f0366aa2d134566878&md5=af91075a290bf91315dfac9c471e334e"

    val bizhiduoduo =
        "https://ucdl.25pp.com/fs08/2024/10/10/5/106_7efa85efaf878e6e860968bc8d5ddcf3.apk?nrd=0&fname=%E7%9A%AE%E7%9A%AE%E8%99%BE&productid=2011&packageid=203582695&pkg=com.sup.android.superb&vcode=516&yingid=wdj_web&pos=wdj_web%2Fdetail_normal_dl%2F0&appid=7826209&apprd=7826209&iconUrl=http%3A%2F%2Fandroid-artworks.25pp.com%2Ffs08%2F2024%2F10%2F10%2F8%2F106_15b4453aa930accac749585eef10c014_con.png&did=fc315c93e2ee9287634f6833b2a12b78&md5=036de1b66bdc730dbabbf2421c21c6d3"

    private val list = arrayListOf<DownloadItem>()

    override fun afterOnCreate() {

        XXPermissions.with(this)
            .unchecked()
            .permission(Permission.WRITE_EXTERNAL_STORAGE)
            .permission(Permission.READ_EXTERNAL_STORAGE)
            .request { permissions, allGranted ->

            }

        initListener()

        list.add(
            DownloadItem(url = bizhiduoduo, name = "bizhiduoduo"),
        )

        list.add(
            DownloadItem(url = zhishangtanbing, name = "zhishangtanbing"),
        )

        DownloadBreakPointManger.queryAll()?.forEach {
            LogUtils.d("download666", "查询到的下载任务:$it")
        }

        DownloadChunkManager.queryAll()?.forEach {
            LogUtils.d("download666", "查询到的下载任务分块:$it")
        }

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


    @SuppressLint("SetTextI18n")
    private fun initRecyclerView() {

        binding.recyclerView.linear().setup {
            addType<DownloadItem>(R.layout.item_donwload_list)

            onBind {
                val item = getModel<DownloadItem>()
                val itemBinding = getBinding<ItemDonwloadListBinding>()
                itemBinding.tvAppName.text = item.name

                itemBinding.btnDownload.click {
                    if (modelPosition == 0) {
                        DownloadCall()
                            .download(
                                com.pichs.download.DownloadTask.Builder()
                                    .build {
                                        it.url = item.url

                                        it.fileName = item.name + ".apk"

                                        it.filePath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath


                                    }
                            ) { task, totalBytesRead, contentLength, progress, speed ->
                                lifecycleScope.launch(Dispatchers.Main) {
                                    itemBinding.progressBar.progress = progress
                                    itemBinding.tvProgress.text = "$progress%"
                                    itemBinding.btnDownload.text = "下载中:${speed / 1024}Kb/s"
                                }
                            }
                    } else {
                        DownloadMultiCall()
                            .download(
                                com.pichs.download.DownloadTask.Builder()
                                    .build {
                                        it.url = item.url
                                        it.fileName = item.name + ".apk"
                                        it.filePath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
                                        it.taskId = DownloadTaskUtils.generateTaskId(it.url, it.filePath, it.fileName)
                                    }
                            ) { task, totalBytesRead, contentLength, progress, speed ->
//                                lifecycleScope.launch(Dispatchers.Main) {
//                                    itemBinding.progressBar.progress = progress
//                                    itemBinding.tvProgress.text = "$progress%"
//                                    itemBinding.btnDownload.text = "下载中:${speed / 1024}Kb/s"
                                    LogUtils.d("download666", "${item.name}的下载进度:$progress ，速度:${speed / 1024}Kb/s")
//                                }
                            }
                    }
                }
            }

        }.models = list
    }


}