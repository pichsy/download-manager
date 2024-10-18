package com.pichs.download.intercept

import com.pichs.download.api.DownloadChain
import com.pichs.download.api.IIntercept

class ProgressIntercept : IIntercept {

    override fun chain(chain: DownloadChain?): IIntercept? {
        return null
    }

}