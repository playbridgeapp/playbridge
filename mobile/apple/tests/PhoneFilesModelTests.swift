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
        let slidePhotos = [media("p1", kind: .image, added: t(1)), media("p3", kind: .image, added: t(3))]
        let slideVideos = [media("v2", kind: .video, added: t(2))]
        precondition(PhoneFilesModel.slideshow(photos: slidePhotos, videos: slideVideos, includeVideos: true, shuffle: false,
                                               startIndex: 0, limit: 10).map(\.title) == ["p3", "v2", "p1"])
        precondition(PhoneFilesModel.slideshow(photos: slidePhotos, videos: slideVideos, includeVideos: false, shuffle: false,
                                               startIndex: 0, limit: 10).map(\.title) == ["p3", "p1"])
        precondition(PhoneFilesModel.slideshow(photos: slidePhotos, videos: slideVideos, includeVideos: true, shuffle: false,
                                               startIndex: 1, limit: 10).map(\.title) == ["v2", "p1", "p3"])
        precondition(PhoneFilesModel.slideshow(photos: slidePhotos, videos: slideVideos, includeVideos: true, shuffle: false,
                                               startIndex: 0, limit: 2).count == 2)
        let many = (1...20).map { media("p\($0)", kind: .image, added: t(Double($0))) }
        let shuffled = PhoneFilesModel.slideshow(photos: many, videos: [], includeVideos: false, shuffle: true, startIndex: 0, limit: 50)
        precondition(Set(shuffled.map(\.title)) == Set(many.map(\.title)) && shuffled.count == 20)

        checkQueueWindow()
        print("PASS: phone files model")
    }

    /// Applies `plan` to `tv` the way the receiver would.
    static func apply(_ plan: WindowPlan, to tv: [String]) -> [String] {
        var queue = tv.filter { !plan.removes.contains($0) } + plan.adds
        for move in plan.moves {
            queue.removeAll { $0 == move.itemId }
            if let before = move.before, let at = queue.firstIndex(of: before) { queue.insert(move.itemId, at: at) }
            else { queue.append(move.itemId) }
        }
        return queue
    }

    static func checkQueueWindow() {
        func ids(_ range: ClosedRange<Int>) -> [String] { range.map { "e\($0)" } }
        let order = ids(0...9_999)
        // Start fills 20 ahead; nothing to do while enough is queued; top up at 10 left.
        precondition(PhoneQueueWindow.plan(order: order, cursor: 0, tv: ["e0"], tvCurrent: "e0").adds == ids(1...20))
        precondition(PhoneQueueWindow.plan(order: order, cursor: 4, tv: ids(0...20), tvCurrent: "e4").isEmpty)
        precondition(PhoneQueueWindow.plan(order: order, cursor: 10, tv: ids(0...20), tvCurrent: "e10").adds == ids(21...30))
        // Played items are trimmed down to 10.
        precondition(PhoneQueueWindow.plan(order: order, cursor: 16, tv: ids(0...32), tvCurrent: "e16").removes == ids(0...5))
        // Playing through 500 items keeps the window small and the next item queued.
        var tv = ["e0"]
        for cursor in 0..<500 {
            tv = apply(PhoneQueueWindow.plan(order: order, cursor: cursor, tv: tv, tvCurrent: "e\(cursor)"), to: tv)
            precondition(tv.count <= 36 && tv[tv.firstIndex(of: "e\(cursor)")! + 1] == "e\(cursor + 1)")
        }
        // Play next inside the window: add, then move into place.
        let edited = ["e0", "x", "y"] + ids(1...9_999)
        let plan = PhoneQueueWindow.plan(order: edited, cursor: 0, tv: ids(0...15), tvCurrent: "e0")
        precondition(plan.adds == ["x", "y"] && apply(plan, to: ids(0...15)) == ["e0", "x", "y"] + ids(1...15))
        // Add to queue past the window sends nothing; other controllers' items are left alone.
        precondition(PhoneQueueWindow.plan(order: order + ["z"], cursor: 4, tv: ids(0...20), tvCurrent: "e4").isEmpty)
        precondition(PhoneQueueWindow.plan(order: order, cursor: 0, tv: ["e0", "foreign"] + ids(1...14), tvCurrent: "e0").isEmpty)
        // A far jump back: the old window is dropped, not the gap filled.
        let jumped = PhoneQueueWindow.plan(order: order, cursor: 100, tv: ["e100"] + ids(5_001...5_020), tvCurrent: "e100")
        precondition(jumped.removes == ids(5_001...5_020) && jumped.adds == ids(101...120))
        let appended = ids(4_990...5_020) + ["e100"]
        let after = apply(PhoneQueueWindow.plan(order: order, cursor: 100, tv: appended, tvCurrent: "e100"), to: appended)
        precondition(Array(after[after.firstIndex(of: "e100")!...]) == ids(100...120))
        // The receiver's 200-item limit.
        precondition(PhoneQueueWindow.plan(order: order, cursor: 0, tv: ["e0"] + (1...195).map { "f\($0)" }, tvCurrent: "e0").adds == ids(1...4))
        // Smaller window for Photos videos.
        precondition(PhoneQueueWindow.plan(order: order, cursor: 0, tv: ["e0"], tvCurrent: "e0",
                                           limits: .init(ahead: 2, aheadLow: 1)).adds == ids(1...2))
        // Edits made on the receiver: removals and reordering are taken in; unsent items stay.
        precondition(PhoneQueueWindow.adopt(order: ids(0...7), cursor: 1, sent: Set(ids(0...5)),
                                            tv: ["e0", "e1", "e2", "e4", "e5"], tvCurrent: "e1")
            == ["e0", "e1", "e2", "e4", "e5", "e6", "e7"])
        precondition(PhoneQueueWindow.adopt(order: ids(0...5), cursor: 0, sent: Set(ids(0...3)),
                                            tv: ["e0", "e3", "e1", "e2"], tvCurrent: "e0")
            == ["e0", "e3", "e1", "e2", "e4", "e5"])
        precondition(PhoneQueueWindow.adopt(order: ids(0...30), cursor: 0, sent: Set(ids(0...20)), tv: ids(0...20), tvCurrent: "e0")
            == ids(0...30))
    }
}
