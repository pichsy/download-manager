package com.pichs.download.demo.widget

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.pichs.download.demo.R
import kotlin.math.abs
import androidx.core.content.withStyledAttributes

class CurvedShapeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.FILL
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }

    private val path = Path()

    // arcDepth支持正负，默认0f（无凹凸）
    private var arcDepth = 0f

    init {
        attrs?.let {
            context.withStyledAttributes(it, R.styleable.CurvedShapeView) {
                arcDepth = getDimension(R.styleable.CurvedShapeView_arcDepth, 0f)
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)

        measuredWidthF = width.toFloat()
        measuredHeightF = height.toFloat()
    }

    private var measuredWidthF = 0f
    private var measuredHeightF = 0f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = measuredWidthF
        val h = measuredHeightF

        if (w == 0f || h == 0f) return // 防止未测量完成绘制

        val reserveSpace = abs(arcDepth).coerceAtMost(h / 2f)
        val drawWidth = w - 2 * reserveSpace
        val maxDepth = drawWidth / 2f
        val adjustedArcDepth = arcDepth.coerceIn(-maxDepth, maxDepth)

        val controlX = reserveSpace + drawWidth - adjustedArcDepth
        val controlY = h / 2f

        path.reset()
        path.moveTo(reserveSpace, 0f)
        path.lineTo(reserveSpace + drawWidth, 0f)
        path.quadTo(controlX, controlY, reserveSpace + drawWidth, h)
        path.lineTo(reserveSpace, h)
        path.close()

        canvas.drawPath(path, fillPaint)
        canvas.drawPath(path, strokePaint)
    }

    // 动态修改arcDepth
    fun setArcDepth(depth: Float) {
        arcDepth = depth
        invalidate()
    }

    fun getArcDepth(): Float = arcDepth

    fun setFillColor(color: Int) {
        fillPaint.color = color
        invalidate()
    }

    fun setStrokeColor(color: Int) {
        strokePaint.color = color
        invalidate()
    }

    fun setStrokeWidth(width: Float) {
        strokePaint.strokeWidth = width
        invalidate()
    }
}
