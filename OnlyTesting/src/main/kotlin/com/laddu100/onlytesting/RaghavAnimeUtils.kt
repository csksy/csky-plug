package com.laddu100.onlytesting

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext

suspend fun runLimitedAsync(concurrency: Int, vararg blocks: suspend () -> Unit) {
    val semaphore = Semaphore(concurrency)
    coroutineScope {
        blocks.map { block ->
            async {
                semaphore.acquire()
                try {
                    block()
                } finally {
                    semaphore.release()
                }
            }
        }.awaitAll()
    }
}
