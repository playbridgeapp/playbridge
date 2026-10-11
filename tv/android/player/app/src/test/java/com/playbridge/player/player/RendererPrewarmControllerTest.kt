package com.playbridge.player.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RendererPrewarmControllerTest {
    private class FakePlatform : RendererPrewarmController.Platform {
        class Binding(
            val kind: RendererProcessSupervisor.Kind,
            val connected: () -> Unit,
            val disconnected: () -> Unit,
            var released: Boolean = false,
        )
        class Scheduled(val delayMs: Long, val action: () -> Unit, var canceled: Boolean = false)
        val bindings = mutableListOf<Binding>()
        val scheduled = mutableListOf<Scheduled>()
        val killed = mutableListOf<RendererProcessSupervisor.Kind>()
        var failBind = false
        var disconnectOnUnbind = false

        override fun bind(
            kind: RendererProcessSupervisor.Kind,
            connected: () -> Unit,
            disconnected: () -> Unit,
        ): (() -> Unit)? {
            if (failBind) return null
            val binding = Binding(kind, connected, disconnected)
            bindings += binding
            return {
                binding.released = true
                if (disconnectOnUnbind) disconnected()
            }
        }

        override fun schedule(delayMs: Long, action: () -> Unit): () -> Unit {
            val task = Scheduled(delayMs, action)
            scheduled += task
            return { task.canceled = true }
        }

        override fun releaseProcess(kind: RendererProcessSupervisor.Kind) { killed += kind }

        fun runPending() {
            val tasks = scheduled.toList()
            scheduled.clear()
            tasks.filterNot { it.canceled }.forEach { it.action() }
        }
    }

    @Test fun startupWaitsForContextRecoveryThenWarmsBothRenderers() {
        val platform = FakePlatform()
        RendererPrewarmController(platform)
        assertTrue(platform.bindings.isEmpty())
        assertEquals(listOf(1500L, 1500L), platform.scheduled.map { it.delayMs })
        platform.runPending()
        assertEquals(RendererProcessSupervisor.Kind.entries.toSet(), platform.bindings.map { it.kind }.toSet())
    }

    @Test fun recoveredBrowserCancelsStartupWarmAndReleasesCachedProcesses() {
        val platform = FakePlatform()
        val controller = RendererPrewarmController(platform)
        val queued = platform.scheduled.toList()
        controller.setBrowserActive(true)
        assertTrue(queued.all { it.canceled })
        queued.forEach { it.action() }
        assertTrue(platform.bindings.isEmpty())
        assertEquals(RendererProcessSupervisor.Kind.entries.toSet(), platform.killed.toSet())
    }

    @Test fun browserReleasesBothBindingsBeforeDisconnectCanScheduleRewarm() {
        val platform = FakePlatform().apply { disconnectOnUnbind = true }
        val controller = RendererPrewarmController(platform)
        platform.runPending()
        controller.setBrowserActive(true)
        assertTrue(platform.bindings.all { it.released })
        assertTrue(platform.scheduled.isEmpty())
        platform.bindings.forEach { it.disconnected() }
        assertTrue(platform.scheduled.isEmpty())
        assertEquals(2, platform.killed.size)
    }

    @Test fun browserCancelsPendingRebindAfterRendererDeath() {
        val platform = FakePlatform()
        val controller = RendererPrewarmController(platform)
        platform.runPending()
        platform.bindings.first().disconnected()
        val retry = platform.scheduled.single()
        controller.setBrowserActive(true)
        assertTrue(retry.canceled)
        retry.action()
        assertEquals(2, platform.bindings.size)
    }

    @Test fun leavingBrowserResumesBothBindingsImmediately() {
        val platform = FakePlatform()
        val controller = RendererPrewarmController(platform)
        controller.setBrowserActive(true)
        controller.setBrowserActive(false)
        assertEquals(2, platform.bindings.size)
        assertFalse(platform.bindings.any { it.released })
        controller.setBrowserActive(false)
        assertEquals(2, platform.bindings.size)
    }

    @Test fun lateCallbacksCannotRetireReplacementBindingsAfterResume() {
        val platform = FakePlatform()
        val warmed = mutableListOf<RendererProcessSupervisor.Kind>()
        val controller = RendererPrewarmController(platform, onWarm = { warmed += it })
        platform.runPending()
        val old = platform.bindings.toList()
        controller.setBrowserActive(true)
        controller.setBrowserActive(false)
        old.forEach { it.disconnected(); it.connected() }
        assertTrue(warmed.isEmpty())
        assertEquals(4, platform.bindings.size)
        assertTrue(platform.bindings.takeLast(2).none { it.released })
        platform.bindings.takeLast(2).forEach { it.connected() }
        assertEquals(2, warmed.size)
    }

    @Test fun canceledStartupCallbacksCannotDuplicateResumedBindings() {
        val platform = FakePlatform()
        val controller = RendererPrewarmController(platform)
        val queued = platform.scheduled.toList()
        controller.setBrowserActive(true)
        controller.setBrowserActive(false)
        queued.forEach { it.action() }
        assertEquals(2, platform.bindings.size)
    }

    @Test fun normalDisconnectStillRewarmsAfterCooldownWithoutDuplicateRetries() {
        val platform = FakePlatform()
        RendererPrewarmController(platform)
        platform.runPending()
        val first = platform.bindings.first()
        first.disconnected()
        first.disconnected()
        assertTrue(first.released)
        assertEquals(1, platform.scheduled.size)
        assertEquals(1500L, platform.scheduled.single().delayMs)
        platform.runPending()
        assertEquals(3, platform.bindings.size)
        assertEquals(first.kind, platform.bindings.last().kind)
    }

    @Test fun failedBindingsRetryNormallyButNotWhileBrowserOwnsContext() {
        val platform = FakePlatform().apply { failBind = true }
        val controller = RendererPrewarmController(platform)
        platform.runPending()
        assertEquals(2, platform.scheduled.size)
        controller.setBrowserActive(true)
        platform.failBind = false
        platform.runPending()
        assertTrue(platform.bindings.isEmpty())
        controller.setBrowserActive(false)
        assertEquals(2, platform.bindings.size)
    }

    @Test fun repeatedBrowserAnnouncementsDoNotRepeatRelease() {
        val platform = FakePlatform()
        val controller = RendererPrewarmController(platform)
        controller.setBrowserActive(true)
        controller.setBrowserActive(true)
        assertEquals(2, platform.killed.size)
    }
}
