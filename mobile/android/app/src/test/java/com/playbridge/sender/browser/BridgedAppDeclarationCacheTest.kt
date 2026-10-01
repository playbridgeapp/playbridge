package com.playbridge.sender.browser

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgedAppDeclarationCacheTest {
    @Test
    fun `shares checks and removes the opt out when a declaration expires`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var time = 0L
            var calls = 0
            var changes = 0
            var result = CompletableDeferred<Boolean>()
            val cache = BridgedAppDeclarationCache(scope, { calls++; result.await() },
                { changes++ }, now = { time }, lifetimeMs = 10)
            val origin = "https://app.example"
            val pending = cache.refresh(origin)!!
            assertNull(cache.refresh(origin))
            assertEquals(1, calls)
            result.complete(true)
            pending.join()
            assertTrue(cache.isDeclared(origin))
            assertFalse(cache.isDeclared("https://other.example"))
            assertEquals(setOf(origin), cache.declaredOrigins())
            assertNull(cache.refresh(origin))
            time = 11
            result = CompletableDeferred(false)
            cache.refresh(origin)!!.join()
            assertFalse(cache.isDeclared(origin))
            assertTrue(cache.declaredOrigins().isEmpty())
            assertEquals(2, changes)
            assertNull(cache.refresh(origin))
        } finally { scope.cancel() }
    }

    @Test
    fun `failed checks are cached without disabling an ordinary website`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var calls = 0
            val cache = BridgedAppDeclarationCache(scope, { calls++; error("unavailable") },
                { error("negative results must not publish a policy change") })
            cache.refresh("https://ordinary.example")!!.join()
            assertFalse(cache.isDeclared("https://ordinary.example"))
            assertNull(cache.refresh("https://ordinary.example"))
            assertEquals(1, calls)
        } finally { scope.cancel() }
    }
}
