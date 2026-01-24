package com.pichs.download.demo

import android.content.Context
import android.view.View
import android.view.animation.Animation
import com.pichs.download.demo.databinding.DialogDeleteConfirmBinding
import com.pichs.xbase.clickhelper.fastClick
import razerdp.basepopup.BasePopupWindow
import razerdp.util.animation.AnimationHelper
import razerdp.util.animation.ScaleConfig

/**
 * 删除确认弹窗
 */
class DeleteConfirmDialog(
    context: Context,
    private val onConfirm: () -> Unit
) : BasePopupWindow(context) {

    private lateinit var binding: DialogDeleteConfirmBinding

    init {
        setContentView(R.layout.dialog_delete_confirm)
    }

    override fun onViewCreated(contentView: View) {
        super.onViewCreated(contentView)
        binding = DialogDeleteConfirmBinding.bind(contentView)

        setOutSideDismiss(true)
        setBackPressEnable(true)

        binding.btnCancel.fastClick { dismiss() }
        binding.btnConfirm.fastClick {
            onConfirm.invoke()
            dismiss()
        }
    }

    override fun onCreateShowAnimation(): Animation {
        return AnimationHelper.asAnimation().withScale(ScaleConfig.CENTER).toShow()
    }

    override fun onCreateDismissAnimation(): Animation {
        return AnimationHelper.asAnimation().withScale(ScaleConfig.CENTER).toDismiss()
    }
}
