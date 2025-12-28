package com.pichs.download.demo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pichs.download.demo.ui.AppStoreFragment.Companion.TYPE_MUST_DOWNLOAD
import com.pichs.download.demo.ui.AppStoreFragment.Companion.TYPE_USER_DOWNLOAD
import com.pichs.shanhai.base.api.ShanHaiApi
import com.pichs.shanhai.base.api.UpdateAppBody
import com.pichs.shanhai.base.api.entity.UpdateAppInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object AppStoreViewModel : ViewModel() {

    private val _appListInfoFlow = MutableStateFlow(mutableListOf<UpdateAppInfo>())
    val appListFlow= _appListInfoFlow.asStateFlow()

    fun loadUpdateAppList(type: Int = TYPE_MUST_DOWNLOAD) {
        viewModelScope.launch {
            try {
                val response = ShanHaiApi.getApi().loadUpdateAppList(
                    UpdateAppBody(
                        type = 0,
                        category_type = if (TYPE_USER_DOWNLOAD == type) "2" else "1,3"
                    )
                )
                response?.result?.data?.let {
                    _appListInfoFlow.value = it
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }


}