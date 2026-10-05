package com.playbridge.sender.browser

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import playbridge.PlayPayload
import playbridge.PlaylistPayload
import playbridge.VisualMetadata

/** Structural page fields only: metadata and header names are not protocol fields. */
internal fun pageCastHasSenderOnlyFields(value: JsonElement): Boolean {
    fun forbidden(source: JsonElement?): Boolean {
        val objectValue = source as? JsonObject ?: return false
        return "progressWebhook" in objectValue ||
            (objectValue["items"] as? JsonArray)?.any { item ->
                item is JsonObject && "progressWebhook" in item
            } == true
    }
    return forbidden(value) || forbidden((value as? JsonObject)?.get("payload"))
}

/** Website casts never inherit or accept a sender's progress callback. */
internal fun pageCastPlaylistPayload(
    items: List<PlayPayload>,
    startIndex: Int,
    metadata: VisualMetadata?,
    skipPreplay: Boolean,
): PlaylistPayload = PlaylistPayload(
    items = items,
    start_index = startIndex,
    visual_metadata = metadata,
    skip_preplay = skipPreplay,
    progress_webhook = null,
)
