package com.playbridge.shared.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

class RunCatchingLoggedTest {
    @Test
    fun successPassesThroughWithoutLogging() {
        val warnings = mutableListOf<String>()

        val result = runCatchingLogged("Tag", "unused", warn = { _, message, _ -> warnings += message }) {
            42
        }

        assertEquals(42, result.getOrNull())
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun capturesOtherExceptionsAndLogsRedactedText() {
        val logged = mutableListOf<Pair<String, Throwable>>()
        val failure = IllegalStateException(
            "wrapper",
            IllegalStateException(
                "https://user:secret@media.example/video.mp4?token=dl-secret " +
                    "Authorization: Bearer abcdefgh token=raw-secret",
            ),
        )

        val result = runCatchingLogged(
            tag = "Tag",
            message = "load failed for https://cdn.example/movie.mkv?token=dl-secret",
            warn = { _, message, error -> logged += message to error },
        ) {
            throw failure
        }

        assertSame(failure, result.exceptionOrNull())
        assertRedacted(logged.single(), failure)
    }

    @Test
    fun rethrowsCancellationWithoutLogging() {
        val warnings = mutableListOf<String>()

        val thrown = assertFailsWith<CancellationException> {
            runCatchingLogged("Tag", "work failed", warn = { _, message, _ -> warnings += message }) {
                throw CancellationException("stop")
            }
        }

        assertEquals("stop", thrown.message)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun suspendVariantRethrowsCancellationAndPassesSuccess() = runTest {
        val warnings = mutableListOf<String>()

        assertFailsWith<TimeoutCancellationException> {
            suspendRunCatchingLogged(
                "Tag",
                "timed work failed",
                warn = { _, message, _ -> warnings += message },
            ) {
                withTimeout(1) { awaitCancellation() }
            }
        }

        val result = suspendRunCatchingLogged(
            "Tag",
            "suspended work failed",
            warn = { _, message, _ -> warnings += message },
        ) {
            delay(1)
            "ok"
        }

        assertEquals("ok", result.getOrThrow())
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun suspendVariantCapturesAndRedactsOtherFailures() = runTest {
        val logged = mutableListOf<Pair<String, Throwable>>()
        val failure = IllegalStateException("https://cdn.example/fail.m3u8?token=dl-secret")

        val result = suspendRunCatchingLogged(
            tag = "Tag",
            message = "suspended work failed",
            warn = { _, message, error -> logged += message to error },
        ) {
            delay(1)
            throw failure
        }

        assertSame(failure, result.exceptionOrNull())
        val (message, loggedError) = logged.single()
        assertEquals("suspended work failed", message)
        assertFalse(loggedError.message.orEmpty().contains("cdn.example"), loggedError.message)
        assertFalse(loggedError.message.orEmpty().contains("dl-secret"), loggedError.message)
        assertTrue(loggedError.stackTrace.contentEquals(failure.stackTrace))
    }

    private fun assertRedacted(logged: Pair<String, Throwable>, failure: Throwable) {
        val (message, loggedError) = logged
        assertEquals("load failed for https://<redacted>", message)
        assertFalse(loggedError === failure)
        assertFalse(loggedError.message.orEmpty().contains("secret"), loggedError.message)
        assertFalse(loggedError.message.orEmpty().contains("media.example"), loggedError.message)
        assertFalse(loggedError.cause?.message.orEmpty().contains("dl-secret"), loggedError.cause?.message)
        assertFalse(loggedError.cause?.message.orEmpty().contains("abcdefgh"), loggedError.cause?.message)
        assertFalse(loggedError.cause?.message.orEmpty().contains("raw-secret"), loggedError.cause?.message)
        assertTrue(loggedError.stackTrace.contentEquals(failure.stackTrace))
    }
}
