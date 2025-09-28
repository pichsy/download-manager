package com.pichs.download.utils

object ContentTypeMapper {
    private val contentTypeToExtension = mutableMapOf(
        "application/vnd.android.package-archive" to "apk",
        // Image types
        "image/avif" to "avif",
        "image/bmp" to "bmp",
        "image/gif" to "gif",
        "image/vnd.microsoft.icon" to "ico",
        "image/jpeg" to "jpg",
        "image/png" to "png",
        "image/svg+xml" to "svg",
        "image/tiff" to "tif",
        "image/webp" to "webp",

        // Video types
        "video/x-msvideo" to "avi",
        "video/mp4" to "mp4",
        "video/mpeg" to "mpeg",
        "video/ogg" to "ogv",
        "video/mp2t" to "ts",
        "video/webm" to "webm",
        "video/3gpp" to "3gp",
        "video/3gpp2" to "3g2",

        // Audio types
        "audio/aac" to "aac",
        "audio/midi" to "mid",
        "audio/x-midi" to "midi",
        "audio/mpeg" to "mp3",
        "audio/ogg" to "oga",
        "audio/opus" to "opus",
        "audio/wav" to "wav",
        "audio/webm" to "weba",

        // Font types
        "font/otf" to "otf",
        "font/ttf" to "ttf",
        "font/woff" to "woff",
        "font/woff2" to "woff2",

        "application/x-msdownload" to "exe",
        "application/x-apple-diskimage" to "dmg",
        "application/x-debian-package" to "deb",

        "application/gzip" to "gz",
        "application/java-archive" to "jar",
        "application/json" to "json",
        "application/vnd.rar" to "rar",
        "application/zip" to "zip",
        "application/x-7z-compressed" to "7z",
        "application/octet-stream" to "bin",
        "application/pdf" to "pdf",
        "application/msword" to "doc",
        "application/vnd.ms-powerpoint" to "ppt",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" to "pptx",
        "application/vnd.ms-excel" to "xls",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "xlsx",

        "application/x-tar" to "tar",
        "application/x-bzip" to "bz",
        "application/x-bzip2" to "bz2",
        "application/x-cdf" to "cda",
        "application/x-csh" to "csh",
        "application/vnd.ms-fontobject" to "eot",
        "application/epub+zip" to "epub",
        "application/ld+json" to "jsonld",
        "application/vnd.apple.installer+xml" to "mpkg",
        "application/vnd.oasis.opendocument.presentation" to "odp",
        "application/vnd.oasis.opendocument.spreadsheet" to "ods",
        "application/vnd.oasis.opendocument.text" to "odt",
        "application/ogg" to "ogx",
        "application/x-httpd-php" to "php",
        "application/rtf" to "rtf",
        "application/x-sh" to "sh",
        "application/x-shockwave-flash" to "swf",
        "application/vnd.visio" to "vsd",
        "application/xhtml+xml" to "xhtml",
        "application/xml" to "xml",
        "application/vnd.mozilla.xul+xml" to "xul",
        // Text types
        "text/css" to "css",
        "text/csv" to "csv",
        "text/html" to "html",
        "text/calendar" to "ics",
        "text/javascript" to "js",
        "text/plain" to "txt",
        "text/xml" to "xml",
    )

    fun getExtensionFromContentType(contentType: String?): String? {
        return contentType?.let { contentTypeToExtension.getOrDefault(it.lowercase(), "") }
    }

    fun addContentTypeMapping(contentType: String, extension: String) {
        contentTypeToExtension[contentType.lowercase()] = extension.lowercase()
    }
}