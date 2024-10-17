package com.pichs.shanhai.base.consts

/**
 * @Description: 浏览器的字段参数定义ç
 */
object ChromeConst {
    //==================================================
    //=================== Key 类型 ======================
    //==================================================
    /**
     * intent字段KEY url地址
     */
    const val KEY_CONTENT = "content"

    /**
     * 标题
     */
    const val KEY_TITLE = "title"

    /**
     * 图片地址
     */
    const val KEY_IMAGE_URL = "imageUrl"

    /**
     * 是否展示Toolbar
     */
    const val KEY_SHOW_TOOLBAR = "showToolbar"

    /**
     * intent字段KEY url类型
     * 0，网址，1，其他（暂时没有，先预留）
     */
    const val KEY_CONTENT_TYPE = "contentType"
    //==================================================
    //=================== Value 类型 ====================
    //==================================================
    /**
     * url链接类型类型 0，普通
     */
    const val CONTENT_TYPE_URL = 0

    /**
     * HTMl 文本
     */
    const val CONTENT_TYPE_HTML = 1

    /**
     * assets目录下的html文件地址
     * 格式： file:///android_assets/xxx
     */
    const val CONTENT_TYPE_ASSETS = 2

    /**
     * 文件路径
     */
    const val CONTENT_TYPE_FILE_PATH = 3

    /**
     * 文本字符串
     */
    const val CONTENT_TYPE_PLAIN_TEXT = 4
    // =====================================================
    // =================   浏览器设置参数    ==================
    // =====================================================
    /**
     * 字体大小zoom梯度, 由小到大。
     */
    @JvmField
    val FONT_SIZE_ARRAY = intArrayOf(75, 100, 125, 150, 175)
}