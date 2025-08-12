package com.pichs.download.demo

/**
 * 进程内简单元数据注册表：用于在首页加载后，把每个条目的包名/版本等缓存，
 * 其他页面优先使用这里的数据，避免重复解析或不一致。
 */
object AppMetaRegistry {
    private val lock = Any()
    private val byName = LinkedHashMap<String, DownloadItem>()

    private fun key(name: String): String = name.substringBeforeLast('.')
        .trim()
        .lowercase()

    fun clear() = synchronized(lock) { byName.clear() }

    fun registerAll(list: List<DownloadItem>) = synchronized(lock) {
        list.forEach { it.name?.let { n -> byName[key(n)] = it } }
    }

    fun getByName(displayName: String): DownloadItem? = synchronized(lock) {
        byName[key(displayName)]
    }
}
