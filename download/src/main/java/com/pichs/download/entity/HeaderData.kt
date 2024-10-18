package com.pichs.download.entity

data class HeaderData(
    var contentLength: Long = 0L,
    var contentRange: String? = null,
    var eTag: String? = null,
    var lastModified: String? = null,
    var contentType: String? = null,
)
