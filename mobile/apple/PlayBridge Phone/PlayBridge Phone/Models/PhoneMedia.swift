import Foundation
import UniformTypeIdentifiers

struct PhoneMedia: Codable, Identifiable, Hashable {
    enum Kind: String, Codable, CaseIterable { case video, image, audio
        var title: String { switch self { case .video: return "Videos"; case .image: return "Images"; case .audio: return "Audio" } }
        var icon: String { switch self { case .video: return "play.rectangle"; case .image: return "photo"; case .audio: return "music.note" } }
        static func classify(_ url: URL) -> Kind? {
            let ext = url.pathExtension.lowercased()
            if ["mkv", "webm", "avi", "ts", "m2ts", "flv"].contains(ext) { return .video }
            if ["flac", "ogg", "oga", "opus"].contains(ext) { return .audio }
            guard let type = UTType(filenameExtension: ext) else { return nil }
            if type.conforms(to: .image) { return .image }
            if type.conforms(to: .audio) { return .audio }
            if type.conforms(to: .movie) || type.conforms(to: .video) { return .video }
            return nil
        }
    }
    enum Source: String, Codable { case imported, photos, download
        var title: String { switch self { case .imported: return "Files"; case .photos: return "Photos"; case .download: return "Downloads" } }
    }
    let id: String
    var title: String
    let kind: Kind
    let source: Source
    let addedAt: Date
    var filename: String?
    var duration: Double?
    var bytes: Int64?
    var assetIdentifier: String?
}
