package com.pichs.download.demo.widget

import android.R.attr.textColor
import android.R.attr.textSize
import android.annotation.SuppressLint
import android.view.Gravity
import com.pichs.xwidget.cardview.XCardFrameLayout
import com.pichs.xwidget.progressbar.XProgressBar
import com.pichs.xwidget.progressbar.XProgressBar.TYPE_RECT
import com.pichs.xwidget.view.XTextView
//
//class ProgressButton @JvmOverloads constructor(
//    context: android.content.Context, attrs: android.util.AttributeSet? = null, defStyleAttr: Int = 0
//) : XCardFrameLayout(context, attrs, defStyleAttr) {
//
//    private lateinit var progressBar: XProgressBar
//    private lateinit var textView: XTextView
//
//    init {
//        initView(context, attrs, defStyleAttr)
//    }
//
//    private fun initView(context: Context, attrs: AttributeSet?, defStyleAttr: Int) {
//        progressBar = XProgressBar(context).apply {
//            maxValue = 100
//            setType(TYPE_RECT)
//            setStrokeRoundCap(false)
//            setBackgroundColor(Color.parseColor("#00000000"))
//            setProgressColor(Color.parseColor("#467CFD"))
//        }
//        addView(progressBar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
//            gravity = Gravity.CENTER
//        })
//
//        textView = XTextView(context).apply {
//            gravity = Gravity.CENTER
//            setTextColor(Color.parseColor("#467CFD"))
//            setText("下载")
//            setTextSize(14f)
//        }
//        addView(textView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
//            gravity = Gravity.CENTER
//        })
//    }
//
//
//    @SuppressLint("SetTextI18n")
//    fun setProgress(progress: Int) {
//        if (!::progressBar.isInitialized) {
//            // 没初始化，搞毛啊
//            return
//        }
//        progressBar.setProgress(progress, false)
//    }
//
//
//    fun setText(text: String) {
//        if (!::textView.isInitialized) {
//            // 没初始化，搞毛啊
//            return
//        }
//        textView.text = text
//    }
//
//    fun getText(): String {
//        if (!::textView.isInitialized) {
//            // 没初始化，搞毛啊
//            return ""
//        }
//        return textView.text.toString()
//    }
//
//    fun setProgressBarColor(color: Int) {
//        if (!::progressBar.isInitialized) {
//            // 没初始化，搞毛啊
//            return
//        }
//        progressBar.setProgressColor(color)
//    }
//
//    fun setProgressBarBackgroundColor(color: Int) {
//        if (!::progressBar.isInitialized) {
//            // 没初始化，搞毛啊
//            return
//        }
//        progressBar.setBackgroundColor(color)
//    }
//
//    fun setTextColor(color: Int) {
//        if (!::textView.isInitialized) {
//            // 没初始化，搞毛啊
//            return
//        }
//        textView.setTextColor(color)
//    }
//
//}

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
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

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val clipPath = Path()
    private val clipRect = RectF()

    init {
        context.theme.obtainStyledAttributes(attrs, R.styleable.ProgressButton, 0, 0).apply {
            try {
                backgroundColor = getColor(R.styleable.ProgressButton_pb_backgroundColor, backgroundColor)
                progressColor = getColor(R.styleable.ProgressButton_pb_progressColor, progressColor)
                cornerRadius = getDimension(R.styleable.ProgressButton_xp_radius, cornerRadius)
                buttonText = getString(R.styleable.ProgressButton_android_text) ?: buttonText
                textColor = getColor(R.styleable.ProgressButton_android_textColor, textColor)
                textSize = getDimension(R.styleable.ProgressButton_android_textSize, textSize)
            } finally {
                recycle()
            }
        }

        bgPaint.color = backgroundColor
        progressPaint.color = progressColor

        textPaint.color = textColor
        textPaint.textSize = textSize
        textPaint.textAlign = Paint.Align.CENTER

        // 启用硬件加速，以支持 clipPath 裁剪
        setLayerType(LAYER_TYPE_HARDWARE, null)

        // ✅ 开启圆角裁剪
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
            }
        }
        clipToOutline = true

    }

    fun setProgress(percent: Int) {
        progress = (percent.coerceIn(0, 100)) / 100f
        invalidate()
    }

    fun getProgress(): Int {
        return (progress * 100).toInt()
    }

    fun setText(text: String) {
        buttonText = text
        invalidate()
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