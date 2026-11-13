package com.playbridge.shared.together

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.put

/** JNI surface of `cast/ffi/src/together.rs`. */
internal object TogetherNative {
    init {
        System.loadLibrary("playbridge_cast_core_ffi")
    }

    external fun abiVersion(): Int
    external fun start(): Long
    external fun submitJson(handle: Long, commandJson: String): Boolean
    external fun nextEvent(handle: Long, waitMs: Long): String?
    external fun cancel(handle: Long)
    external fun free(handle: Long)
}

interface TogetherNativeBoundary {
    fun abiVersion(): Int
    fun start(): Long
    fun submitJson(handle: Long, commandJson: String): Boolean
    fun nextEvent(handle: Long, waitMs: Long): String?
    fun cancel(handle: Long)
    fun free(handle: Long)
}

class RustTogetherBoundary : TogetherNativeBoundary {
    override fun abiVersion(): Int = TogetherNative.abiVersion()
    override fun start(): Long = TogetherNative.start()
    override fun submitJson(handle: Long, commandJson: String): Boolean = TogetherNative.submitJson(handle, commandJson)
    override fun nextEvent(handle: Long, waitMs: Long): String? = TogetherNative.nextEvent(handle, waitMs)
    override fun cancel(handle: Long) = TogetherNative.cancel(handle)
    override fun free(handle: Long) = TogetherNative.free(handle)
}

/**
 * One Watch Together session: at most one room and one receiver pairing code.
 * [events] has a single collector. Command JSON holds room keys and sign-in
 * headers, so it is never logged.
 */
class TogetherClient(
    private val native: TogetherNativeBoundary,
    scope: CoroutineScope,
) {
    private val channel = Channel<TogetherEvent>(Channel.UNLIMITED)
    private val lock = Any()
    @Volatile private var handle = native.start()

    val events: Flow<TogetherEvent> = channel.receiveAsFlow()

    init {
        check(handle != 0L) { "Watch Together could not start" }
        check(native.abiVersion() == ABI_VERSION) { "Watch Together library mismatch" }
        val started = handle
        // The pump is the only caller of nextEvent and the only one that frees
        // the handle, so a free never overlaps a native call.
        scope.launch(Dispatchers.IO) {
            try {
                while (isActive && handle == started) {
                    val text = native.nextEvent(started, POLL_MS) ?: continue
                    TogetherJson.event(text)?.let { channel.trySend(it) }
                }
            } finally {
                synchronized(lock) {
                    handle = 0L
                    native.cancel(started)
                    native.free(started)
                }
                channel.close()
            }
        }
    }

    fun host(relay: String, device: TogetherDevice) = submit(TogetherJson.command("host") {
        put("relay", relay)
        TogetherJson.device(this, device)
    })

    fun join(invite: String, device: TogetherDevice) = submit(TogetherJson.command("join") {
        put("invite", invite)
        TogetherJson.device(this, device)
    })

    /** Receiver: show a code and wait for an invite. */
    fun pairRegister(pairing: String) = submit(TogetherJson.command("pair_register") { put("pairing", pairing) })

    /**
     * Receiver: join the invite from the last [TogetherEvent.PairingInvite]. A [guest]
     * isn't counted as the inviting phone's own device, so it gets none of its sign-ins.
     */
    fun pairAccept(device: TogetherDevice, guest: Boolean = false) = submit(TogetherJson.command("pair_accept") {
        TogetherJson.device(this, device)
        if (guest) put("guest", true)
    })

    fun pairCancel() = submit(TogetherJson.command("pair_cancel"))

    /** Sender: invite a receiver by its code or the link in its QR code. */
    fun pairSend(pairing: String, target: String, id: String? = null) = submit(TogetherJson.command("pair_send") {
        put("pairing", pairing)
        put("target", target)
        id?.let { put("id", it) }
    })

    /** What the local player is doing; send it about once a second and after every change. */
    fun snapshot(positionMs: Long, playing: Boolean, buffering: Boolean) = submit(TogetherJson.command("snapshot") {
        put("position_ms", positionMs)
        put("playing", playing)
        put("buffering", buffering)
    })

    /** The player finished loading [item] (paused). */
    fun loaded(item: String, durationMs: Long?) = submit(TogetherJson.command("loaded") {
        put("item", item)
        durationMs?.takeIf { it > 0 }?.let { put("duration_ms", it) }
    })

    fun load(item: TogetherItem) = submit(TogetherJson.command("load") { put("item", TogetherJson.item(item)) })
    fun requestPlay(item: TogetherItem) = submit(TogetherJson.command("request_play") { put("item", TogetherJson.item(item)) })
    fun play() = submit(TogetherJson.command("play"))
    fun pause() = submit(TogetherJson.command("pause"))
    fun seek(positionMs: Long) = submit(TogetherJson.command("seek") { put("position_ms", positionMs) })
    fun lock(locked: Boolean) = submit(TogetherJson.command("lock") { put("locked", locked) })
    fun policy(policy: TogetherPolicy) = submit(TogetherJson.command("policy") { put("policy", policy.wire) })

    fun reply(request: String, allowed: Boolean, always: Boolean = false) = submit(TogetherJson.command("reply") {
        put("request", request)
        put("allowed", allowed)
        put("always", always)
    })

    /** Hand host duties to one of this phone's own devices. */
    fun delegate(member: String, signKey: String) = submit(TogetherJson.command("delegate") {
        put("member", member)
        put("sign_pk", signKey)
    })

    /** A device this phone added over its paired LAN connection. */
    fun addOwn(member: String, boxKey: String) = submit(TogetherJson.command("add_own") {
        put("member", member)
        put("box_pk", boxKey)
    })

    fun continueWithout(member: String) = submit(TogetherJson.command("continue_without") { put("member", member) })
    fun shareCredentials(member: String) = submit(TogetherJson.command("share_credentials") { put("member", member) })
    /** Starts or stops playing the video on this device; starting loads it where the party is. */
    fun setScreen(screen: Boolean) = submit(TogetherJson.command("screen") { put("screen", screen) })
    fun leave() = submit(TogetherJson.command("leave"))

    /** Leaves any room; the native handle is released once the event pump stops. */
    fun close() {
        synchronized(lock) {
            val current = handle
            if (current == 0L) return
            handle = 0L
            native.cancel(current)
        }
    }

    private fun submit(json: String): Boolean = synchronized(lock) {
        val current = handle
        current != 0L && native.submitJson(current, json)
    }

    private companion object {
        const val ABI_VERSION = 1
        const val POLL_MS = 500L
    }
}
