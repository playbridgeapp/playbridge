import Foundation

enum MediaKind: String, CaseIterable {
    case video, audio, image

    static func resolve(explicit: String?, url: String, contentType: String?) -> MediaKind {
        if let explicit, let kind = MediaKind(rawValue: explicit.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()) {
            return kind
        }
        let mime = (contentType ?? "").split(separator: ";").first.map(String.init)?
            .trimmingCharacters(in: .whitespacesAndNewlines).lowercased() ?? ""
        for kind in allCases where mime.hasPrefix(kind.rawValue + "/") { return kind }
        let path = url.components(separatedBy: "?")[0].components(separatedBy: "#")[0].lowercased()
        let ext = path.split(separator: ".").last.map(String.init) ?? ""
        if ["mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac", "weba"].contains(ext) { return .audio }
        if ["jpg", "jpeg", "png", "webp", "avif", "gif", "bmp", "heic", "heif"].contains(ext) { return .image }
        return .video
    }

    func enriching(_ frame: [String: Any]) -> [String: Any] {
        var result = frame
        result["mediaKind"] = rawValue
        return result
    }
}
