import Foundation

/// Normalizes incoming cast language codes and resolves audio/subtitle track
/// priority across saved session choices, per-item cast preferences, and device locale.
enum TrackLanguagePolicy {
    private static let disallowedPrimaryCodes: Set<String> = ["und", "mul", "zxx", "mis"]

    private static let knownISOLanguageCodes: Set<String> = {
        if #available(macOS 13, tvOS 16, *) {
            return Set(Locale.LanguageCode.isoLanguageCodes.map { $0.identifier.lowercased() })
        }
        return []
    }()

    /// Validates and canonicalizes a short BCP-47 language code (2–3 letters,
    /// optionally with script and/or region such as `pt-BR` or `zh-Hans`).
    /// Returns `nil` for empty, undetermined, or invalid values.
    static func normalizedCode(_ raw: String?) -> String? {
        guard let raw else { return nil }
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "_", with: "-")
        guard !trimmed.isEmpty, trimmed.count <= 16 else { return nil }

        let parts = trimmed.split(separator: "-", omittingEmptySubsequences: false)
        guard let primary = parts.first, isValidPrimarySubtag(primary) else { return nil }
        let lowerPrimary = primary.lowercased()
        guard !disallowedPrimaryCodes.contains(lowerPrimary) else { return nil }

        switch parts.count {
        case 1:
            break
        case 2:
            guard isValidRegionSubtag(parts[1]) || isValidScriptSubtag(parts[1]) else { return nil }
        case 3:
            guard isValidScriptSubtag(parts[1]), isValidRegionSubtag(parts[2]) else { return nil }
        default:
            return nil
        }

        let canonical = Locale.canonicalLanguageIdentifier(from: trimmed)
        let canonicalPrimary = baseLanguage(of: canonical)
        guard !disallowedPrimaryCodes.contains(canonicalPrimary) else { return nil }
        if !knownISOLanguageCodes.isEmpty, !knownISOLanguageCodes.contains(canonicalPrimary) {
            return nil
        }
        return canonical
    }

    /// Normalizes a language tag read from a media container or `AVMediaSelectionOption`,
    /// tolerating trailing variant/private-use subtags when the leading language is valid.
    static func candidateTag(_ raw: String?) -> String? {
        if let direct = normalizedCode(raw) { return direct }
        guard let raw else { return nil }
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "_", with: "-")
        guard !trimmed.isEmpty else { return nil }
        let parts = trimmed.split(separator: "-", omittingEmptySubsequences: false)
        guard let primary = parts.first, isValidPrimarySubtag(primary) else { return nil }
        if parts.count >= 3, isValidScriptSubtag(parts[1]), isValidRegionSubtag(parts[2]) {
            return normalizedCode("\(primary)-\(parts[1])-\(parts[2])")
        }
        if parts.count >= 2, isValidRegionSubtag(parts[1]) || isValidScriptSubtag(parts[1]) {
            return normalizedCode("\(primary)-\(parts[1])")
        }
        return normalizedCode(String(primary))
    }

    static func baseLanguage(of canonicalCode: String) -> String {
        String(canonicalCode.split(separator: "-", maxSplits: 1).first ?? Substring(canonicalCode)).lowercased()
    }

    static func matches(_ candidateRaw: String?, preferenceRaw: String?) -> (exact: Bool, base: Bool) {
        guard let pref = normalizedCode(preferenceRaw) ?? candidateTag(preferenceRaw),
              let cand = candidateTag(candidateRaw) else {
            return (false, false)
        }
        if cand.caseInsensitiveCompare(pref) == .orderedSame {
            return (true, true)
        }
        if baseLanguage(of: cand) == baseLanguage(of: pref) {
            return (false, true)
        }
        return (false, false)
    }

    /// Comma-separated mpv `alang`/`slang` list ordered by priority, or `""` when unset.
    /// Regional codes expand to include their base language fallback (`pt-BR,pt`).
    static func mpvLanguageList(
        savedLanguage: String? = nil,
        castLanguage: String? = nil,
        deviceLanguages: [String] = []
    ) -> String {
        var result: [String] = []
        func appendExpanded(_ raw: String?) {
            guard let code = normalizedCode(raw) ?? candidateTag(raw) else { return }
            if !result.contains(where: { $0.caseInsensitiveCompare(code) == .orderedSame }) {
                result.append(code)
            }
            let base = baseLanguage(of: code)
            if base.caseInsensitiveCompare(code) != .orderedSame,
               !result.contains(where: { $0.caseInsensitiveCompare(base) == .orderedSame }) {
                result.append(base)
            }
        }
        appendExpanded(savedLanguage)
        appendExpanded(castLanguage)
        for lang in deviceLanguages {
            appendExpanded(lang)
        }
        return result.joined(separator: ",")
    }

    /// Ordered language list for `AVAsset.loadChapterMetadataGroups(bestMatchingPreferredLanguages:)`.
    static func preferredChapterLanguages(
        savedAudioLanguage: String?,
        castAudioLanguage: String?,
        deviceLanguages: [String]
    ) -> [String] {
        var result: [String] = []
        for raw in [savedAudioLanguage, castAudioLanguage] {
            if let code = normalizedCode(raw) ?? candidateTag(raw),
               !result.contains(where: { $0.caseInsensitiveCompare(code) == .orderedSame }) {
                result.append(code)
            }
        }
        for lang in deviceLanguages {
            if !result.contains(where: { $0.caseInsensitiveCompare(lang) == .orderedSame }) {
                result.append(lang)
            }
        }
        return result
    }

    struct TrackCandidate: Equatable {
        let id: Int
        let name: String
        let language: String?
        let secondaryLanguage: String?
        let isForced: Bool
        let isAccessibility: Bool
        let isExternal: Bool

        init(
            id: Int,
            name: String,
            language: String? = nil,
            secondaryLanguage: String? = nil,
            isForced: Bool = false,
            isAccessibility: Bool = false,
            isExternal: Bool = false
        ) {
            self.id = id
            self.name = name
            self.language = language
            self.secondaryLanguage = secondaryLanguage
            self.isForced = isForced
            self.isAccessibility = isAccessibility
            self.isExternal = isExternal
        }
    }

    struct SavedTrackChoice: Equatable {
        var language: String?
        var name: String?
        var isOff: Bool = false
    }

    enum SubtitleResolution: Equatable {
        case off
        case track(TrackCandidate)
        case keepDefault
    }

    /// Audio priority: saved choice > cast `preferredAudioLanguage` > device locale.
    static func resolveAudioTrack(
        candidates: [TrackCandidate],
        saved: SavedTrackChoice,
        castLanguage: String?,
        deviceLanguages: [String]
    ) -> TrackCandidate? {
        guard !candidates.isEmpty else { return nil }
        if let savedMatch = matchSaved(saved, in: candidates, preferNonForced: false) {
            return savedMatch
        }
        if let castLang = normalizedCode(castLanguage),
           let castMatch = matchLanguage(castLang, in: candidates, preferNonForced: false) {
            return castMatch
        }
        for deviceLang in deviceLanguages {
            if let norm = candidateTag(deviceLang),
               let deviceMatch = matchLanguage(norm, in: candidates, preferNonForced: false) {
                return deviceMatch
            }
        }
        return nil
    }

    /// Subtitle priority: saved Off > saved choice > cast `preferredSubtitleLanguage` > keep default.
    static func resolveSubtitleTrack(
        candidates: [TrackCandidate],
        saved: SavedTrackChoice,
        castLanguage: String?
    ) -> SubtitleResolution {
        if saved.isOff { return .off }
        if !candidates.isEmpty,
           let savedMatch = matchSaved(saved, in: candidates, preferNonForced: true) {
            return .track(savedMatch)
        }
        if !candidates.isEmpty,
           let castLang = normalizedCode(castLanguage),
           let castMatch = matchLanguage(castLang, in: candidates, preferNonForced: true) {
            return .track(castMatch)
        }
        return .keepDefault
    }

    /// Chooses which sender-supplied subtitle `MPVPlayerView` auto-attaches on file load.
    /// Keeps first-sender-subtitle auto-attach working while allowing a matching saved or
    /// cast-preferred external subtitle to win when no embedded track already matches.
    static func pickPendingExternalSubtitle(
        subtitles: [ItemSubtitle],
        hasMatchingEmbeddedTrack: Bool,
        saved: SavedTrackChoice,
        castSubtitleLanguage: String?
    ) -> (subtitle: ItemSubtitle, flag: String)? {
        guard let first = subtitles.first else { return nil }
        if saved.isOff {
            return (first, "auto")
        }
        if !hasMatchingEmbeddedTrack {
            if let savedName = saved.name, !savedName.isEmpty,
               let byName = subtitles.first(where: { $0.label == savedName }) {
                return (byName, "select")
            }
            if let savedLang = saved.language,
               let bySavedLang = matchExternalSubtitle(subtitles, preference: savedLang) {
                return (bySavedLang, "select")
            }
            if let castLang = normalizedCode(castSubtitleLanguage),
               let byCastLang = matchExternalSubtitle(subtitles, preference: castLang) {
                return (byCastLang, "select")
            }
        }
        return (first, "auto")
    }

    static func pickExternalSubtitle(
        options: [ExternalSubtitleCatalog.Option],
        castSubtitleLanguage: String?
    ) -> ExternalSubtitleCatalog.Option? {
        guard let pref = normalizedCode(castSubtitleLanguage) else { return nil }
        return options.first { matches($0.language, preferenceRaw: pref).exact }
            ?? options.first { matches($0.language, preferenceRaw: pref).base }
    }

    static func hasMatchingTrack(language: String?, in candidates: [TrackCandidate]) -> Bool {
        guard let pref = normalizedCode(language) else { return false }
        return matchLanguage(pref, in: candidates, preferNonForced: false) != nil
    }

    private static func matchExternalSubtitle(
        _ subtitles: [ItemSubtitle],
        preference: String
    ) -> ItemSubtitle? {
        subtitles.first { matches($0.language, preferenceRaw: preference).exact }
            ?? subtitles.first { matches($0.language, preferenceRaw: preference).base }
    }

    private static func matchSaved(
        _ saved: SavedTrackChoice,
        in candidates: [TrackCandidate],
        preferNonForced: Bool
    ) -> TrackCandidate? {
        let savedLang = candidateTag(saved.language)
        let savedName = saved.name?.trimmingCharacters(in: .whitespacesAndNewlines)
        let hasName = savedName.map { !$0.isEmpty } ?? false

        if let savedLang, hasName, let savedName {
            if let exact = candidates.first(where: {
                $0.name == savedName && candidateMatches($0, preference: savedLang).exact
            }) { return exact }
            if let base = candidates.first(where: {
                $0.name == savedName && candidateMatches($0, preference: savedLang).base
            }) { return base }
        }

        if hasName, let savedName {
            if let compatible = candidates.first(where: { candidate in
                guard candidate.name == savedName else { return false }
                guard let savedLang else { return true }
                let candLang = candidateTag(candidate.language) ?? candidateTag(candidate.secondaryLanguage)
                return candLang == nil || baseLanguage(of: candLang!) == baseLanguage(of: savedLang)
            }) {
                return compatible
            }
        }

        if let savedLang {
            return matchLanguage(savedLang, in: candidates, preferNonForced: preferNonForced)
        }
        return nil
    }

    private static func matchLanguage(
        _ preference: String,
        in candidates: [TrackCandidate],
        preferNonForced: Bool
    ) -> TrackCandidate? {
        let scored: [(candidate: TrackCandidate, exact: Bool)] = candidates.compactMap { candidate in
            let match = candidateMatches(candidate, preference: preference)
            guard match.base else { return nil }
            return (candidate, match.exact)
        }
        guard !scored.isEmpty else { return nil }

        let ranked = scored.sorted { lhs, rhs in
            if preferNonForced && lhs.candidate.isForced != rhs.candidate.isForced {
                return !lhs.candidate.isForced
            }
            if lhs.exact != rhs.exact {
                return lhs.exact
            }
            if preferNonForced && lhs.candidate.isAccessibility != rhs.candidate.isAccessibility {
                return !lhs.candidate.isAccessibility
            }
            if lhs.candidate.isExternal != rhs.candidate.isExternal {
                return !lhs.candidate.isExternal
            }
            return false
        }
        return ranked.first?.candidate
    }

    private static func candidateMatches(
        _ candidate: TrackCandidate,
        preference: String
    ) -> (exact: Bool, base: Bool) {
        let primary = matches(candidate.language, preferenceRaw: preference)
        let secondary = matches(candidate.secondaryLanguage, preferenceRaw: preference)
        return (primary.exact || secondary.exact, primary.base || secondary.base)
    }

    private static func isValidPrimarySubtag(_ s: Substring) -> Bool {
        (2...3).contains(s.count) && s.allSatisfy { $0.isASCII && $0.isLetter }
    }

    private static func isValidScriptSubtag(_ s: Substring) -> Bool {
        s.count == 4 && s.allSatisfy { $0.isASCII && $0.isLetter }
    }

    private static func isValidRegionSubtag(_ s: Substring) -> Bool {
        (s.count == 2 && s.allSatisfy { $0.isASCII && $0.isLetter })
            || (s.count == 3 && s.allSatisfy { $0.isASCII && $0.isNumber })
    }
}
