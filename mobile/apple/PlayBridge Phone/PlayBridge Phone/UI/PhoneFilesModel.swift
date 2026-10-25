import Foundation

/// Phone Files tabs, in display order.
enum PhoneFilesTab: String, CaseIterable, Identifiable {
    case videos, music, photos
    var id: String { rawValue }
    var title: String {
        switch self { case .videos: return "Videos"; case .music: return "Music"; case .photos: return "Photos" }
    }
    var kind: PhoneMedia.Kind {
        switch self { case .videos: return .video; case .music: return .audio; case .photos: return .image }
    }
}

/// How the Videos grid and the song list are ordered.
enum PhoneFilesSort: String, CaseIterable, Identifiable {
    case newest, oldest, name, largest
    var id: String { rawValue }
    var title: String {
        switch self {
        case .newest: return "Newest first"
        case .oldest: return "Oldest first"
        case .name: return "Name"
        case .largest: return "Largest first"
        }
    }
}

/// Music sub-views. iPhone has no folders: imported files are flat copies.
enum MusicView: String, CaseIterable, Identifiable {
    case songs, albums, artists
    var id: String { rawValue }
    var title: String {
        switch self { case .songs: return "Songs"; case .albums: return "Albums"; case .artists: return "Artists" }
    }
}

/// An album or artist grouping of songs; `id` is stable for navigation.
struct SongGroup: Identifiable, Hashable {
    let id: String
    let title: String
    let subtitle: String?
    let songs: [PhoneMedia]
    var totalDuration: Double { songs.reduce(0) { $0 + max(0, $1.duration ?? 0) } }
}

/// A dated section of the Photos grid.
struct PhotoSection: Identifiable {
    let label: String
    let items: [PhoneMedia]
    var id: String { label }
}

/// Pure list logic for Phone Files, kept out of SwiftUI so it can be checked on the host.
enum PhoneFilesModel {
    static func matches(_ item: PhoneMedia, _ query: String) -> Bool {
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return true }
        return item.title.localizedCaseInsensitiveContains(q) ||
            (item.artist?.localizedCaseInsensitiveContains(q) ?? false) ||
            (item.album?.localizedCaseInsensitiveContains(q) ?? false)
    }

    static func sorted(_ items: [PhoneMedia], by sort: PhoneFilesSort) -> [PhoneMedia] {
        switch sort {
        case .newest: return items.sorted { $0.addedAt > $1.addedAt }
        case .oldest: return items.sorted { $0.addedAt < $1.addedAt }
        case .name: return items.sorted { $0.title.localizedStandardCompare($1.title) == .orderedAscending }
        case .largest: return items.sorted { ($0.bytes ?? 0) > ($1.bytes ?? 0) }
        }
    }

    /// Album filter (asset ids of the chosen album, nil for All), then search, then sort.
    static func visible(_ items: [PhoneMedia], album: Set<String>?, query: String, sort: PhoneFilesSort) -> [PhoneMedia] {
        sorted(items.filter { item in
            (album.map { set in item.assetIdentifier.map(set.contains) ?? false } ?? true) && matches(item, query)
        }, by: sort)
    }

    /// Track number when tagged, otherwise title.
    static func trackOrder(_ songs: [PhoneMedia]) -> [PhoneMedia] {
        songs.sorted {
            let a = $0.trackNumber ?? .max, b = $1.trackNumber ?? .max
            return a != b ? a < b : $0.title.localizedStandardCompare($1.title) == .orderedAscending
        }
    }

    /// Albums keyed by album and artist, so same-named albums by different artists stay apart.
    static func albums(_ songs: [PhoneMedia]) -> [SongGroup] {
        let tagged = songs.filter { !($0.album ?? "").isEmpty }
        let groups = Dictionary(grouping: tagged) { "\($0.album!.lowercased())|\(($0.artist ?? "").lowercased())" }
        return groups.values.map { group in
            let first = group[0]
            return SongGroup(id: "album:\(first.album!)|\(first.artist ?? "")", title: first.album!,
                             subtitle: first.artist, songs: trackOrder(group))
        }.sorted { $0.title.localizedStandardCompare($1.title) == .orderedAscending }
    }

    static func artists(_ songs: [PhoneMedia]) -> [SongGroup] {
        let tagged = songs.filter { !($0.artist ?? "").isEmpty }
        let groups = Dictionary(grouping: tagged) { $0.artist!.lowercased() }
        return groups.values.map { group in
            let ordered = group.sorted {
                let a = ($0.album ?? "").lowercased(), b = ($1.album ?? "").lowercased()
                if a != b { return a < b }
                let ta = $0.trackNumber ?? .max, tb = $1.trackNumber ?? .max
                return ta != tb ? ta < tb : $0.title.localizedStandardCompare($1.title) == .orderedAscending
            }
            return SongGroup(id: "artist:\(group[0].artist!)", title: group[0].artist!, subtitle: nil, songs: ordered)
        }.sorted { $0.title.localizedStandardCompare($1.title) == .orderedAscending }
    }

    /// Photos grouped by the day they were taken, newest first: Today, Yesterday, then dates.
    static func photoSections(_ photos: [PhoneMedia], now: Date = Date(), calendar: Calendar = .current,
                              locale: Locale = .current) -> [PhotoSection] {
        let sameYear = DateFormatter(), otherYear = DateFormatter()
        sameYear.locale = locale; otherYear.locale = locale
        sameYear.calendar = calendar; otherYear.calendar = calendar
        sameYear.timeZone = calendar.timeZone; otherYear.timeZone = calendar.timeZone
        sameYear.setLocalizedDateFormatFromTemplate("EEE d MMM")
        otherYear.setLocalizedDateFormatFromTemplate("d MMM yyyy")
        let today = calendar.startOfDay(for: now)
        let yesterday = calendar.date(byAdding: .day, value: -1, to: today)!
        let ordered = photos.sorted { $0.addedAt > $1.addedAt }
        var sections: [PhotoSection] = []
        var current: (day: Date, items: [PhoneMedia])?
        func flush() {
            guard let current else { return }
            let label: String
            if current.day == today { label = "Today" }
            else if current.day == yesterday { label = "Yesterday" }
            else if calendar.component(.year, from: current.day) == calendar.component(.year, from: today) {
                label = sameYear.string(from: current.day)
            } else { label = otherYear.string(from: current.day) }
            sections.append(PhotoSection(label: label, items: current.items))
        }
        for photo in ordered {
            let day = calendar.startOfDay(for: photo.addedAt)
            if current?.day == day { current!.items.append(photo) }
            else { flush(); current = (day, [photo]) }
        }
        flush()
        return sections
    }

    /// Toggles `id` in a pick-ordered selection; removing renumbers the rest.
    static func toggle(_ selection: [String], _ id: String) -> [String] {
        selection.contains(id) ? selection.filter { $0 != id } : selection + [id]
    }

    /// "1:52:10" or "3:41".
    static func clock(_ seconds: Double) -> String {
        let s = max(0, Int(seconds.isFinite ? seconds : 0))
        let h = s / 3600, m = (s % 3600) / 60, sec = s % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, sec) : String(format: "%d:%02d", m, sec)
    }

    /// "48 min" or "2 h 5 min".
    static func minutes(_ seconds: Double) -> String {
        let total = seconds > 0 ? max(1, Int(seconds) / 60) : 0
        return total >= 60 ? "\(total / 60) h \(total % 60) min" : "\(total) min"
    }

    static func size(_ bytes: Int64?) -> String? {
        guard let bytes, bytes > 0 else { return nil }
        return ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
    }

    static func songCount(_ n: Int) -> String { n == 1 ? "1 song" : "\(n) songs" }

    /// Parses "3", "3/12" (ID3 TRCK) into a track number.
    static func trackNumber(from text: String?) -> Int? {
        guard let text else { return nil }
        let head = text.split(separator: "/").first.map(String.init)?.trimmingCharacters(in: .whitespaces) ?? ""
        return Int(head).flatMap { $0 > 0 ? $0 : nil }
    }
}
