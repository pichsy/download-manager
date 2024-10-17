package com.pichs.shanhai.base.search

import java.io.Serializable

/**
 * @Description: 搜索类型
 */
enum class SearchType(var type: Int) {
    NONE(0),
    NOTE(1),
    TODO_LIST(2),
    TOOLS(3),
    APP(4),
    WEB(5),
    MUSIC(6),
    VIDEO(7),
    IMAGE(8),
    FILE(9),
    CONTACT(10),
    MESSAGE(11),
    CALENDAR(12),

    /**
     * 以下是首页的搜索类型
     */
    BANNER(100),
    HISTORY(101),
    COLLECTION(102),

}

/**
 * 搜索结果
 */
data class SearchResult(
    var data: Any? = null,
    var type: SearchType = SearchType.NONE,
) : Serializable {

    override fun toString(): String {
        return "SearchResult(data=${data.toString()}, type=$type)"
    }
}