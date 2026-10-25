import Foundation

@main struct PhoneFilesModelTests {
    static func media(_ title: String, kind: PhoneMedia.Kind = .audio, added: Date = Date(timeIntervalSince1970: 0),
                      bytes: Int64? = nil, asset: String? = nil, artist: String? = nil, album: String? = nil,
                      track: Int? = nil) -> PhoneMedia {
        PhoneMedia(id: title, title: title, kind: kind, source: asset == nil ? .imported : .photos, addedAt: added,
                   bytes: bytes, assetIdentifier: asset, artist: artist, album: album, trackNumber: track)
    }

    static func main() {
        let t = { (s: Double) in Date(timeIntervalSince1970: s) }
        let videos = [
            media("Beach", kind: .video, added: t(1), bytes: 30, asset: "a"),
            media("beach party", kind: .video, added: t(3), bytes: 10, asset: "b"),
            media("Mountain", kind: .video, added: t(2), bytes: 20, asset: "c"),
            media("Imported beach", kind: .video, added: t(4)),
        ]
        precondition(PhoneFilesModel.visible(videos, album: ["a", "b", "c"], query: "BEACH", sort: .newest).map(\.title)
            == ["beach party", "Beach"])
        precondition(PhoneFilesModel.visible(videos, album: nil, query: "", sort: .largest).map(\.title).prefix(3)
            == ["Beach", "Mountain", "beach party"])
        precondition(PhoneFilesModel.visible(videos, album: nil, query: "", sort: .oldest).map(\.title)
            == ["Beach", "Mountain", "beach party", "Imported beach"])

        let song = media("Track", artist: "Nina", album: "Blue")
        precondition(PhoneFilesModel.matches(song, "nin") && PhoneFilesModel.matches(song, "blu") && !PhoneFilesModel.matches(song, "red"))

        let songs = [
            media("Two", artist: "A", album: "Greatest Hits", track: 2),
            media("One", artist: "A", album: "Greatest Hits", track: 1),
            media("Other", artist: "B", album: "Greatest Hits"),
            media("Loose"),
            media("x", artist: "nina"), media("y", artist: "Nina"),
        ]
        let albums = PhoneFilesModel.albums(songs)
        precondition(albums.count == 2)
        precondition(albums.first { $0.subtitle == "A" }!.songs.map(\.title) == ["One", "Two"])
        precondition(PhoneFilesModel.artists(songs).map { "\($0.title.lowercased())=\($0.songs.count)" } == ["a=2", "b=1", "nina=2"])

        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        let now = calendar.date(from: DateComponents(year: 2026, month: 10, day: 25, hour: 12))!
        func day(_ y: Int, _ m: Int, _ d: Int) -> Date { calendar.date(from: DateComponents(year: y, month: m, day: d, hour: 1))! }
        let photos = [
            media("old", kind: .image, added: day(2025, 3, 2)),
            media("today", kind: .image, added: day(2026, 10, 25)),
            media("yesterday", kind: .image, added: day(2026, 10, 24)),
            media("week", kind: .image, added: day(2026, 10, 20)),
        ]
        let labels = PhoneFilesModel.photoSections(photos, now: now, calendar: calendar, locale: Locale(identifier: "en_GB")).map(\.label)
        precondition(labels.count == 4 && labels[0] == "Today" && labels[1] == "Yesterday", "\(labels)")
        precondition(labels[2].contains("20") && labels[3].contains("2025"), "\(labels)")

        var selection: [String] = []
        for id in ["a", "c", "b", "a"] { selection = PhoneFilesModel.toggle(selection, id) }
        precondition(selection == ["c", "b"])

        precondition(PhoneFilesModel.clock(6730) == "1:52:10" && PhoneFilesModel.clock(8) == "0:08")
        precondition(PhoneFilesModel.minutes(48 * 60) == "48 min" && PhoneFilesModel.minutes(125 * 60) == "2 h 5 min")
        precondition(PhoneFilesModel.trackNumber(from: "3/12") == 3 && PhoneFilesModel.trackNumber(from: "0") == nil
            && PhoneFilesModel.trackNumber(from: "x") == nil)
        print("PASS: phone files model")
    }
}
