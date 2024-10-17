package com.pichs.shanhai.base.base

import android.os.Bundle
import androidx.viewbinding.ViewBinding
import com.pichs.xbase.binding.BindingLazyFragment

abstract class BaseLazyFragment<ViewBinder : ViewBinding> : BindingLazyFragment<ViewBinder>() {

    override fun beforeOnCreateView(savedInstanceState: Bundle?) {
        super.beforeOnCreateView(savedInstanceState)

    }

}