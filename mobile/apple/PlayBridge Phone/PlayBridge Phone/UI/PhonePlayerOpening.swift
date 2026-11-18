import Foundation

/// Settings › Phone player › Landscape videos open in.
enum PhoneLandscapeOpening: String, CaseIterable, Identifiable {
    /// Landscape at once, held while the player is open (Android's default).
    case fullscreen
    /// Follows how the phone is held; an upright phone shows the portrait layout.
    case portraitFirst

    static let key = "phone_player_landscape_opening"
    var id: String { rawValue }
    var label: String { self == .fullscreen ? "Fullscreen" : "Portrait first" }
    var summary: String { self == .fullscreen ? "Landscape videos open fullscreen" : "Landscape videos open in portrait first" }

    static func load(from store: UserDefaults = .standard) -> Self {
        store.string(forKey: key).flatMap(Self.init(rawValue:)) ?? .fullscreen
    }
}

/// Settings › Phone player › Ambient mode: in the portrait layout a soft glow of the
/// video's colours fills the space around it. On by default.
enum PhoneAmbientMode {
    static let key = "phone_player_ambient"
    /// How often a new tiny frame is taken while playing.
    static let interval: UInt64 = 1_000_000_000

    /// The picture's size fitted inside `container`. With ambient mode on in the full-screen
    /// layout (Fit), the video surface takes exactly this size so the bars around it are free
    /// for the glow; nil (fill the container) when the picture's size isn't known yet.
    static func fittedSize(width: Int, height: Int, in container: CGSize) -> CGSize? {
        guard width > 0, height > 0, container.width > 0, container.height > 0 else { return nil }
        let scale = min(container.width / Double(width), container.height / Double(height))
        return CGSize(width: (Double(width) * scale).rounded(), height: (Double(height) * scale).rounded())
    }
}

/// How the full-screen player turns when a video opens. Decided once, when the first
/// frame gives the picture's shape; the rotate and Fullscreen buttons take over after.
enum PhonePlayerOpening: Equatable {
    /// No change: the phone's own rotation decides.
    case followPhone
    case holdLandscape
    case holdPortrait

    /// Called when the player opens (size unknown) and again once the first frame gives the
    /// picture's size. Fullscreen turns to landscape at once rather than waiting for the frame,
    /// then a vertical picture turns it back to portrait.
    /// - Returns: nil when there is nothing to do yet.
    static func decide(websiteOrientation: String?, width: Int, height: Int, isAudio: Bool, preference: PhoneLandscapeOpening) -> Self? {
        // A website's requested orientation is applied by PhonePlayerOpeningOrientationView and wins.
        if websiteOrientation == "portrait" || websiteOrientation == "landscape" { return .followPhone }
        if isAudio { return .followPhone }
        guard width > 0, height > 0 else { return preference == .fullscreen ? .holdLandscape : nil }
        if height > width { return .holdPortrait }
        return preference == .fullscreen ? .holdLandscape : .followPhone
    }

    /// A landscape picture on an upright screen gets the portrait layout: video on top,
    /// controls below. Vertical videos and unknown shapes keep the full-screen layout.
    static func usesPortraitLayout(width: Int, height: Int, screenIsLandscape: Bool) -> Bool {
        !screenIsLandscape && width > 0 && height > 0 && width > height
    }

    /// The video box's height in the portrait layout: full width at the picture's shape,
    /// never more than `maxFraction` of the screen.
    static func portraitVideoHeight(width: Int, height: Int, screenWidth: Double, screenHeight: Double, maxFraction: Double = 0.45) -> Double {
        guard width > 0, height > 0, screenWidth > 0 else { return 0 }
        return min(screenWidth * Double(height) / Double(width), max(0, screenHeight) * maxFraction)
    }
}
