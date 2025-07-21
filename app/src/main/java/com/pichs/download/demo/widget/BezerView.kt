package com.pichs.download.demo.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

class BezerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        strokeWidth = 8f
        style = Paint.Style.STROKE
    }

    // 矩形边界
    private var rectLeft = 100f
    private var rectTop = 200f
    private var rectRight = 600f
    private var rectBottom = 500f

    // 控制点数组：floatArrayOf(x1, y1, x2, y2)
    private var leftBezier = floatArrayOf(100f, 250f, 100f, 450f)
    private var rightBezier = floatArrayOf(600f, 250f, 600f, 450f)
    val path = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        super.onDraw(canvas);
        val startX = 100f
        val startY = 100f
        val width = 150f
        val height = 170f
        val curveDepth = 10f // 向左凹进去的深度

        path.reset()
        path.moveTo(startX, startY) // 左上角
        path.lineTo(startX + width, startY) // 右上角


        // 使用贝塞尔曲线向左凹
        val controlX = startX + width - curveDepth // 控制点向左偏
        val controlY = startY + height / 2f // 中心点向下

        path.quadTo(controlX, controlY, startX + width, startY + height) // 到右下角

        path.lineTo(startX, startY + height) // 左下角
        path.close()

        canvas.drawPath(path, paint)

    }

    /**
     * 通过类似 cubic-bezier(x1, y1, x2, y2) 的方式设置曲线形状
     * 控制点范围：0~1，偏移量 offsetX 决定曲线“鼓包”水平程度
     */
    fun setBezierByCubic(
        leftCubic: FloatArray,
        rightCubic: FloatArray,
        offsetX: Float = 100f
    ) {
        val h = rectBottom - rectTop

        // 左边：从 top 到 bottom
        leftBezier = floatArrayOf(
            rectLeft + (leftCubic[0] - 0.5f) * offsetX, rectTop + h * leftCubic[1],
            rectLeft + (leftCubic[2] - 0.5f) * offsetX, rectTop + h * leftCubic[3]
        )

        // 右边：从 top 到 bottom
        rightBezier = floatArrayOf(
            rectRight + (rightCubic[0] - 0.5f) * offsetX, rectTop + h * rightCubic[1],
            rectRight + (rightCubic[2] - 0.5f) * offsetX, rectTop + h * rightCubic[3]
        )

        invalidate()
    }

    /**
     * 可选：设置矩形范围
     */
    fun setRect(left: Float, top: Float, right: Float, bottom: Float) {
        rectLeft = left
        rectTop = top
        rectRight = right
        rectBottom = bottom
        invalidate()
    }
}