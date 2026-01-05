package com.pichs.download.demo.widget

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.ViewOutlineProvider
import com.pichs.download.demo.R

class ProgressButton @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var backgroundColor = Color.LTGRAY
    private var progressColor = Color.BLUE
    private var cornerRadius = 20f
    private var textColor = Color.WHITE
    private var textSize = 48f
    private var buttonText = "Progress"
    private var progress = 0f // [0f, 1f]

    // 自动调整字体大小相关属性
    private var autoSizeMinTextSize = 0f // 0 表示未设置
    private var autoSizeMaxTextSize = 0f // 0 表示未设置
    private var horizontalPadding = 0f   // 水平内边距
    private var isAutoSizeEnabled = false
    private var cachedAutoTextSize = 0f  // 缓存计算后的字体大小

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // 用于测量文字宽度的临时 Paint
    private val measurePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        context.theme.obtainStyledAttributes(attrs, R.styleable.ProgressButton, 0, 0).apply {
            try {
                backgroundColor = getColor(R.styleable.ProgressButton_pb_backgroundColor, backgroundColor)
                progressColor = getColor(R.styleable.ProgressButton_pb_progressColor, progressColor)
                cornerRadius = getDimension(R.styleable.ProgressButton_pb_radius, cornerRadius)
                buttonText = getString(R.styleable.ProgressButton_android_text) ?: buttonText
                textColor = getColor(R.styleable.ProgressButton_android_textColor, textColor)
                textSize = getDimension(R.styleable.ProgressButton_android_textSize, textSize)
                
                // 自动调整字体大小属性
                autoSizeMinTextSize = getDimension(R.styleable.ProgressButton_pb_autoSizeMinTextSize, 0f)
                autoSizeMaxTextSize = getDimension(R.styleable.ProgressButton_pb_autoSizeMaxTextSize, 0f)
                horizontalPadding = getDimension(R.styleable.ProgressButton_pb_horizontalPadding, 0f)
                
                // 如果设置了 min 和 max，则启用自动调整
                isAutoSizeEnabled = autoSizeMinTextSize > 0 && autoSizeMaxTextSize > 0 && autoSizeMaxTextSize > autoSizeMinTextSize
            } finally {
                recycle()
            }
        }

        bgPaint.color = backgroundColor
        progressPaint.color = progressColor

        textPaint.color = textColor
        textPaint.textSize = textSize
        textPaint.textAlign = Paint.Align.CENTER

        measurePaint.textAlign = Paint.Align.CENTER

        // 启用硬件加速，以支持 clipPath 裁剪
        setLayerType(LAYER_TYPE_HARDWARE, null)

        // ✅ 开启圆角裁剪
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
            }
        }
        clipToOutline = true
        
        // 使按钮可点击
        isClickable = true
        isFocusable = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (isAutoSizeEnabled && w > 0) {
            recalculateAutoTextSize()
        }
    }

    /**
     * 重新计算自动调整后的字体大小
     * 使用二分查找找到最大的能完整显示文字的字体大小
     */
    private fun recalculateAutoTextSize() {
        if (!isAutoSizeEnabled || width <= 0) return
        
        val availableWidth = width - horizontalPadding * 2
        if (availableWidth <= 0) {
            cachedAutoTextSize = autoSizeMinTextSize
            textPaint.textSize = cachedAutoTextSize
            return
        }

        // 二分查找最合适的字体大小
        var low = autoSizeMinTextSize
        var high = autoSizeMaxTextSize
        var bestSize = autoSizeMinTextSize

        while (high - low > 0.5f) { // 精度 0.5px
            val mid = (low + high) / 2
            measurePaint.textSize = mid
            val textWidth = measurePaint.measureText(buttonText)
            
            if (textWidth <= availableWidth) {
                bestSize = mid
                low = mid
            } else {
                high = mid
            }
        }

        cachedAutoTextSize = bestSize
        textPaint.textSize = cachedAutoTextSize
    }

    fun setProgress(percent: Int) {
        val newProgress = (percent.coerceIn(0, 100)) / 100f
        if (progress == newProgress) return
        progress = newProgress
        invalidate()
    }

    fun getProgress(): Int {
        return (progress * 100).toInt()
    }

    fun setText(text: String) {
        if (buttonText == text) return
        buttonText = text
        if (isAutoSizeEnabled) {
            recalculateAutoTextSize()
        }
        invalidate()
    }

    fun getText(): String {
        return buttonText
    }

    fun setProgressColor(color: Int) {
        if (progressColor == color) return
        progressColor = color
        progressPaint.color = color
        invalidate()
    }

    fun setTextColor(color: Int) {
        if (textColor == color) return
        textColor = color
        textPaint.color = color
        invalidate()
    }

    /**
     * 设置自动调整字体大小的范围
     * @param minTextSizeSp 最小字体大小 (sp)
     * @param maxTextSizeSp 最大字体大小 (sp)
     */
    fun setAutoSizeTextRange(minTextSizeSp: Float, maxTextSizeSp: Float) {
        val newMin = sp2px(minTextSizeSp)
        val newMax = sp2px(maxTextSizeSp)
        if (autoSizeMinTextSize == newMin && autoSizeMaxTextSize == newMax) return
        autoSizeMinTextSize = newMin
        autoSizeMaxTextSize = newMax
        isAutoSizeEnabled = autoSizeMinTextSize > 0 && autoSizeMaxTextSize > 0 
                && autoSizeMaxTextSize > autoSizeMinTextSize
        if (isAutoSizeEnabled) {
            recalculateAutoTextSize()
        }
        invalidate()
    }

    /**
     * 设置水平内边距
     * @param paddingDp 内边距 (dp)
     */
    fun setHorizontalPadding(paddingDp: Float) {
        val newPadding = dp2px(paddingDp)
        if (horizontalPadding == newPadding) return
        horizontalPadding = newPadding
        if (isAutoSizeEnabled) {
            recalculateAutoTextSize()
        }
        invalidate()
    }

    /**
     * 禁用自动调整字体大小，使用固定字体大小
     * @param textSizeSp 字体大小 (sp)
     */
    fun setFixedTextSize(textSizeSp: Float) {
        val newSize = sp2px(textSizeSp)
        if (!isAutoSizeEnabled && textSize == newSize) return
        isAutoSizeEnabled = false
        textSize = newSize
        textPaint.textSize = textSize
        invalidate()
    }

    private fun sp2px(sp: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics
        )
    }

    private fun dp2px(dp: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRect(rect, bgPaint)

        val progressRect = RectF(0f, 0f, width * progress, height.toFloat())
        canvas.drawRect(progressRect, progressPaint)

        val xPos = width / 2f
        val yPos = (height / 2f) - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText(buttonText, xPos, yPos, textPaint)
    }
}