import Foundation

/// The history list's filters.
enum CastHistoryFilter: String, CaseIterable, Identifiable {
    case all = "All"
    case unfinished = "Unfinished"
    case failed = "Failed"
    case expired = "Expired"
    var id: String { rawValue }

    func matches(_ entry: CastHistoryStore.Entry, now: Date) -> Bool {
        switch self {
        case .all: return true
        case .unfinished: return entry.status == .inProgress
        case .failed: return entry.status == .failed
        case .expired: return entry.linkStatus(now: now).isExpired
        }
    }
}

/// How a history row reads. Port of Android's `CastHistoryUi.kt` wording (#545). Pure.
enum CastHistoryPresentation {
    static func title(_ entry: CastHistoryStore.Entry) -> String {
        let title = entry.title.trimmingCharacters(in: .whitespacesAndNewlines)
        return title.isEmpty ? (entry.host.isEmpty ? "Untitled cast" : entry.host) : title
    }

    /// "Living Room TV", "Kitchen (DLNA)", "AirPlay".
    static func receiverLabel(name: String?, kind: String?) -> String {
        let suffix: String?
        switch kind {
        case "dlna": suffix = "DLNA"
        case "roku": suffix = "Roku"
        case "google_cast": suffix = "Google Cast"
        case "browser": suffix = "browser"
        case "airplay": suffix = "AirPlay"
        default: suffix = nil
        }
        let base = name?.trimmingCharacters(in: .whitespacesAndNewlines).nonEmpty
        switch (base, suffix) {
        case let (base?, suffix?) where base != suffix: return "\(base) (\(suffix))"
        case let (base?, _): return base
        case let (nil, suffix?): return suffix
        default: return "TV"
        }
    }

    /// "site · device"; a playlist says "Playlist" where the site would be.
    static func subtitle(_ entry: CastHistoryStore.Entry) -> String {
        let receiver = receiverLabel(name: entry.receiver, kind: entry.receiverKind)
        let lead = (entry.playlistCount ?? 0) > 1 ? "Playlist" : entry.host.nonEmpty
        return lead.map { "\($0) · \(receiver)" } ?? receiver
    }

    /// "2 of 5" for a playlist, LIVE, or the length.
    static func badge(_ entry: CastHistoryStore.Entry) -> String? {
        if entry.status == .failed { return nil }
        if let count = entry.progress?.playlistCount ?? entry.playlistCount, count > 1 {
            return entry.progress?.playlistIndex.map { "\($0 + 1) of \(count)" } ?? "\(count) items"
        }
        if entry.progress?.live == true { return "LIVE" }
        if let duration = entry.progress?.durationMs, duration > 0 { return clock(duration) }
        return nil
    }

    /// "2:58", "1:32:05".
    static func clock(_ ms: Int64) -> String {
        let total = max(0, ms) / 1000
        let (h, m, s) = (total / 3600, (total % 3600) / 60, total % 60)
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%d:%02d", m, s)
    }

    /// "25 min", "1 h 5 min": how long a live stream was watched.
    static func watched(_ ms: Int64) -> String {
        let minutes = max(1, max(0, ms) / 60_000)
        if minutes < 60 { return "\(minutes) min" }
        return minutes % 60 == 0 ? "\(minutes / 60) h" : "\(minutes / 60) h \(minutes % 60) min"
    }

    /// A short reason; never the receiver's own text.
    static func failureText(_ reason: String?, kind: String?) -> String {
        let tv = kind == nil || kind == "playbridge"
        switch reason {
        case CastPositionTracker.failReceiverError: return tv ? "The TV couldn't load it" : "The device couldn't load it"
        case CastPositionTracker.failStoppedEarly: return "Stopped before it started"
        case CastPositionTracker.failNotSent: return tv ? "Couldn't reach the TV" : "Couldn't reach the device"
        default: return "Didn't play"
        }
    }

    /// "Resume from 2:58".
    static func resumeLabel(_ entry: CastHistoryStore.Entry) -> String { "Resume from \(clock(entry.progress?.positionMs ?? 0))" }

    /// "Stopped at 2:58 of 12:14", or what is known.
    static func stoppedDetail(_ entry: CastHistoryStore.Entry) -> String {
        let p = entry.progress ?? CastProgress()
        switch entry.status {
        case .finished: return "Finished"
        case .live: return "Live · watched for \(watched(p.liveWatchedMs))"
        case .failed: return failureText(p.failureReason, kind: entry.receiverKind)
        case .unknown: return "Position not reported"
        case .inProgress, .notStarted:
            return p.durationMs > 0 ? "Stopped at \(clock(p.positionMs)) of \(clock(p.durationMs))" : "Stopped at \(clock(p.positionMs))"
        }
    }

    /// "just now", "20 min ago", "2 h ago", "Yesterday", "3 days ago", "last week", "3 weeks ago", "Oct 3".
    static func ago(_ date: Date, now: Date, calendar: Calendar = .current) -> String {
        let seconds = now.timeIntervalSince(date)
        if seconds < 60 { return "just now" }
        if seconds < 3600 { return "\(Int(seconds / 60)) min ago" }
        if seconds < 86_400 { return "\(Int(seconds / 3600)) h ago" }
        let days = daysBetween(date, now, calendar)
        switch days {
        case ...1: return "Yesterday"
        case 2..<7: return "\(days) days ago"
        case 7..<14: return "last week"
        case 14..<30: return "\(days / 7) weeks ago"
        default: return shortDate(date, calendar: calendar, withYear: false)
        }
    }

    /// "Today", "Yesterday", then "Nov 10"; an earlier year adds the year.
    static func dayLabel(_ date: Date, now: Date, calendar: Calendar = .current) -> String {
        let days = daysBetween(date, now, calendar)
        if days <= 0 { return "Today" }
        if days == 1 { return "Yesterday" }
        let sameYear = calendar.component(.year, from: date) == calendar.component(.year, from: now)
        return shortDate(date, calendar: calendar, withYear: !sameYear)
    }

    /// Entries (newest first) grouped under their day.
    static func groupByDay(_ entries: [CastHistoryStore.Entry], now: Date,
                           calendar: Calendar = .current) -> [(label: String, entries: [CastHistoryStore.Entry])] {
        var groups: [(day: Date, entries: [CastHistoryStore.Entry])] = []
        for entry in entries {
            let day = calendar.startOfDay(for: entry.date)
            if let last = groups.last, last.day == day { groups[groups.count - 1].entries.append(entry) }
            else { groups.append((day, [entry])) }
        }
        return groups.map { (dayLabel($0.entries[0].date, now: now, calendar: calendar), $0.entries) }
    }

    /// "Stopped at 2:58 · 2 h ago", "Finished · Yesterday", "Cast 2 h ago" when nothing was reported.
    static func status(_ entry: CastHistoryStore.Entry, now: Date) -> String {
        let when = ago(entry.date, now: now)
        let p = entry.progress ?? CastProgress()
        switch entry.status {
        case .failed: return "\(failureText(p.failureReason, kind: entry.receiverKind)) · \(when)"
        case .finished: return "Finished · \(when)"
        case .live: return "Watched for \(watched(p.liveWatchedMs)) · \(when)"
        case .inProgress, .notStarted: return "Stopped at \(clock(p.positionMs)) · \(when)"
        case .unknown: return "Cast \(when)"
        }
    }

    /// "Playing on Living room" for the cast playing now.
    static func playingLine(_ entry: CastHistoryStore.Entry) -> String {
        "Playing on \(receiverLabel(name: entry.receiver, kind: entry.receiverKind))"
    }

    /// What a screen reader hears for the whole row.
    static func description(_ entry: CastHistoryStore.Entry, now: Date) -> String {
        [title(entry), subtitle(entry), status(entry, now: now), entry.linkStatus(now: now).label(now: now)]
            .compactMap { $0 }.joined(separator: ". ")
    }

    /// "This link expired yesterday at 21:40", or the plain form when the time isn't known.
    static func expiredNotice(_ at: Date?, now: Date, calendar: Calendar = .current) -> String {
        guard let at else { return "This link has expired" }
        let day = dayLabel(at, now: now, calendar: calendar)
        let when = day == "Today" || day == "Yesterday" ? day.lowercased() : "on \(day)"
        let time = DateFormatter.localizedString(from: at, dateStyle: .none, timeStyle: .short)
        return "This link expired \(when) at \(time)"
    }

    /// "38 casts · 31 pictures".
    static func storageLine(casts: Int, pictures: Int) -> String {
        "\(casts == 1 ? "1 cast" : "\(casts) casts") · \(pictures == 1 ? "1 picture" : "\(pictures) pictures")"
    }

    /// "2.4 MB", "310 KB".
    static func bytes(_ count: Int64) -> String {
        count >= 1_000_000 ? String(format: "%.1f MB", Double(count) / 1_000_000) : "\((max(0, count) + 999) / 1000) KB"
    }

    private static func daysBetween(_ date: Date, _ now: Date, _ calendar: Calendar) -> Int {
        calendar.dateComponents([.day], from: calendar.startOfDay(for: date), to: calendar.startOfDay(for: now)).day ?? 0
    }

    private static func shortDate(_ date: Date, calendar: Calendar, withYear: Bool) -> String {
        let formatter = DateFormatter()
        formatter.calendar = calendar
        formatter.timeZone = calendar.timeZone
        formatter.setLocalizedDateFormatFromTemplate(withYear ? "MMMdyyyy" : "MMMd")
        return formatter.string(from: date)
    }
}

private extension String {
    var nonEmpty: String? { isEmpty ? nil : self }
}
