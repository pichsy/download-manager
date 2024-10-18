package com.pichs.download.entity

data class HeaderData(
    var contentLength: Long = 0L,
    var contentType: String? = null,
    var contentRange: String? = null,
    var eTag: String? = null,
    var lastModified: String? = null,
)
