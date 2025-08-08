package com.pichs.download.internal

data class HeaderData(
    var contentLength: Long = -1L,
    var contentType: String? = null,
    var eTag: String? = null,
    var acceptRanges: String? = null,
)
