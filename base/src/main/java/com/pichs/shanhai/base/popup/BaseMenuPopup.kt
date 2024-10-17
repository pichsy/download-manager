package com.pichs.shanhai.base.popup

import android.content.Context
import android.os.Parcelable
import android.view.Gravity
import android.view.View
import com.drake.brv.utils.linear
import com.drake.brv.utils.models
import com.drake.brv.utils.setup
import com.pichs.shanhai.base.R
import com.pichs.shanhai.base.databinding.BasePopupMenuBinding
import com.pichs.shanhai.base.databinding.BasePopupMenuItemLayoutBinding
import com.pichs.xbase.kotlinext.setItemAnimatorDisable
import kotlinx.parcelize.Parcelize
import razerdp.basepopup.BasePopupWindow

@Parcelize
data class BaseMenuBean(
    val icon: Int = 0, val title: String = "", val tag: String = ""
) : Parcelable

class BaseMenuPopup(
    context: Context,
    menuList: MutableList<BaseMenuBean> = mutableListOf(),
    private val isShowArrow: Boolean = true,
    private var onMenuClick: (BaseMenuBean) -> Unit
) :
    BasePopupWindow(context) {
    private lateinit var binding: BasePopupMenuBinding
    private val mMenuList: MutableList<BaseMenuBean> = mutableListOf()

    init {
        mMenuList.clear()
        mMenuList.addAll(menuList)
        setContentView(R.layout.base_popup_menu)
    }

    override fun onViewCreated(contentView: View) {
        super.onViewCreated(contentView)
        binding = BasePopupMenuBinding.bind(contentView)
        setPopupGravity(GravityMode.RELATIVE_TO_ANCHOR, Gravity.CENTER or Gravity.BOTTOM)

        binding.ivArrow.visibility = if (isShowArrow) View.VISIBLE else View.GONE
        initRecyclerView()

    }

    private fun initRecyclerView() {
        binding.rvMenu.linear().setItemAnimatorDisable().setup {
            addType<BaseMenuBean>(R.layout.base_popup_menu_item_layout)
            onBind {
                val item = getModel<BaseMenuBean>()
                val itemBinding = getBinding<BasePopupMenuItemLayoutBinding>()

                itemBinding.tvTitle.text = item.title
                itemBinding.llRoot.setOnClickListener {
                    dismiss()
                    onMenuClick.invoke(item)
                }
            }
        }.models = mMenuList
    }


    fun setMenuList(menuList: MutableList<BaseMenuBean>) {
        this.mMenuList.clear()
        this.mMenuList.addAll(menuList)
        binding.rvMenu.post {
            binding.rvMenu.models = mMenuList
        }
    }

}