package com.pichs.download.api

/**
 * 拦截去
 */
fun interface IIntercept {

    fun chain(chain: DownloadChain?): IIntercept?

    companion object {
        /**
         * Constructs an interceptor for a lambda. This compact syntax is most useful for inline
         * interceptors.
         *
         * ```
         * val interceptor = Interceptor { chain: Interceptor.Chain ->
         *     chain.proceed(chain.request())
         * }
         * ```
         */
        inline operator fun invoke(crossinline block: (chain: DownloadChain?) -> IIntercept): IIntercept =
            IIntercept { block(it) }
    }

}

interface DownloadChain {


}