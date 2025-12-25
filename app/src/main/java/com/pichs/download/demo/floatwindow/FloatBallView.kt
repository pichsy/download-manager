package com.pichs.download.demo.floatwindow

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import com.pichs.xbase.kotlinext.dp
import com.pichs.xbase.kotlinext.dp2px
import com.pichs.xwidget.cardview.XCardConstraintLayout

/**
 * 悬浮球 View
 * 显示下载进度、网速、应用名
 * 支持拖动和长按删除
 */
class FloatBallView(context: Context) : XCardConstraintLayout(context) {

    companion object {
        private const val LONG_PRESS_TIMEOUT = 500L // 长按超时时间
    }

    private val ballSize = 72.dp // 增大尺寸以容纳更多内容
    private val touchSlop = 8f.dp2px

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val wmLayoutParams: WindowManager.LayoutParams

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var isDragging = false
    private var hasMoved = false
    private var isLongPressed = false

    // 长按处理
    private val handler = Handler(Looper.getMainLooper())
    private val longPressRunnable = Runnable {
        if (!hasMoved) {
            isLongPressed = true
            onLongPress()
        }
    }

    // 删除区域
    private var deleteZoneView: DeleteZoneView? = null

    // 子 View
    private lateinit var progressView: CircularProgressView
    private lateinit var tvSpeed: TextView
    private lateinit var tvAppName: TextView
    private lateinit var tvProgress: TextView
    private lateinit var ivIcon: ImageView

    // 点击监听
    private var onClickListener: (() -> Unit)? = null

    // 隐藏回调
    private var onDismissListener: (() -> Unit)? = null

    init {
        setNormalBackgroundColor(Color.parseColor("#E6222222")) // 深色半透明背景
        setRadiusAndShadow(ballSize / 2, 6f.dp2px.toInt(), 0.5f)

        setupViews()

        wmLayoutParams = WindowManager.LayoutParams().apply {
            width = ballSize + 12.dp // 阴影空间
            height = ballSize + 12.dp
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 300
        }
    }

    private fun setupViews() {
        // 圆形进度条
        progressView = CircularProgressView(context).apply {
            id = generateViewId()
        }
        addView(progressView, LayoutParams(0, 0).apply {
            topToTop = LayoutParams.PARENT_ID
            bottomToBottom = LayoutParams.PARENT_ID
            startToStart = LayoutParams.PARENT_ID
            endToEnd = LayoutParams.PARENT_ID
            setMargins(4.dp, 4.dp, 4.dp, 4.dp)
        })

        // 下载图标
        ivIcon = ImageView(context).apply {
            id = generateViewId()
            setImageResource(android.R.drawable.stat_sys_download)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        addView(ivIcon, LayoutParams(16.dp, 16.dp).apply {
            topToTop = LayoutParams.PARENT_ID
            startToStart = LayoutParams.PARENT_ID
            endToEnd = LayoutParams.PARENT_ID
            topMargin = 10.dp
        })

        // 网速
        tvSpeed = TextView(context).apply {
            id = generateViewId()
            setTextColor(Color.parseColor("#00BFFF")) // 蓝色
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            text = "0KB/s"
        }
        addView(tvSpeed, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            topToBottom = ivIcon.id
            startToStart = LayoutParams.PARENT_ID
            endToEnd = LayoutParams.PARENT_ID
            topMargin = 2.dp
        })

        // 应用名
        tvAppName = TextView(context).apply {
            id = generateViewId()
            setTextColor(Color.parseColor("#00BFFF"))
            textSize = 8f
            gravity = Gravity.CENTER
            maxLines = 1
            text = ""
        }
        addView(tvAppName, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            topToBottom = tvSpeed.id
            startToStart = LayoutParams.PARENT_ID
            endToEnd = LayoutParams.PARENT_ID
            topMargin = 1.dp
        })

        // 进度百分比
        tvProgress = TextView(context).apply {
            id = generateViewId()
            setTextColor(Color.parseColor("#00BFFF"))
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            text = "0%"
        }
        addView(tvProgress, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            topToBottom = tvAppName.id
            startToStart = LayoutParams.PARENT_ID
            endToEnd = LayoutParams.PARENT_ID
            topMargin = 1.dp
        })
    }

    /**
     * 更新下载进度
     */
    fun updateProgress(appName: String, progress: Int, speed: String) {
        tvAppName.text = appName
        tvProgress.text = "${progress}%"
        tvSpeed.text = speed
        progressView.setProgress(progress)
    }

    /**
     * 设置点击监听
     */
    fun setOnFloatClickListener(listener: () -> Unit) {
        onClickListener = listener
    }

    /**
     * 设置隐藏回调
     */
    fun setOnDismissListener(listener: () -> Unit) {
        onDismissListener = listener
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                lastX = event.rawX
                lastY = event.rawY
                isDragging = true
                hasMoved = false
                isLongPressed = false
                // 启动长按检测
                handler.postDelayed(longPressRunnable, LONG_PRESS_TIMEOUT)
            }

            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    val dx = event.rawX - lastX
                    val dy = event.rawY - lastY
                    wmLayoutParams.x += dx.toInt()
                    wmLayoutParams.y += dy.toInt()
                    windowManager.updateViewLayout(this, wmLayoutParams)
                    lastX = event.rawX
                    lastY = event.rawY

                    val totalDx = event.rawX - downX
                    val totalDy = event.rawY - downY
                    if (totalDx * totalDx + totalDy * totalDy > touchSlop * touchSlop) {
                        hasMoved = true
                        // 移动后取消长按检测
                        handler.removeCallbacks(longPressRunnable)
                    }

                    // 长按后检测是否进入删除区域
                    if (isLongPressed) {
                        checkDeleteZone(event.rawX, event.rawY)
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPressRunnable)
                isDragging = false

                if (isLongPressed) {
                    // 检查是否在删除区域内
                    if (deleteZoneView?.isInZone(event.rawX, event.rawY) == true) {
                        hideDeleteZone()
                        dismiss()
                        onDismissListener?.invoke()
                    } else {
                        hideDeleteZone()
                    }
                    isLongPressed = false
                } else if (!hasMoved) {
                    // 点击事件
                    onClickListener?.invoke()
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                isDragging = false
                hideDeleteZone()
                isLongPressed = false
            }
        }
        return true
    }

    private fun onLongPress() {
        // 判断悬浮球位置，决定删除区域显示在哪边
        val screenWidth = resources.displayMetrics.widthPixels
        val ballCenterX = wmLayoutParams.x + ballSize / 2
        val showOnLeft = ballCenterX > screenWidth / 2

        deleteZoneView = DeleteZoneView(context)
        deleteZoneView?.show(showOnLeft)
    }

    private fun checkDeleteZone(x: Float, y: Float) {
        deleteZoneView?.let { zone ->
            val inZone = zone.isInZone(x, y)
            zone.setHighlighted(inZone)
        }
    }

    private fun hideDeleteZone() {
        deleteZoneView?.dismiss()
        deleteZoneView = null
    }

    /**
     * 显示悬浮球
     */
    fun show() {
        try {
            windowManager.addView(this, wmLayoutParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 隐藏悬浮球
     */
    fun dismiss() {
        hideDeleteZone()
        try {
            windowManager.removeView(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 是否已显示
     */
    fun isShowing(): Boolean {
        return isAttachedToWindow
    }
}
