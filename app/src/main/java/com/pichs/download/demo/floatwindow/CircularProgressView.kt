package com.pichs.download.demo.floatwindow

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import com.pichs.xbase.kotlinext.dp2px

/**
 * 圆形进度条 View
 * 用于悬浮球显示下载进度
 */
class CircularProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // 进度 0-100
    private var progress: Int = 0

    // 进度条宽度
    private val strokeWidth = 2f.dp2px

    // 背景环画笔
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = this@CircularProgressView.strokeWidth
        color = Color.parseColor("#33FFFFFF") // 半透明白色背景
        strokeCap = Paint.Cap.ROUND
    }

    // 进度环画笔
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = this@CircularProgressView.strokeWidth
        strokeCap = Paint.Cap.ROUND
    }

    // 渐变色：蓝紫 -> 浅红紫
    private val gradientColors = intArrayOf(
        Color.parseColor("#FF667EEA"),
        Color.parseColor("#FF9B59B6"),
        Color.parseColor("#FFF093FB"),
        Color.parseColor("#FF667EEA") // 闭环
    )

    private val rectF = RectF()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val padding = strokeWidth / 2
        rectF.set(padding, padding, w - padding, h - padding)
        
        // 设置渐变
        val centerX = w / 2f
        val centerY = h / 2f
        progressPaint.shader = SweepGradient(centerX, centerY, gradientColors, null)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        // 绘制背景环
        canvas.drawArc(rectF, 0f, 360f, false, bgPaint)
        
        // 绘制进度环（从顶部开始，即-90度）
        val sweepAngle = progress * 360f / 100f
        canvas.drawArc(rectF, -90f, sweepAngle, false, progressPaint)
    }

    /**
     * 设置进度
     * @param progress 0-100
     */
    fun setProgress(progress: Int) {
        this.progress = progress.coerceIn(0, 100)
        invalidate()
    }

    fun getProgress(): Int = progress
}
