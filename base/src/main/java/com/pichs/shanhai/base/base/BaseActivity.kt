package com.pichs.shanhai.base.base

import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.core.view.updateLayoutParams
import androidx.viewbinding.ViewBinding
import com.pichs.shanhai.base.databinding.ActivityBaseBinding
import com.pichs.shanhai.base.utils.DayNightUtils
import com.pichs.shanhai.base.utils.NavigationBarUtils
import com.pichs.xbase.binding.BindingActivity
import com.pichs.xbase.binding.ViewBindingUtil
import com.pichs.xbase.utils.StatusBarUtils
import com.pichs.xwidget.view.XFrameLayout

abstract class BaseActivity<ViewBinder : ViewBinding> : BindingActivity<ViewBinder>() {

    protected var isFirstResume = true

    override fun onCreate(savedInstanceState: Bundle?) {
        onAddActivity()
        super.onCreate(savedInstanceState)
    }

    override fun beforeOnCreate(savedInstanceState: Bundle?) {
        super.beforeOnCreate(savedInstanceState)
    }

    private lateinit var baseBinding: ActivityBaseBinding

    override fun getContentView(): View? {
        binding = ViewBindingUtil.inflateWithGeneric(this, LayoutInflater.from(this))
        if (turnOnNavigationBarAuto()) {
            baseBinding = ActivityBaseBinding.inflate(layoutInflater, null, false).apply {
                flContainer.addView(binding.root)
                if (turnOnNavigationBarAuto() && NavigationBarUtils.hasNavigationBar(this@BaseActivity)) {
                    navigationBar.updateLayoutParams {
                        height = NavigationBarUtils.getNavigationBarHeight()
                    }
                    navigationBar.visibility = View.VISIBLE
                    onNavigationBarCreated(navigationBar)
                } else {
                    navigationBar.visibility = View.GONE
                }
            }
            return baseBinding.root
        } else {
            return binding.root
        }
    }

    open fun turnOnNavigationBarAuto(): Boolean {
        return true
    }

    open fun onNavigationBarCreated(navigationBar: XFrameLayout) {

    }

    override fun onResume() {
        super.onResume()
        if (isFirstResume) {
            isFirstResume = false
        }
        resetNavigationBar()
    }

    private fun resetNavigationBar() {
        try {
            if (turnOnNavigationBarAuto() && this::baseBinding.isInitialized && NavigationBarUtils.hasNavigationBar(this@BaseActivity)) {
                baseBinding.navigationBar.updateLayoutParams {
                    height = NavigationBarUtils.getNavigationBarHeight()
                }
                baseBinding.navigationBar.visibility = View.VISIBLE
            } else {
                baseBinding.navigationBar.visibility = View.GONE
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onCreate() {
        super.onCreate()
        onSystemUISettings()
    }

    /**
     * 设置沉浸式状态栏
     */
    open fun onSystemUISettings() {
        StatusBarUtils.immersiveStatusBar(this)
        DayNightUtils.setStatusBarFontByDayNightMode(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        onSystemUISettings()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        resetNavigationBar()
    }

}