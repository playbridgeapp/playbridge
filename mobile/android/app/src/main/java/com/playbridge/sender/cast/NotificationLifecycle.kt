package com.playbridge.sender.cast

/** A queued start can outlive its session/last owner. Promote only to discharge
 * Android's foreground-start contract, then remove the deferred notification. */
internal enum class NotificationStart { RUN, FINISH_START_AND_STOP }

internal fun notificationStart(hasWork: Boolean): NotificationStart =
    if (hasWork) NotificationStart.RUN else NotificationStart.FINISH_START_AND_STOP

internal fun mediaNotificationNeedsIdleStop(isPlaying: Boolean?): Boolean = isPlaying != true

/** Promotion failures must never escape a Service callback or leave an orphan service. */
internal fun promoteNotificationForeground(
    promote: () -> Unit,
    onFailure: (Exception) -> Unit,
    stop: () -> Unit,
): Boolean = try {
    promote()
    true
} catch (error: Exception) {
    onFailure(error)
    stop()
    false
}

internal data class MediaNotificationMetadata<Artwork>(
    val title: String?,
    val artist: String?,
    val artwork: Artwork? = null,
)

internal data class MediaOwnerNotification<Artwork>(
    val ownerId: String,
    val metadata: MediaNotificationMetadata<Artwork>? = null,
    val playing: Boolean? = null,
    val position: MediaNotificationPosition? = null,
    val seekSupported: Boolean = false,
)

/** Same idempotent owner-refcount model as the service's old set, with state kept
 * for each owner's lifetime (including notification dismissal/service restarts). */
internal class MediaNotificationOwners<Artwork> {
    private val owners = linkedMapOf<String, MediaOwnerNotification<Artwork>>()
    private var selectedOwner: String? = null

    @Synchronized fun add(ownerId: String) {
        owners.putIfAbsent(ownerId, MediaOwnerNotification(ownerId))
        if (selectedOwner == null) selectedOwner = ownerId
    }

    @Synchronized fun remove(ownerId: String): Boolean {
        if (owners.remove(ownerId) == null) return false
        if (selectedOwner == ownerId) {
            selectedOwner = owners.values.lastOrNull { it.playing == true }?.ownerId
                ?: owners.keys.lastOrNull()
        }
        return true
    }

    @Synchronized fun isEmpty(): Boolean = owners.isEmpty()

    @Synchronized fun ownerIds(): List<String> = owners.keys.toList()

    @Synchronized fun current(): MediaOwnerNotification<Artwork>? = owners[selectedOwner]

    @Synchronized fun updateMetadata(ownerId: String, metadata: MediaNotificationMetadata<Artwork>): Boolean {
        val owner = owners[ownerId] ?: return false
        owners[ownerId] = owner.copy(metadata = metadata)
        return true
    }

    @Synchronized fun updatePlayback(ownerId: String, playing: Boolean, nowMs: Long? = null): Boolean {
        val owner = owners[ownerId] ?: return false
        owners[ownerId] = owner.copy(
            playing = playing,
            position = if (nowMs != null) owner.position?.rebase(nowMs, owner.playing == true) else owner.position,
        )
        // A background tab's pause must not replace the playing owner's metadata.
        if (playing || selectedOwner == null) selectedOwner = ownerId
        else if (selectedOwner == ownerId) {
            owners.values.lastOrNull { it.playing == true }?.let { selectedOwner = it.ownerId }
        }
        return true
    }

    @Synchronized fun updatePosition(ownerId: String, position: MediaNotificationPosition): Boolean {
        val owner = owners[ownerId] ?: return false
        owners[ownerId] = owner.copy(position = position)
        return true
    }

    @Synchronized fun updateSeekSupported(ownerId: String, supported: Boolean): Boolean {
        val owner = owners[ownerId] ?: return false
        owners[ownerId] = owner.copy(seekSupported = supported)
        return true
    }

    @Synchronized fun pauseCurrent(nowMs: Long? = null) {
        val owner = owners[selectedOwner] ?: return
        updatePlayback(owner.ownerId, playing = false, nowMs = nowMs)
    }
}
