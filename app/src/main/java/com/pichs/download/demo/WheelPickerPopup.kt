package com.pichs.download.demo

import android.content.Context
import android.view.View
import android.widget.TextView
import com.pichs.download.demo.databinding.PopupWheelPickerBinding
import com.pichs.xwidget.wheel.XWheelView
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

    private lateinit var binding: PopupWheelPickerBinding
    private var currentSelectedIndex = selectedIndex

    init {
        setContentView(R.layout.popup_wheel_picker)
    }

    override fun onViewCreated(contentView: View) {
        super.onViewCreated(contentView)
        binding = PopupWheelPickerBinding.bind(contentView)

        // 标题
        binding.tvTitle.text = title

        binding.wheelView.adapter = object : XWheelView.Adapter() {
            override fun getItemCount(): Int {
                return items.size
            }

            override fun getItem(position: Int): String {
                return items[position]
            }
        }

        binding.wheelView.currentItem = selectedIndex

        binding.wheelView.addOnItemSelectedListener { wheelView, index ->
            currentSelectedIndex = index
        }

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
