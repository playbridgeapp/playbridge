package com.playbridge.sender.data.collection

/** Which playback path a collection item takes. */
enum class CollectionRoute { WEB, LOCAL }

/**
 * Pure mapping from an item's [CollectionItemEntity.kind] to the playback route. Kept Android-free
 * so it can be unit-tested; the screen turns the route into the actual cast/local-player call.
 */
object CollectionPlayRouter {
    fun routeOf(kind: String?): CollectionRoute =
        if (kind == CollectionItemKind.LOCAL) CollectionRoute.LOCAL else CollectionRoute.WEB
}
