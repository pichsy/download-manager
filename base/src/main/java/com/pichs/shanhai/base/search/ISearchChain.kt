package com.pichs.shanhai.base.search

interface ISearchChain {
//
//    suspend fun getSearchType(): SearchType

    /**
     * 搜索
     */
    suspend fun search(key: String): MutableList<SearchResult>?

}