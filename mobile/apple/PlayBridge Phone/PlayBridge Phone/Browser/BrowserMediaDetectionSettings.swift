import Foundation

/// Global automatic detection preferences. Explicit website casting remains independent.
struct BrowserMediaDetectionSettings: Codable, Equatable {
    var enabled = true
    var videos = true
    var images = true
    var audio = true
    var subtitles = true
    var domScanning = true
    var networkDetection = true
    var responseScanning = true
    var navigationRescans = true
    var visibilityOverrides = true
    var detectInBridgedSites = false

    private static let key = "browser.mediaDetection"

    static func load(defaults: UserDefaults = .standard) -> Self {
        var result = Self()
        let values = defaults.dictionary(forKey: key) as? [String: Bool] ?? [:]
        result.enabled = values["enabled"] ?? result.enabled
        result.videos = values["videos"] ?? result.videos
        result.images = values["images"] ?? result.images
        result.audio = values["audio"] ?? result.audio
        result.subtitles = values["subtitles"] ?? result.subtitles
        result.domScanning = values["domScanning"] ?? result.domScanning
        result.networkDetection = values["networkDetection"] ?? result.networkDetection
        result.responseScanning = values["responseScanning"] ?? result.responseScanning
        result.navigationRescans = values["navigationRescans"] ?? result.navigationRescans
        result.visibilityOverrides = values["visibilityOverrides"] ?? result.visibilityOverrides
        result.detectInBridgedSites = values["detectInBridgedSites"] ?? result.detectInBridgedSites
        return result
    }

    func allows(_ kind: String) -> Bool {
        switch kind {
        case "image": return enabled && images
        case "audio": return enabled && audio
        case "subtitle": return enabled && subtitles
        default: return enabled && videos
        }
    }

    func save(defaults: UserDefaults = .standard) { defaults.set(values, forKey: Self.key) }

    var values: [String: Bool] {
        [
            "enabled": enabled,
            "videos": videos,
            "images": images,
            "audio": audio,
            "subtitles": subtitles,
            "domScanning": domScanning,
            "networkDetection": networkDetection,
            "responseScanning": responseScanning,
            "navigationRescans": navigationRescans,
            "visibilityOverrides": visibilityOverrides,
            "detectInBridgedSites": detectInBridgedSites,
        ]
    }

    func scriptOptions(enabled: Bool) -> String {
        var options = values
        options["enabled"] = enabled
        let data = try! JSONSerialization.data(withJSONObject: options, options: [.sortedKeys])
        return String(data: data, encoding: .utf8)!
    }
}
