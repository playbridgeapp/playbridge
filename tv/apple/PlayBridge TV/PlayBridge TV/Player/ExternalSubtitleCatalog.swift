import Foundation

/// Stable menu IDs for subtitle URLs that have not been loaded into the player yet.
/// Native track IDs are nonnegative; -1 is reserved for "Off".
struct ExternalSubtitleCatalog {
    struct Option {
        let id: Int
        let url: String
        let name: String
    }

    let options: [Option]

    private init(options: [Option]) { self.options = options }

    init(urls: [String]) {
        var seen = Set<String>()
        options = urls.compactMap { raw in
            guard let url = URL(string: raw),
                  ((["http", "https"].contains(url.scheme?.lowercased() ?? "") && url.host != nil)
                    || (url.isFileURL && !url.path.isEmpty)),
                  seen.insert(raw).inserted else { return nil }
            let number = seen.count
            return Option(id: -number - 1, url: raw, name: "External subtitle \(number)")
        }
    }

    func option(for id: Int) -> Option? {
        options.first { $0.id == id }
    }

    func appending(url: String, name: String) -> (ExternalSubtitleCatalog, Option) {
        if let existing = options.first(where: { $0.url == url }) { return (self, existing) }
        let option = Option(id: -options.count - 2, url: url, name: name)
        return (ExternalSubtitleCatalog(options: options + [option]), option)
    }

    func unloadedTracks(excluding loadedURLs: Set<String>) -> [(id: Int, name: String)] {
        options.filter { !loadedURLs.contains($0.url) }.map { ($0.id, $0.name) }
    }
}
