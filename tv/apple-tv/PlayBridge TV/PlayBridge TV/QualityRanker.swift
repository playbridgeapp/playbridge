import Foundation

/// Quality tier rank — mirrors the phone's QualityFilter patterns so both sides
/// apply the same preference. Higher rank = preferred.
struct QualityRanker {
    private static let uhdPatterns = ["2160p", "4k", "uhd"]
    private static let fhdPatterns = ["1080p", "1080"]
    private static let hdPatterns = ["720p", "720"]

    /** Returns 4, 3, 2, or 1 for UHD / FHD / HD / SD respectively based on name/title text. */
    static func rankFromText(_ text: String) -> Int {
        let lower = text.lowercased()
        if uhdPatterns.contains(where: { lower.contains($0) }) { return 4 }
        if fhdPatterns.contains(where: { lower.contains($0) }) { return 3 }
        if hdPatterns.contains(where: { lower.contains($0) }) { return 2 }
        return 1
    }

    /**
     * Returns the target rank for the given quality preference key.
     * Returns 0 for null / "auto" so callers can detect "no preference".
     */
    static func targetRank(_ qualityPreference: String?) -> Int {
        guard let pref = qualityPreference?.lowercased() else { return 0 }
        if uhdPatterns.contains(pref) { return 4 }
        if fhdPatterns.contains(pref) { return 3 }
        if hdPatterns.contains(pref) { return 2 }
        return 0
    }
}
