package com.pichs.download.demo.install

/**
 * 安装任务
 */
data class InstallTask(
    val packageName: String,
    val apkPath: String,
    val versionCode: Long,
    var retryCount: Int = 0,
    var status: InstallStatus = InstallStatus.PENDING
)

/**
 * 安装状态
 */
enum class InstallStatus {
    PENDING,      // 等待安装
    INSTALLING,   // 安装中
    SUCCESS,      // 安装成功
    FAILED        // 安装失败
}
