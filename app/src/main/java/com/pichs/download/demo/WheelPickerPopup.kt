package com.pichs.download.demo

import android.content.Context
import android.view.View
import android.widget.TextView
import com.aigestudio.wheelpicker.WheelPicker
import razerdp.basepopup.BasePopupWindow

/**
 * 滚轮选择器弹窗
 */
class WheelPickerPopup(
    private val context: Context,
    private val title: String,
    private val items: List<String>,
    private val selectedIndex: Int = 0,
    private val onConfirm: (index: Int, item: String) -> Unit
) : BasePopupWindow(context) {

    private var currentSelectedIndex = selectedIndex

    init {
        setContentView(R.layout.popup_wheel_picker)
    }

    override fun onViewCreated(contentView: View) {
        super.onViewCreated(contentView)

        // 标题
        val tvTitle = findViewById<TextView>(R.id.tv_title)
        tvTitle.text = title

        // 滚轮选择器
        val wheelPicker = findViewById<WheelPicker>(R.id.wheel_picker)
        wheelPicker.data = items
        wheelPicker.selectedItemPosition = selectedIndex
        wheelPicker.setOnItemSelectedListener(object : WheelPicker.OnItemSelectedListener {
            override fun onItemSelected(picker: WheelPicker, data: Any?, position: Int) {
                currentSelectedIndex = position
            }
        })

        // 取消按钮
        val tvCancel = findViewById<TextView>(R.id.tv_cancel)
        tvCancel.setOnClickListener {
            dismiss()
        }

        // 确定按钮
        val tvConfirm = findViewById<TextView>(R.id.tv_confirm)
        tvConfirm.setOnClickListener {
            onConfirm(currentSelectedIndex, items[currentSelectedIndex])
            dismiss()
        }
    }
}
