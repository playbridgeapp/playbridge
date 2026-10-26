import AVFoundation
import CryptoKit

/// Song tags and embedded cover art for a phone file, sent as the item's `visualMetadata` so
/// the receiver can show them (as Android's LocalCastPackager does). Cover art is served from
/// the phone alongside the song.
enum LocalAudioMetadata {
    static func visualMetadata(for media: PhoneMedia, fileURL: URL) async -> [String: Any]? {
        guard media.kind == .audio else { return nil }
        var metadata: [String: Any] = ["title": media.title]
        if let artist = media.artist, !artist.isEmpty { metadata["artist"] = artist }
        if let album = media.album, !album.isEmpty { metadata["album"] = album }
        if let trackNumber = media.trackNumber { metadata["trackNumber"] = trackNumber }
        if let artwork = await artworkURL(fileURL) { metadata["artworkUrl"] = artwork }
        return metadata.count > 1 ? metadata : nil
    }

    private static func artworkURL(_ url: URL) async -> String? {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let items = (try? await AVURLAsset(url: url).load(.commonMetadata)) ?? []
        for entry in AVMetadataItem.metadataItems(from: items, filteredByIdentifier: .commonIdentifierArtwork) {
            guard let data = try? await entry.load(.dataValue), !data.isEmpty else { continue }
            let key = SHA256.hash(data: Data(url.path.utf8)).prefix(12).map { String(format: "%02x", $0) }.joined()
            return await LocalFileServer.shared.addArtwork(data, key: key)
        }
        return nil
    }
}
