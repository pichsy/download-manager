package com.pichs.shanhai.base.ext

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

fun CoroutineScope.tryLaunch(
    context: CoroutineContext = EmptyCoroutineContext,
    onException: (suspend (Exception) -> Unit)? = null,
    block: suspend CoroutineScope.() -> Unit,
): Job {
    return launch(context) {
        try {
            block.invoke(this)
        } catch (e: Exception) {
            onException?.invoke(e)
        }
    }
}

suspend fun <T> tryWithContext(
    context: CoroutineContext = EmptyCoroutineContext,
    onException: (suspend (Exception) -> Unit)? = null,
    block: suspend CoroutineScope.() -> T,
): T? {
    return withContext(context) {
        try {
            return@withContext block.invoke(this)
        } catch (e: Exception) {
            onException?.invoke(e)
        }
        return@withContext null
    }
}

