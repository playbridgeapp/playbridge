import Foundation

/// Reads the expiry a signed media link states about itself. Port of Android's `LinkExpiry.kt`
/// (#545). Pure: no network, nothing logged, the link is not stored anywhere new. Returns nil
/// when the link carries no recognisable, plausible expiry; when several forms are present the
/// earliest wins.
///
/// Recognised: plain epoch parameters (`expires`, `expire`, `expiry`, `expiration`, `expires_at`,
/// `exp`, `e`, `validto`, `valid_until`; seconds or milliseconds, any case), AWS SigV4 and Google
/// Cloud Storage V4 (`X-Amz-Date` + `X-Amz-Expires`, `X-Goog-Date` + `X-Goog-Expires`), Azure SAS
/// (`se` with `sig`), Akamai tokens (`hdnts`, `hdnea`, `hdntl`, `token`, `__token__` holding
/// `exp=`), JWTs (`eyJ…` with an `exp` claim, signature not checked), and `name=value` path
/// segments with the plain names.
///
/// An epoch counts only between 2015-01-01 and ten years after `savedAt`.
enum LinkExpiry {
    static func expiresAt(_ url: String, savedAt: Date) -> Date? {
        let savedMs = Int64(savedAt.timeIntervalSince1970 * 1000)
        var candidates: [Int64] = []
        let noFragment = url.split(separator: "#", maxSplits: 1, omittingEmptySubsequences: false).first.map(String.init) ?? url
        let parts = noFragment.split(separator: "?", maxSplits: 1, omittingEmptySubsequences: false)
        let beforeQuery = parts.first.map(String.init) ?? ""
        let query = parts.count > 1 ? String(parts[1]) : ""
        let afterScheme = beforeQuery.range(of: "://").map { String(beforeQuery[$0.upperBound...]) } ?? beforeQuery
        let path = afterScheme.firstIndex(of: "/").map { String(afterScheme[afterScheme.index(after: $0)...]) } ?? ""

        var params: [String: [String]] = [:]
        func add(_ name: String, _ value: String) { params[name.lowercased(), default: []].append(value) }
        for pair in query.split(whereSeparator: { $0 == "&" || $0 == ";" }) {
            guard let eq = pair.firstIndex(of: "="), eq != pair.startIndex else { continue }
            add(decode(String(pair[..<eq])), decode(String(pair[pair.index(after: eq)...])))
        }
        let segments = path.split(separator: "/").map { decode(String($0)) }.filter { !$0.isEmpty }
        for segment in segments {
            if let eq = segment.firstIndex(of: "="), (1...32).contains(segment.distance(from: segment.startIndex, to: eq)) {
                add(String(segment[..<eq]), String(segment[segment.index(after: eq)...]))
            }
        }

        for name in epochNames { params[name]?.forEach { if let ms = plausibleEpochMs($0, savedMs) { candidates.append(ms) } } }
        if let ms = signedDuration(params, date: "x-amz-date", length: "x-amz-expires", savedMs) { candidates.append(ms) }
        if let ms = signedDuration(params, date: "x-goog-date", length: "x-goog-expires", savedMs) { candidates.append(ms) }
        if params["sig"] != nil {
            params["se"]?.forEach { if let ms = isoMs($0), plausible(ms, savedMs) { candidates.append(ms) } }
        }
        for name in tokenNames { params[name]?.forEach { if let ms = akamaiExp($0, savedMs) { candidates.append(ms) } } }
        for value in params.values.flatMap({ $0 }) + segments {
            if let ms = jwtExpMs(value, savedMs) { candidates.append(ms) }
        }
        return candidates.min().map { Date(timeIntervalSince1970: Double($0) / 1000) }
    }

    private static let epochNames = ["expires", "expire", "expiry", "expiration", "expires_at", "exp", "e", "validto", "valid_until"]
    private static let tokenNames = ["hdnts", "hdnea", "hdntl", "token", "__token__"]
    private static let minEpochMs: Int64 = 1_420_070_400_000 // 2015-01-01T00:00:00Z
    private static let tenYearsMs: Int64 = 10 * 366 * 24 * 3600 * 1000
    private static let maxSignedSeconds: Int64 = 7 * 24 * 3600

    private static func decode(_ text: String) -> String {
        text.replacingOccurrences(of: "+", with: "%2B").removingPercentEncoding ?? text
    }

    private static func plausible(_ ms: Int64, _ savedMs: Int64) -> Bool { ms >= minEpochMs && ms <= savedMs + tenYearsMs }

    /// Epoch seconds or milliseconds; the two ranges cannot overlap inside the window.
    static func plausibleEpochMs(_ raw: String, _ savedMs: Int64) -> Int64? {
        let text = raw.trimmingCharacters(in: .whitespaces)
        guard !text.isEmpty, text.count <= 16, text.allSatisfy({ $0.isASCII && ($0.isNumber || $0 == ".") }),
              text.filter({ $0 == "." }).count <= 1,
              let number = Int64(text.split(separator: ".", omittingEmptySubsequences: false).first ?? "") else { return nil }
        let ms = (1_000_000_000...99_999_999_999).contains(number) ? number * 1000 : number
        return plausible(ms, savedMs) ? ms : nil
    }

    private static func signedDuration(_ params: [String: [String]], date: String, length: String, _ savedMs: Int64) -> Int64? {
        guard let raw = params[date]?.first, let seconds = Int64(params[length]?.first?.trimmingCharacters(in: .whitespaces) ?? ""),
              (1...maxSignedSeconds).contains(seconds) else { return nil }
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "UTC")
        formatter.dateFormat = "yyyyMMdd'T'HHmmss'Z'"
        guard let start = formatter.date(from: raw.trimmingCharacters(in: .whitespaces)) else { return nil }
        let ms = Int64(start.timeIntervalSince1970 * 1000) + seconds * 1000
        return plausible(ms, savedMs) ? ms : nil
    }

    private static func isoMs(_ raw: String) -> Int64? {
        let text = raw.trimmingCharacters(in: .whitespaces)
        let full = ISO8601DateFormatter()
        let fractional = ISO8601DateFormatter(); fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let day = ISO8601DateFormatter(); day.formatOptions = [.withFullDate]
        let local = DateFormatter()
        local.locale = Locale(identifier: "en_US_POSIX"); local.timeZone = TimeZone(identifier: "UTC")
        local.dateFormat = "yyyy-MM-dd'T'HH:mm:ss"
        guard let date = full.date(from: text) ?? fractional.date(from: text) ?? local.date(from: text) ?? day.date(from: text) else { return nil }
        return Int64(date.timeIntervalSince1970 * 1000)
    }

    /// `st=..~exp=1700003600~acl=..~hmac=..`; `exp` is epoch seconds.
    private static func akamaiExp(_ token: String, _ savedMs: Int64) -> Int64? {
        for part in token.split(whereSeparator: { $0 == "~" || $0 == "&" }) where part.lowercased().hasPrefix("exp=") {
            return plausibleEpochMs(String(part.dropFirst(4)), savedMs)
        }
        return nil
    }

    private static func jwtExpMs(_ value: String, _ savedMs: Int64) -> Int64? {
        guard value.count <= 8192, value.hasPrefix("eyJ") else { return nil }
        let parts = value.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 3, !parts[1].isEmpty else { return nil }
        var base64 = parts[1].replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        base64 = base64.trimmingCharacters(in: CharacterSet(charactersIn: "="))
        base64 += String(repeating: "=", count: (4 - base64.count % 4) % 4)
        guard let data = Data(base64Encoded: base64),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let seconds = (json["exp"] as? NSNumber)?.doubleValue,
              seconds.isFinite, seconds >= 0, seconds <= 1e12 else { return nil }
        return plausibleEpochMs(String(Int64(seconds)), savedMs)
    }
}

/// What a history row says about its link.
enum LinkStatus: Equatable {
    case none
    case expiresSoon(TimeInterval)
    /// `at` is known only when the link itself states its expiry.
    case expired(Date?)
    case checking
    case couldNotCheck

    static let warningWindow: TimeInterval = 24 * 3600

    /// In order: checking; a stated expiry in the past (unless a later check found the link
    /// working); a check that found it gone; an expiry within 24 hours; a check that couldn't
    /// run; otherwise nothing.
    static func of(expiresAt: Date?, now: Date, check: LinkCheck.Result? = nil, checkedAt: Date? = nil,
                   checking: Bool = false) -> LinkStatus {
        if checking { return .checking }
        if let expiresAt, expiresAt <= now {
            let contradicted = check == .works && (checkedAt.map { $0 >= expiresAt } ?? false)
            if !contradicted { return .expired(expiresAt) }
        }
        if check == .expired { return .expired(nil) }
        if let expiresAt, expiresAt > now, expiresAt.timeIntervalSince(now) <= warningWindow {
            return .expiresSoon(expiresAt.timeIntervalSince(now))
        }
        if check == .couldNotCheck { return .couldNotCheck }
        return .none
    }

    var isExpired: Bool { if case .expired = self { return true }; return false }

    /// "Link expires in 2 h", "Link expired 3 days ago", "Link expired".
    func label(now: Date) -> String? {
        switch self {
        case .none: return nil
        case .expiresSoon(let remaining): return "Link expires in \(Self.span(remaining))"
        case .expired(let at):
            guard let at, now.timeIntervalSince(at) >= 60 else { return "Link expired" }
            return "Link expired \(Self.span(now.timeIntervalSince(at))) ago"
        case .checking: return "Checking the link…"
        case .couldNotCheck: return "Couldn’t check · may have expired"
        }
    }

    /// "5 min", "2 h", "3 days"; rounds down, under a minute is "1 min".
    static func span(_ seconds: TimeInterval) -> String {
        let value = max(0, seconds)
        if value < 3600 { return "\(max(1, Int(value / 60))) min" }
        if value < 86_400 { return "\(Int(value / 3600)) h" }
        let days = Int(value / 86_400)
        return days == 1 ? "1 day" : "\(days) days"
    }
}
