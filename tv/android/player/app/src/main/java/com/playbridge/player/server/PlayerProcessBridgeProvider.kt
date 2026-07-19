package com.playbridge.player.server

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import com.playbridge.shared.protocol.decodePlayPayloadListJson
import com.playbridge.shared.protocol.encodePlayPayloadListJson
import playbridge.PlayPayload

/**
 * Non-exported Binder bridge hosted in the main app process. The private MPV process uses it
 * only to atomically drain queue items that may have arrived before its broadcast receiver was
 * registered. Status and lifecycle messages use asynchronous package-scoped broadcasts.
 */
class PlayerProcessBridgeProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? = when (method) {
        METHOD_DRAIN_PENDING_QUEUE -> Bundle().apply {
            putString(KEY_ITEMS_JSON, encodePlayPayloadListJson(ServerService.drainPendingQueueItems()))
        }
        else -> super.call(method, arg, extras)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        private const val METHOD_DRAIN_PENDING_QUEUE = "drain_pending_queue"
        private const val KEY_ITEMS_JSON = "items_json"

        fun drainPendingQueue(context: Context): List<PlayPayload> = runCatching {
            val uri = Uri.parse("content://${context.packageName}.player-process-bridge")
            val result = context.contentResolver.call(uri, METHOD_DRAIN_PENDING_QUEUE, null, null)
            result?.getString(KEY_ITEMS_JSON)
                ?.let(::decodePlayPayloadListJson)
                .orEmpty()
        }.getOrDefault(emptyList())
    }
}
