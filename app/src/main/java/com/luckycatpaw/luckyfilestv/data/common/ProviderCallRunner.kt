package com.luckycatpaw.luckyfilestv.data.common

import android.os.CancellationSignal
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Runs a synchronous ContentResolver/DocumentsProvider call without making
 * coroutine cancellation wait for the remote provider to return.
 *
 * Cancellation immediately reaches both Android's CancellationSignal and the
 * worker thread. A broken third-party provider can still ignore both, but it
 * can no longer keep the caller suspended past its timeout.
 */
internal object ProviderCallRunner {
    private val executor = ThreadPoolExecutor(
        MAX_PROVIDER_THREADS,
        MAX_PROVIDER_THREADS,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(MAX_QUEUED_CALLS),
        { runnable ->
            Thread(runnable, "TVFM-ProviderCall").apply {
                isDaemon = true
            }
        },
        ThreadPoolExecutor.AbortPolicy()
    )

    suspend fun <T> run(cancellationSignal: CancellationSignal? = null, block: (CancellationSignal) -> T): T =
        suspendCancellableCoroutine { continuation ->
            val signal = cancellationSignal ?: CancellationSignal()

            val task = object : FutureTask<T>({ block(signal) }) {
                override fun done() {
                    if (!continuation.isActive || isCancelled) return

                    val result = try {
                        Result.success(get())
                    } catch (error: ExecutionException) {
                        Result.failure(error.cause ?: error)
                    } catch (_: java.util.concurrent.CancellationException) {
                        // The continuation cancellation handler already completed
                        // the coroutine and cancelled the Android operation.
                        return
                    } catch (error: Exception) {
                        Result.failure(error)
                    }

                    if (continuation.isActive) {
                        continuation.resumeWith(result)
                    }
                }
            }

            continuation.invokeOnCancellation {
                signal.cancel()
                task.cancel(true)
            }

            if (continuation.isActive) {
                try {
                    executor.execute(task)
                } catch (error: RejectedExecutionException) {
                    signal.cancel()
                    task.cancel(true)
                    if (continuation.isActive) {
                        continuation.resumeWith(Result.failure(error))
                    }
                }
            } else {
                signal.cancel()
                task.cancel(true)
            }
        }

    private const val MAX_PROVIDER_THREADS = 8
    private const val MAX_QUEUED_CALLS = 32
}
