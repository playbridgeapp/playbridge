package com.playbridge.sender.cast

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal enum class ThumbnailRequestPriority {
    PREFETCH,
    VISIBLE,
}

private data class ThumbnailFailure(
    val failedAtMs: Long,
    val priority: ThumbnailRequestPriority,
)

private sealed interface ThumbnailCompletion<out V> {
    data class Finished<V>(
        val value: V?,
        val priority: ThumbnailRequestPriority,
    ) : ThumbnailCompletion<V>
    data object Aborted : ThumbnailCompletion<Nothing>
}

private sealed interface ThumbnailPermit<out V> {
    data class Own<V>(
        val completion: CompletableDeferred<ThumbnailCompletion<V>>,
    ) : ThumbnailPermit<V>

    data class Join<V>(
        val completion: CompletableDeferred<ThumbnailCompletion<V>>,
    ) : ThumbnailPermit<V>

    data object CoolingDown : ThumbnailPermit<Nothing>
}

/**
 * Coalesces thumbnail work by key and gives visible rows one immediate retry after a speculative
 * prefetch failure. Failures requested by the UI use a cooldown so recomposition cannot create a
 * request loop.
 */
internal class ThumbnailRequestCoordinator<K : Any, V : Any>(
    private val retryCooldownMs: Long = 30_000L,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val stateMutex = Mutex()
    private val inFlight = mutableMapOf<K, CompletableDeferred<ThumbnailCompletion<V>>>()
    private val failures = object : LinkedHashMap<K, ThumbnailFailure>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<K, ThumbnailFailure>): Boolean =
            size > 100
    }

    suspend fun run(
        key: K,
        priority: ThumbnailRequestPriority,
        loader: suspend () -> V?,
    ): V? {
        while (true) {
            val permit: ThumbnailPermit<V> = stateMutex.withLock {
                val failure = failures[key]
                if (failure != null && !canAttempt(failure, priority)) {
                    ThumbnailPermit.CoolingDown
                } else {
                    val existing = inFlight[key]
                    if (existing != null) {
                        ThumbnailPermit.Join(existing)
                    } else {
                        val completion = CompletableDeferred<ThumbnailCompletion<V>>()
                        inFlight[key] = completion
                        ThumbnailPermit.Own(completion)
                    }
                }
            }

            when (permit) {
                ThumbnailPermit.CoolingDown -> return null
                is ThumbnailPermit.Join -> when (val completion = permit.completion.await()) {
                    ThumbnailCompletion.Aborted -> continue
                    is ThumbnailCompletion.Finished -> {
                        if (completion.value == null &&
                            priority == ThumbnailRequestPriority.VISIBLE &&
                            completion.priority == ThumbnailRequestPriority.PREFETCH) {
                            continue
                        }
                        return completion.value
                    }
                }
                is ThumbnailPermit.Own -> {
                    try {
                        val value = loader()
                        stateMutex.withLock {
                            if (value == null) {
                                failures[key] = ThumbnailFailure(nowMs(), priority)
                            } else {
                                failures.remove(key)
                            }
                            if (inFlight[key] === permit.completion) inFlight.remove(key)
                            permit.completion.complete(
                                ThumbnailCompletion.Finished(value, priority),
                            )
                        }
                        return value
                    } catch (cancelled: CancellationException) {
                        // A visible waiter can take ownership instead of inheriting cancellation
                        // from a tab-scoped prefetch that was just invalidated by navigation.
                        withContext(NonCancellable) {
                            stateMutex.withLock {
                                if (inFlight[key] === permit.completion) inFlight.remove(key)
                                permit.completion.complete(ThumbnailCompletion.Aborted)
                            }
                        }
                        throw cancelled
                    } catch (failure: Throwable) {
                        withContext(NonCancellable) {
                            stateMutex.withLock {
                                if (inFlight[key] === permit.completion) inFlight.remove(key)
                                permit.completion.completeExceptionally(failure)
                            }
                        }
                        throw failure
                    }
                }
            }
        }
    }

    private fun canAttempt(
        failure: ThumbnailFailure,
        priority: ThumbnailRequestPriority,
    ): Boolean {
        if (priority == ThumbnailRequestPriority.VISIBLE &&
            failure.priority == ThumbnailRequestPriority.PREFETCH) {
            return true
        }
        return nowMs() - failure.failedAtMs >= retryCooldownMs
    }
}
