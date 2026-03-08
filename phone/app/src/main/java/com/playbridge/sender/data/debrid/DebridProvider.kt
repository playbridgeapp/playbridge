package com.playbridge.sender.data.debrid

/**
 * Interface that all Debrid providers must implement.
 */
interface DebridProvider {
    /**
     * The display name of the provider (e.g., "Real-Debrid").
     */
    val name: String

    /**
     * Add a magnet link to the user's Debrid cloud.
     * @return The internal ID of the newly added torrent.
     */
    suspend fun addMagnet(magnetUri: String): String

    /**
     * Add a raw .torrent file to the user's Debrid cloud.
     * @return The internal ID of the newly added torrent.
     */
    suspend fun addTorrent(torrentBytes: ByteArray): String

    /**
     * Get details about a specific torrent, including its files and status.
     */
    suspend fun getTorrentInfo(id: String): DebridTorrentInfo
    
    /**
     * Get a list of the user's recent torrents.
     */
    suspend fun getTorrents(): List<DebridTorrentInfo>

    /**
     * Instruct the Debrid provider which files within the torrent to download/prepare.
     * Often required before streams can be generated.
     */
    suspend fun selectFiles(id: String, fileIds: List<String>)

    /**
     * Get the restricted links for a given torrent (to be used with unrestrictLink).
     */
    suspend fun getRestrictedLinks(id: String): List<String>

    /**
     * Convert an internal provider stream link into a direct, playable HTTP URL.
     * @param link The restricted link provided by the Debrid service (usually from the torrent properties after picking files)
     */
    suspend fun unrestrictLink(link: String): DebridUnrestrictedLink
    
    /**
     * Delete a torrent from the user's cloud.
     */
    suspend fun deleteTorrent(id: String)
}

/**
 * Exception thrown when a Debrid API request fails.
 */
class DebridException(message: String, cause: Throwable? = null) : Exception(message, cause)
