package com.pichs.download.demo.floatwindow

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import com.pichs.xbase.kotlinext.dp2px

/**
 * 删除区域 View
 * 长按悬浮球时显示，拖入此区域可隐藏悬浮球
 */
class DeleteZoneView(context: Context) : View(context) {

    companion object {
        const val ZONE_WIDTH = 80  // dp
        const val ZONE_HEIGHT = 80 // dp
    }

    private val zoneWidth = ZONE_WIDTH.toFloat().dp2px.toInt()
    private val zoneHeight = ZONE_HEIGHT.toFloat().dp2px.toInt()

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val layoutParams: WindowManager.LayoutParams

    // 是否显示在左侧
    private var isOnLeft = false

    // 是否高亮（悬浮球进入区域）
    private var isHighlighted = false

    // 删除图标画笔
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 3f.dp2px
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }

    // 背景画笔
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC333333")
        style = Paint.Style.FILL
    }

    // 高亮背景画笔
    private val highlightBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CCFF4444")
        style = Paint.Style.FILL
    }

    private var currentScale = 0f
    private var animator: ValueAnimator? = null

    init {
        layoutParams = WindowManager.LayoutParams().apply {
            width = zoneWidth
            height = zoneHeight
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.START
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val cx = width / 2f
        val cy = height / 2f
        val radius = (minOf(width, height) / 2f - 10f.dp2px) * currentScale
        
        // 绘制背景圆
        canvas.drawCircle(cx, cy, radius, if (isHighlighted) highlightBgPaint else bgPaint)
        
        // 绘制 X 图标
        if (currentScale > 0.5f) {
            val iconSize = 12f.dp2px * currentScale
            canvas.drawLine(cx - iconSize, cy - iconSize, cx + iconSize, cy + iconSize, iconPaint)
            canvas.drawLine(cx + iconSize, cy - iconSize, cx - iconSize, cy + iconSize, iconPaint)
        }
    }

    /**
     * 显示删除区域
     * @param showOnLeft 是否显示在屏幕左侧
     */
    fun show(showOnLeft: Boolean) {
        isOnLeft = showOnLeft
        isHighlighted = false
        
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        
        layoutParams.x = if (showOnLeft) {
            20.toFloat().dp2px.toInt()
        } else {
            screenWidth - zoneWidth - 20.toFloat().dp2px.toInt()
        }
        layoutParams.y = (screenHeight - zoneHeight) / 2
        
        try {
            windowManager.addView(this, layoutParams)
            animateIn()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 隐藏删除区域
     */
    fun dismiss() {
        animator?.cancel()
        try {
            windowManager.removeView(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 设置高亮状态
     */
    fun setHighlighted(highlighted: Boolean) {
        if (isHighlighted != highlighted) {
            isHighlighted = highlighted
            invalidate()
        }
    }

    /**
     * 检查坐标是否在删除区域内
     */
    fun isInZone(x: Float, y: Float): Boolean {
        val zoneX = layoutParams.x.toFloat()
        val zoneY = layoutParams.y.toFloat()
        return x >= zoneX && x <= zoneX + zoneWidth &&
               y >= zoneY && y <= zoneY + zoneHeight
    }

    /**
     * 获取删除区域中心坐标
     */
    fun getZoneCenter(): Pair<Float, Float> {
        return Pair(
            layoutParams.x + zoneWidth / 2f,
            layoutParams.y + zoneHeight / 2f
        )
    }

    private fun animateIn() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300
            interpolator = OvershootInterpolator()
            addUpdateListener { anim ->
                currentScale = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }
}
