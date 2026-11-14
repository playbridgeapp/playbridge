import Combine
import Foundation
#if canImport(AVFoundation) && canImport(CoreMedia)
import AVFoundation
import CoreMedia
#endif
#if canImport(UIKit)
import UIKit
#endif

nonisolated enum SubtitleSizeOption: String, CaseIterable, Equatable {
    case small, medium, large, extraLarge = "extra_large"

    var title: String {
        switch self {
        case .small: return "Small"
        case .medium: return "Medium"
        case .large: return "Large"
        case .extraLarge: return "Extra large"
        }
    }

    var scale: Double {
        switch self {
        case .small: return 0.8
        case .medium: return 1.0
        case .large: return 1.25
        case .extraLarge: return 1.5
        }
    }

    func next() -> Self {
        let all = Self.allCases
        return all[((all.firstIndex(of: self) ?? 0) + 1) % all.count]
    }
}

nonisolated enum SubtitleFontOption: String, CaseIterable, Equatable {
    case `default`, sans, serif, mono, custom

    static var builtInCases: [SubtitleFontOption] {
        [.default, .sans, .serif, .mono]
    }

    var title: String {
        switch self {
        case .default: return "Default"
        case .sans: return "Sans"
        case .serif: return "Serif"
        case .mono: return "Monospace"
        case .custom: return "Custom"
        }
    }

    func next() -> Self {
        let all = Self.builtInCases
        return all[((all.firstIndex(of: self) ?? 0) + 1) % all.count]
    }
}

nonisolated enum SubtitleBackgroundOption: String, CaseIterable, Equatable {
    case none, dim, box

    init?(storageValue: String) {
        switch storageValue {
        case "none": self = .none
        case "dim", "translucent": self = .dim
        case "box", "solid": self = .box
        default: return nil
        }
    }

    var wireValue: String {
        switch self {
        case .none: return "none"
        case .dim: return "translucent"
        case .box: return "solid"
        }
    }

    var title: String {
        switch self {
        case .none: return "None"
        case .dim: return "Semi-transparent black"
        case .box: return "Solid black"
        }
    }

    var opacity: Double {
        switch self {
        case .none: return 0.0
        case .dim: return 0.5
        case .box: return 1.0
        }
    }

    func next() -> Self {
        let all = Self.allCases
        return all[((all.firstIndex(of: self) ?? 0) + 1) % all.count]
    }
}

nonisolated enum SubtitleEdgeOption: String, CaseIterable, Equatable {
    case outline, dropShadow, none

    init?(storageValue: String) {
        switch storageValue {
        case "outline": self = .outline
        case "dropShadow", "shadow": self = .dropShadow
        case "none": self = .none
        default: return nil
        }
    }

    var wireValue: String {
        switch self {
        case .outline: return "outline"
        case .dropShadow: return "shadow"
        case .none: return "none"
        }
    }

    var title: String {
        switch self {
        case .outline: return "Outline"
        case .dropShadow: return "Drop shadow"
        case .none: return "None"
        }
    }

    func next() -> Self {
        let all = Self.allCases
        return all[((all.firstIndex(of: self) ?? 0) + 1) % all.count]
    }
}

nonisolated struct SubtitleStylePreferences: Equatable {
    var size: SubtitleSizeOption = .medium
    var font: SubtitleFontOption = .default
    var customFont: String? = nil
    var background: SubtitleBackgroundOption = .none
    var edge: SubtitleEdgeOption = .outline
    var overrideStyled: Bool = false

    static func current(defaults: UserDefaults = .standard) -> SubtitleStylePreferences {
        let rawFont = defaults.string(forKey: "subtitle_font").flatMap(SubtitleFontOption.init(rawValue:)) ?? .default
        let rawCustom = defaults.string(forKey: "subtitle_custom_font")?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let customName = (rawFont == .custom && !(rawCustom?.isEmpty ?? true)) ? rawCustom : nil
        return SubtitleStylePreferences(
            size: defaults.string(forKey: "subtitle_size").flatMap(SubtitleSizeOption.init(rawValue:)) ?? .medium,
            font: customName != nil ? .custom : (rawFont == .custom ? .default : rawFont),
            customFont: customName,
            background: defaults.string(forKey: "subtitle_bg").flatMap(SubtitleBackgroundOption.init(storageValue:)) ?? .none,
            edge: defaults.string(forKey: "subtitle_edge").flatMap(SubtitleEdgeOption.init(storageValue:)) ?? .outline,
            overrideStyled: defaults.bool(forKey: "subtitle_override_styled")
        )
    }

    func normalized(installedFonts: [String]) -> SubtitleStylePreferences {
        var copy = self
        if copy.font == .custom {
            let trimmed = copy.customFont?
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .prefix(128)
                .description
            if let trimmed, !trimmed.isEmpty, installedFonts.contains(trimmed) {
                copy.customFont = trimmed
            } else {
                copy.font = .default
                copy.customFont = nil
            }
        } else {
            copy.customFont = nil
        }
        return copy
    }

    static func fromWire(
        size: String?,
        background: String?,
        edge: String?,
        font: String?,
        overrideStyled: Bool?,
        customFont: String?,
        installedFonts: [String]
    ) -> SubtitleStylePreferences {
        let trimmedCustom = customFont?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .prefix(128)
            .description
        let isCustom = font == "custom"
            && !(trimmedCustom?.isEmpty ?? true)
            && installedFonts.contains(trimmedCustom ?? "")
        return SubtitleStylePreferences(
            size: size.flatMap(SubtitleSizeOption.init(rawValue:)) ?? .medium,
            font: isCustom ? .custom : .default,
            customFont: isCustom ? trimmedCustom : nil,
            background: background.flatMap(SubtitleBackgroundOption.init(storageValue:)) ?? .none,
            edge: edge.flatMap(SubtitleEdgeOption.init(storageValue:)) ?? .outline,
            overrideStyled: overrideStyled ?? false
        )
    }

    var wireFontValue: String {
        (font == .custom && !(customFont?.isEmpty ?? true)) ? "custom" : "default"
    }

    var displayFontTitle: String {
        if font == .custom, let customFont, !customFont.isEmpty {
            return customFont
        }
        return font.title
    }

    func wireDictionary(installedFonts: [String]?) -> [String: Any] {
        var dict: [String: Any] = [
            "type": "subtitle_style",
            "size": size.rawValue,
            "background": background.wireValue,
            "edge": edge.wireValue,
            "font": wireFontValue,
            "overrideStyled": overrideStyled,
        ]
        if wireFontValue == "custom", let customFont {
            dict["customFont"] = customFont
        }
        if let installedFonts {
            dict["installedFonts"] = installedFonts
        }
        return dict
    }

    func save(to defaults: UserDefaults = .standard) {
        defaults.set(size.rawValue, forKey: "subtitle_size")
        defaults.set(font.rawValue, forKey: "subtitle_font")
        if let customFont, font == .custom {
            defaults.set(customFont, forKey: "subtitle_custom_font")
        } else {
            defaults.removeObject(forKey: "subtitle_custom_font")
        }
        defaults.set(background.wireValue, forKey: "subtitle_bg")
        defaults.set(edge.wireValue, forKey: "subtitle_edge")
        defaults.set(overrideStyled, forKey: "subtitle_override_styled")
    }

    var previewFontSizePoints: Double {
        24.0 * size.scale
    }

    var previewBackgroundOpacity: Double {
        background.opacity
    }

    var mpvSubScale: String {
        switch size {
        case .small: return "0.8"
        case .medium: return "1.0"
        case .large: return "1.25"
        case .extraLarge: return "1.5"
        }
    }

    var mpvSubFont: String {
        switch font {
        case .default: return "sans-serif"
        case .sans: return "Helvetica Neue"
        case .serif: return "Times New Roman"
        case .mono: return "Menlo"
        case .custom: return customFont ?? "sans-serif"
        }
    }

    var mpvBorderStyle: String {
        background == .none ? "outline-and-shadow" : "opaque-box"
    }

    var mpvOutlineColor: String {
        switch background {
        case .none: return "#FF000000"
        case .dim: return "#80000000"
        case .box: return "#FF000000"
        }
    }

    var mpvBackColor: String {
        background == .none ? "#B3000000" : "#00000000"
    }

    var mpvOutlineSize: String {
        if background != .none { return "3" }
        return edge == .outline ? "2" : "0"
    }

    var mpvShadowOffset: String {
        guard background == .none else { return "0" }
        switch edge {
        case .outline: return "1"
        case .dropShadow: return "2.5"
        case .none: return "0"
        }
    }

    func mpvProperties() -> [(String, String)] {
        [
            ("sub-scale", mpvSubScale),
            ("sub-font", mpvSubFont),
            ("sub-color", "#FFFFFFFF"),
            ("sub-border-style", mpvBorderStyle),
            ("sub-outline-color", mpvOutlineColor),
            ("sub-back-color", mpvBackColor),
            ("sub-outline-size", mpvOutlineSize),
            ("sub-shadow-offset", mpvShadowOffset),
            ("sub-ass-override", overrideStyled ? "force" : "scale"),
        ]
    }

    #if canImport(AVFoundation) && canImport(CoreMedia)
    func avTextStyleRules() -> [AVTextStyleRule] {
        var attrs: [String: Any] = [:]

        let relSize = NSNumber(value: Int((100.0 * size.scale).rounded()))
        attrs[kCMTextMarkupAttribute_RelativeFontSize as String] = relSize
        attrs[kCMTextMarkupAttribute_ForegroundColorARGB as String] = [1.0, 1.0, 1.0, 1.0]

        switch font {
        case .default, .custom:
            // AVFoundation's embedded media caption renderer ignores process-scoped custom fonts,
            // so custom uploaded fonts apply to mpv and AVPlayer sidecar overlays rather than embedded tracks.
            break
        case .sans:
            attrs[kCMTextMarkupAttribute_GenericFontFamilyName as String] = kCMTextMarkupGenericFontName_SansSerif
        case .serif:
            attrs[kCMTextMarkupAttribute_GenericFontFamilyName as String] = kCMTextMarkupGenericFontName_Serif
        case .mono:
            attrs[kCMTextMarkupAttribute_GenericFontFamilyName as String] = kCMTextMarkupGenericFontName_Monospace
        }

        attrs[kCMTextMarkupAttribute_BackgroundColorARGB as String] = [background.opacity, 0.0, 0.0, 0.0]

        switch edge {
        case .none:
            attrs[kCMTextMarkupAttribute_CharacterEdgeStyle as String] = kCMTextMarkupCharacterEdgeStyle_None
        case .outline:
            attrs[kCMTextMarkupAttribute_CharacterEdgeStyle as String] = kCMTextMarkupCharacterEdgeStyle_Uniform
        case .dropShadow:
            attrs[kCMTextMarkupAttribute_CharacterEdgeStyle as String] = kCMTextMarkupCharacterEdgeStyle_DropShadow
        }

        if let rule = AVTextStyleRule(textMarkupAttributes: attrs) {
            return [rule]
        }
        return []
    }
    #endif

    #if canImport(UIKit)
    @MainActor
    func overlayFont(fontLibrary: SubtitleFontLibrary? = nil) -> UIFont {
        let fontLibrary = fontLibrary ?? .shared
        let pointSize = CGFloat((40.0 * size.scale).rounded())
        switch font {
        case .default:
            return .systemFont(ofSize: pointSize, weight: .semibold)
        case .sans:
            return UIFont(name: "HelveticaNeue-Medium", size: pointSize)
                ?? .systemFont(ofSize: pointSize, weight: .semibold)
        case .serif:
            return UIFont(name: "TimesNewRomanPS-BoldMT", size: pointSize)
                ?? .systemFont(ofSize: pointSize, weight: .semibold)
        case .mono:
            return UIFont(name: "Menlo-Bold", size: pointSize)
                ?? .monospacedSystemFont(ofSize: pointSize, weight: .semibold)
        case .custom:
            if let customFont, let loaded = fontLibrary.uiFont(family: customFont, size: pointSize) {
                return loaded
            }
            return .systemFont(ofSize: pointSize, weight: .semibold)
        }
    }

    @MainActor
    func overlayAttributedText(_ text: String, fontLibrary: SubtitleFontLibrary? = nil) -> NSAttributedString {
        let fontLibrary = fontLibrary ?? .shared
        let paragraph = NSMutableParagraphStyle()
        paragraph.alignment = .center
        var attrs: [NSAttributedString.Key: Any] = [
            .font: overlayFont(fontLibrary: fontLibrary),
            .foregroundColor: UIColor.white,
            .paragraphStyle: paragraph,
        ]
        if edge == .outline {
            attrs[.strokeColor] = UIColor.black
            attrs[.strokeWidth] = -4.0
        }
        return NSAttributedString(string: text, attributes: attrs)
    }

    @MainActor
    func applyToOverlay(captionView: UIView, label: UILabel, fontLibrary: SubtitleFontLibrary? = nil) {
        let fontLibrary = fontLibrary ?? .shared
        captionView.backgroundColor = background == .none
            ? .clear
            : UIColor.black.withAlphaComponent(background.opacity)
        captionView.layer.cornerRadius = background == .none ? 0 : 8
        if edge == .dropShadow {
            label.layer.shadowColor = UIColor.black.cgColor
            label.layer.shadowOpacity = 0.85
            label.layer.shadowOffset = CGSize(width: 2, height: 3)
            label.layer.shadowRadius = 3
        } else {
            label.layer.shadowOpacity = 0
            label.layer.shadowRadius = 0
            label.layer.shadowOffset = .zero
        }
        if let text = label.text, !text.isEmpty {
            label.attributedText = overlayAttributedText(text, fontLibrary: fontLibrary)
        } else {
            label.font = overlayFont(fontLibrary: fontLibrary)
        }
    }
    #endif
}

extension Notification.Name {
    static let playBridgeSubtitleStyleDidChange = Notification.Name("PlayBridgeSubtitleStyleDidChange")
}

/// Single source of truth for the TV's persisted subtitle style and installed custom fonts.
@MainActor
final class SubtitleStyleStore: ObservableObject {
    static let shared = SubtitleStyleStore()

    @Published private(set) var style: SubtitleStylePreferences
    @Published private(set) var installedFonts: [String]
    @Published private(set) var fontRevision: Int = 0

    let fontLibrary: SubtitleFontLibrary
    private let defaults: UserDefaults
    private var suppressDefaultsSync = false
    private var defaultsObserver: NSObjectProtocol?

    init(
        defaults: UserDefaults = .standard,
        fontLibrary: SubtitleFontLibrary? = nil,
        observeUserDefaults: Bool = true
    ) {
        let fontLibrary = fontLibrary ?? .shared
        self.defaults = defaults
        self.fontLibrary = fontLibrary
        let families = fontLibrary.families()
        let initial = SubtitleStylePreferences.current(defaults: defaults).normalized(installedFonts: families)
        self.installedFonts = families
        self.style = initial
        fontLibrary.select(family: initial.customFont)
        if initial != SubtitleStylePreferences.current(defaults: defaults) {
            initial.save(to: defaults)
        }

        if observeUserDefaults {
            defaultsObserver = NotificationCenter.default.addObserver(
                forName: UserDefaults.didChangeNotification,
                object: defaults,
                queue: .main
            ) { [weak self] _ in
                Task { @MainActor [weak self] in
                    self?.syncFromDefaultsIfNeeded()
                }
            }
        }
    }

    deinit {
        if let defaultsObserver {
            NotificationCenter.default.removeObserver(defaultsObserver)
        }
    }

    @discardableResult
    func setStyle(_ next: SubtitleStylePreferences) -> Bool {
        let normalized = next.normalized(installedFonts: installedFonts)
        guard normalized != style else { return false }
        applyNormalizedStyle(normalized)
        return true
    }

    @discardableResult
    func applyFromPhoneJSON(_ json: [String: Any]) -> (style: SubtitleStylePreferences, changed: Bool) {
        let overrideStyled: Bool?
        if let number = json["overrideStyled"] as? NSNumber,
           CFGetTypeID(number) == CFBooleanGetTypeID() {
            overrideStyled = number.boolValue
        } else if let str = json["overrideStyled"] as? String {
            overrideStyled = Bool(str)
        } else {
            overrideStyled = nil
        }

        let parsed = SubtitleStylePreferences.fromWire(
            size: json["size"] as? String,
            background: json["background"] as? String,
            edge: json["edge"] as? String,
            font: json["font"] as? String,
            overrideStyled: overrideStyled,
            customFont: json["customFont"] as? String,
            installedFonts: installedFonts
        )
        let changed = setStyle(parsed)
        return (style, changed)
    }

    func installFont(_ data: Data) -> SubtitleFontLibrary.AddResult {
        let result = fontLibrary.add(data)
        if case .added(let family) = result {
            completeFontInstall(family: family)
        }
        return result
    }

    func completeFontInstall(family: String) {
        installedFonts = fontLibrary.families()
        fontRevision &+= 1
        // Force relink in case an updated font replaced one of the same family name.
        fontLibrary.select(family: nil)
        var next = style
        next.font = .custom
        next.customFont = family
        if !setStyle(next) {
            fontLibrary.select(family: family)
            notifyObservers()
        }
    }

    @discardableResult
    func removeFont(_ family: String) -> Bool {
        guard fontLibrary.remove(family: family) else { return false }
        installedFonts = fontLibrary.families()
        fontRevision &+= 1
        let normalized = style.normalized(installedFonts: installedFonts)
        if normalized != style {
            applyNormalizedStyle(normalized)
        } else {
            notifyObservers()
        }
        return true
    }

    func wireDictionary() -> [String: Any] {
        style.wireDictionary(installedFonts: installedFonts)
    }

    private func syncFromDefaultsIfNeeded() {
        guard !suppressDefaultsSync else { return }
        let normalized = SubtitleStylePreferences.current(defaults: defaults).normalized(installedFonts: installedFonts)
        guard normalized != style else { return }
        fontLibrary.select(family: normalized.customFont)
        style = normalized
        notifyObservers()
    }

    private func applyNormalizedStyle(_ normalized: SubtitleStylePreferences) {
        fontLibrary.select(family: normalized.customFont)
        suppressDefaultsSync = true
        normalized.save(to: defaults)
        suppressDefaultsSync = false
        style = normalized
        notifyObservers()
    }

    private func notifyObservers() {
        NotificationCenter.default.post(name: .playBridgeSubtitleStyleDidChange, object: self)
    }
}
