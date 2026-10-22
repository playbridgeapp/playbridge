import Foundation

/// Artwork sized for a 4K Apple TV. Senders pass TMDB images sized for a phone
/// (backdrops `w780`, posters `w342`), which look soft once stretched to 3840 px.
/// TMDB URLs are re-requested at a larger size first, then as sent; other hosts
/// are used as sent.
enum ArtworkURL {
    enum Kind {
        /// `card` is a landscape image shown at card size (playlist, ~400 pt).
        case backdrop, poster, logo, card

        /// TMDB size path segment: full-screen backdrops and logos use the source.
        var tmdbSize: String {
            switch self {
            case .backdrop, .logo: return "original"
            case .poster, .card: return "w780"
            }
        }
    }

    /// Ordered load attempts: the upgraded TMDB URL, then the URL as sent.
    static func candidates(_ raw: String, kind: Kind) -> [URL] {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let original = URL(string: trimmed), let scheme = original.scheme?.lowercased(),
              scheme == "https" || scheme == "http" else { return [] }
        guard let upgraded = upgradedTMDB(original, kind: kind), upgraded != original else { return [original] }
        return [upgraded, original]
    }

    /// `https://image.tmdb.org/t/p/<size>/<file>` with the size swapped.
    private static func upgradedTMDB(_ url: URL, kind: Kind) -> URL? {
        guard url.host?.lowercased() == "image.tmdb.org" else { return nil }
        let parts = url.path.split(separator: "/", omittingEmptySubsequences: true)
        guard parts.count >= 4, parts[0] == "t", parts[1] == "p" else { return nil }
        let size = parts[2]
        guard size == "original" || (size.first == "w" && Int(size.dropFirst()) != nil) else { return nil }
        // Never shrink: keep a larger size than the target (or the source itself).
        if size == "original" { return nil }
        if kind == .poster || kind == .card, let width = Int(size.dropFirst()), width >= 780 { return nil }
        var components = URLComponents(url: url, resolvingAgainstBaseURL: false)
        components?.path = "/t/p/\(kind.tmdbSize)/" + parts[3...].joined(separator: "/")
        return components?.url
    }
}
