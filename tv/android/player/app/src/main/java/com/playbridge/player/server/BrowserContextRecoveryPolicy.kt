package com.playbridge.player.server

/** A recovery announcement can fill lost state, never replace an owner or pending launch. */
internal fun canRecoverExternalBrowserContext(context: String, launchPending: Boolean): Boolean =
    context == "idle" && !launchPending
