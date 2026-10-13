package com.playbridge.sender.cast.remote

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** A retained composition is not sufficient: Remote owns a connection only while STARTED. */
internal fun bindRemoteRouteLifecycle(lifecycle: Lifecycle, activate: () -> Unit, deactivate: () -> Unit): () -> Unit {
    var started: Boolean? = null
    fun update() {
        val next = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (started == next) return
        started = next
        if (next) activate() else deactivate()
    }
    val observer = LifecycleEventObserver { _, _ -> update() }
    lifecycle.addObserver(observer)
    update()
    return {
        lifecycle.removeObserver(observer)
        if (started == true) deactivate()
        started = false
    }
}

@Composable
internal fun RemoteRouteLifecycleEffect(owner: Any, activate: () -> Unit, deactivate: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val activateState = rememberUpdatedState(activate)
    val deactivateState = rememberUpdatedState(deactivate)
    DisposableEffect(owner, lifecycle) {
        val unbind = bindRemoteRouteLifecycle(lifecycle, { activateState.value() }, { deactivateState.value() })
        onDispose { unbind() }
    }
}
