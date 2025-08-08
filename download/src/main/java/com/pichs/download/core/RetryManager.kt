package com.pichs.download.core

class RetryManager(
    private val maxRetries: Int = 3,
    private val retryDelays: List<Long> = listOf(1000, 3000, 5000)
) {
    suspend fun <T> retry(
        maxAttempts: Int = maxRetries,
        delays: List<Long> = retryDelays,
        block: suspend () -> T
    ): T {
        var lastError: Throwable? = null
        repeat(maxAttempts) { attempt ->
            try {
                return block()
            } catch (e: Throwable) {
                lastError = e
                val delayMs = delays.getOrNull(attempt) ?: delays.last()
                kotlinx.coroutines.delay(delayMs)
            }
        }
        throw lastError ?: IllegalStateException("Unknown error in retry")
    }
}
