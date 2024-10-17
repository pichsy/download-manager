package com.pichs.shanhai.base.http.result

import com.google.gson.annotations.SerializedName

data class CommonDataResp<T>(val `data`: T)

data class CommonListResp<T>(
    @SerializedName(value = "list", alternate = ["data", "text", "catalogs", "courses", "specialList"])
    val list: MutableList<T>? = null,
    @SerializedName(value = "count", alternate = ["sum"])
    val count: Int? = null
)