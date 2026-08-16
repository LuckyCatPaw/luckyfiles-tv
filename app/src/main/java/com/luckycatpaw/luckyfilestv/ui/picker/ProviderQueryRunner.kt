package com.luckycatpaw.luckyfilestv.ui.picker

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import com.luckycatpaw.luckyfilestv.R
import com.luckycatpaw.luckyfilestv.data.provider.model.ProviderChildrenResult
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

internal class ProviderQueryRunner(context: Context) {
    private val appContext = context.applicationContext

    suspend fun queryUntilSettled(
        observedUri: Uri,
        query: suspend (CancellationSignal) -> Result<ProviderChildrenResult>,
        onUpdate: (ProviderChildrenResult) -> Unit
    ): ProviderQueryOutcome {
        var result: ProviderChildrenResult? = null

        repeat(MAX_LOADING_ATTEMPTS) { attempt ->
            currentCoroutineContext().ensureActive()
            val signal = CancellationSignal()
            val queryResult = try {
                try {
                    withTimeoutOrNull(QUERY_TIMEOUT) {
                        query(signal)
                    }
                } finally {
                    signal.cancel()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                return ProviderQueryOutcome(
                    result = result,
                    failure = error,
                    loadingTimedOut = false
                )
            } ?: run {
                return ProviderQueryOutcome(
                    result = result,
                    failure = IllegalStateException(
                        appContext.getString(R.string.provider_read_failed)
                    ),
                    loadingTimedOut = true
                )
            }

            queryResult.exceptionOrNull()?.let { failure ->
                return ProviderQueryOutcome(
                    result = result,
                    failure = failure,
                    loadingTimedOut = false
                )
            }

            result = queryResult.getOrThrow()
            onUpdate(result)

            if (!result.loading) {
                return ProviderQueryOutcome(
                    result = result,
                    failure = null,
                    loadingTimedOut = false
                )
            }

            if (attempt < MAX_LOADING_ATTEMPTS - 1) {
                withTimeoutOrNull(LOADING_RETRY_DELAY) {
                    awaitProviderChange(observedUri)
                }
            }
        }

        return ProviderQueryOutcome(
            result = result,
            failure = null,
            loadingTimedOut = result?.loading == true
        )
    }

    private suspend fun awaitProviderChange(uri: Uri) {
        suspendCancellableCoroutine { continuation ->
            lateinit var observer: ContentObserver
            val completed = AtomicBoolean(false)

            fun unregisterObserver() {
                runCatching {
                    appContext.contentResolver.unregisterContentObserver(observer)
                }
            }

            fun resumeOnce() {
                if (!completed.compareAndSet(false, true)) return
                unregisterObserver()
                if (continuation.isActive) continuation.resume(Unit)
            }

            observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) = resumeOnce()

                override fun onChange(selfChange: Boolean, changedUri: Uri?) {
                    resumeOnce()
                }
            }

            continuation.invokeOnCancellation {
                completed.set(true)
                unregisterObserver()
            }

            if (!continuation.isActive) {
                completed.set(true)
                return@suspendCancellableCoroutine
            }

            runCatching {
                appContext.contentResolver.registerContentObserver(uri, false, observer)
            }.onFailure {
                resumeOnce()
            }

            if (!continuation.isActive) unregisterObserver()
        }
    }

    private companion object {
        const val MAX_LOADING_ATTEMPTS = 6
        val LOADING_RETRY_DELAY = 750.milliseconds
        val QUERY_TIMEOUT = 8000.milliseconds
    }
}

internal data class ProviderQueryOutcome(
    val result: ProviderChildrenResult?,
    val failure: Throwable?,
    val loadingTimedOut: Boolean
)
