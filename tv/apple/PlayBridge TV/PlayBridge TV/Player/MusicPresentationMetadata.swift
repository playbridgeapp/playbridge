import Foundation

struct MusicPresentationMetadata: Equatable {
    let title: String
    let artist: String
    let album: String
    let artworkURLs: [URL]

    init(title: String?, metadataTitle: String?, artist: String?, album: String?,
         artworkURL: String?, posterURL: String?, backdropURL: String?) {
        func nonblank(_ value: String?) -> String? {
            value.flatMap { $0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : $0 }
        }
        self.title = nonblank(title) ?? nonblank(metadataTitle) ?? "Music"
        self.artist = nonblank(artist) ?? ""
        self.album = nonblank(album) ?? ""
        var seen: Set<URL> = []
        artworkURLs = [artworkURL, posterURL, backdropURL].compactMap { value in
            guard let value = nonblank(value), let url = URL(string: value),
                  MediaImageRequest.origin(url) != nil, seen.insert(url).inserted else { return nil }
            return url
        }
    }
}
