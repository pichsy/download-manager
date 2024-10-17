package com.pichs.shanhai.base.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList

object SearchEngine {

    private val searchChainList = CopyOnWriteArrayList<ISearchChain>()

    fun registerSearchChain(searchChain: ISearchChain) {
        if (isContainsChain(searchChain)) {
            return
        }
        searchChainList.add(searchChain)
    }

    fun unregisterSearchChain(searchChain: ISearchChain) {
        searchChainList.remove(searchChain)
    }

    fun unregisterSearchChain(searchChainClassName: String) {
        for (chain in searchChainList) {
            if (chain.javaClass.name == searchChainClassName) {
                searchChainList.remove(chain)
                return
            }
        }
    }

    /**
     * 搜索
     */
    suspend fun search(key: String): MutableList<SearchResult> {
        return withContext(Dispatchers.IO) {
            val searchResultList = mutableListOf<SearchResult>()
            for (searchChain in searchChainList) {
                val searchResult = searchChain.search(key)
                if (!searchResult.isNullOrEmpty()) {
                    searchResultList.addAll(searchResult)
                }
            }
            return@withContext searchResultList
        }
    }


    private fun isContainsChain(searchChain: ISearchChain): Boolean {
        for (chain in searchChainList) {
            if (chain.javaClass.name == searchChain::class.java.name) {
                return true
            }
        }
        return false
    }

}