package com.pichs.download.demo

import android.os.Parcelable
import com.pichs.download.model.DownloadTask
import kotlinx.parcelize.Parcelize

@Parcelize
data class AppListBean(
    var appList: MutableList<DownloadItem>? = null,
) : Parcelable

@Parcelize
data class DownloadItem(
    var name: String = "",
    var install_count: String = "",
    var short_description: String = "",
    var detail_url: String = "",
    var app_id: String = "",
    var baidu_appid: String = "",
    var icon_url: String = "", // JSON field is icon_url, mapped to icon in UI logic often
    var size: String = "0B",   // JSON is string like "122.14MB"
    var version: String = "",
    var update_time: String = "",
    var categories: MutableList<String>? = null,
    var tags: MutableList<String>? = null,
    var developer: String = "",
    var registration_no: String = "",
    var organizer: String = "",
    var description: String = "",
    var screenshots: MutableList<String>? = null,
    var download_url: String = "",
    var package_name: String = "",
    var version_code: String = "0", // JSON string "11482"
    @Transient
    @kotlinx.parcelize.IgnoredOnParcel
    var priority: Int = 1,
    @Transient
    @kotlinx.parcelize.IgnoredOnParcel
    var isInstalled: Boolean = false
) : android.os.Parcelable {

    // Local fields (Excluded from Parcelize constructor)
    @Transient
    @kotlinx.parcelize.IgnoredOnParcel
    var task: DownloadTask? = null

    // Computes the actual URL for download manager
    val url: String
        get() = download_url

    // Computes the package name for logic
    val packageName: String
        get() = package_name

    // Nullable version for compatibility
    val versionName: String?
        get() = version

    // Computes numeric versionCode
    val versionCode: Long
        get() = version_code.toLongOrNull() ?: 0L

    // Computes icon for existing logic
    val icon: String?
        get() = icon_url

    // Computes numeric size in bytes for DownloadManager
    val sizeBytes: Long
        get() = parseSize(size)

    private fun parseSize(sizeStr: String): Long {
        if (sizeStr.isBlank()) return 0L
        val upper = sizeStr.uppercase()
        return when {
            upper.endsWith("GB") -> (upper.removeSuffix("GB").toDoubleOrNull()?.times(1024 * 1024 * 1024))?.toLong() ?: 0L
            upper.endsWith("MB") -> (upper.removeSuffix("MB").toDoubleOrNull()?.times(1024 * 1024))?.toLong() ?: 0L
            upper.endsWith("KB") -> (upper.removeSuffix("KB").toDoubleOrNull()?.times(1024))?.toLong() ?: 0L
            upper.endsWith("B") -> (upper.removeSuffix("B").toDoubleOrNull())?.toLong() ?: 0L
            else -> sizeStr.toLongOrNull() ?: 0L
        }
    }
}
