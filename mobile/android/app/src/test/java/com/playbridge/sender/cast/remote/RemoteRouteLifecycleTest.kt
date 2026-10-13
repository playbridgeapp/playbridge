package com.playbridge.sender.cast.remote

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteRouteLifecycleTest {
    @Test fun retainedRouteDisconnectsOnStopAndReconnectsOnStart() {
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this)
        }
        val events = mutableListOf<Boolean>()
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        val dispose = bindRemoteRouteLifecycle(owner.lifecycle, { events += true }, { events += false })
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        assertEquals(listOf(false, true, false, true), events)
        dispose()
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        assertEquals(listOf(false, true, false, true, false), events)
    }
}
