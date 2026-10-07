package com.playbridge.shared.logging

import kotlinx.coroutines.CancellationException

/**
 * [kotlin.runCatching] that does not swallow coroutine cancellation.
 *
 * [CancellationException] (including timeout cancellation) is rethrown. Every other failure is
 * logged at warn and returned as [Result.failure]. The original exception stays on the [Result];
 * only the logged copy is redacted.
 *
 * [message] and the failure text pass through [redactLogText] before [warn], so URLs, tokens, and
 * header values are not retained. [warn] defaults to the shared [logger].
 *
 * Not inline: shared is compiled for JVM 17 and the Android apps target JVM 11, which cannot
 * inline shared bytecode. Use [suspendRunCatchingLogged] when the block calls suspend functions.
 */
fun <T> runCatchingLogged(
    tag: String,
    message: String,
    warn: (tag: String, message: String, error: Throwable) -> Unit = ::logCaughtFailure,
    block: () -> T,
): Result<T> {
    return try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        logFailure(tag, message, error, warn)
        Result.failure(error)
    }
}

/**
 * Suspend counterpart of [runCatchingLogged]. Same cancellation, logging, and redaction contract.
 */
suspend fun <T> suspendRunCatchingLogged(
    tag: String,
    message: String,
    warn: (tag: String, message: String, error: Throwable) -> Unit = ::logCaughtFailure,
    block: suspend () -> T,
): Result<T> {
    return try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        logFailure(tag, message, error, warn)
        Result.failure(error)
    }
}

/** Default warn sink for [runCatchingLogged]. Public because Kotlin copies default arguments to callers. */
fun logCaughtFailure(tag: String, message: String, error: Throwable) {
    logger.w(tag, message, error)
}

/**
 * A log-safe copy of [this]: stack frames are kept, but messages and causes are redacted.
 * The original throwable is not mutated.
 */
fun Throwable.redactedForLog(): Throwable {
    val safe = RedactedLogThrowable(redactedFailureText(this), cause?.redactedForLog())
    safe.stackTrace = stackTrace.copyOf()
    for (suppressed in suppressed) {
        safe.addSuppressed(suppressed.redactedForLog())
    }
    return safe
}

private fun logFailure(
    tag: String,
    message: String,
    error: Throwable,
    warn: (tag: String, message: String, error: Throwable) -> Unit,
) {
    warn(tag, redactLogText(message), error.redactedForLog())
}

private fun redactedFailureText(error: Throwable): String {
    val name = error::class.simpleName ?: error::class.qualifiedName ?: "Throwable"
    val detail = error.message?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
    return redactLogText(name + detail)
}

private class RedactedLogThrowable(message: String, cause: Throwable?) : Exception(message, cause)
